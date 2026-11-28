package com.github.yutaplug.fixes.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.EditText
import androidx.core.content.ContextCompat
import com.discord.models.domain.emoji.Emoji
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.google.android.material.textfield.TextInputLayout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.github.yutaplug.fixes.FixPatcher
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.GatewayAPI
import com.aliucord.Logger
import com.aliucord.fragments.InputDialog
import com.aliucord.patcher.Hook
import com.aliucord.utils.SerializedName
import com.discord.api.channel.Channel
import com.discord.app.AppFragment
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.stores.StoreStream
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.discord.widgets.voice.fullscreen.WidgetCallFullscreen
import com.discord.widgets.voice.fullscreen.WidgetCallPreviewFullscreen
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheetViewModel
import rx.functions.Action1
import rx.functions.Action2
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Voice channel statuses, as on current Discord: the gateway sends them on request (opcode 43, REQUEST_CHANNEL_INFO,
 * answered with CHANNEL_INFO) and pushes changes (VOICE_CHANNEL_STATUS_UPDATE); they're set with
 * PUT /channels/{id}/voice-status, which needs the Set Voice Channel Status permission. Part of [VoiceFix].
 */
internal class ChannelStatus(private val patcher: FixPatcher, private val logger: Logger) {
    // Gson models for the gateway events (snowflakes arrive as strings, which Gson reads into Long)
    class StatusUpdate(
        @SerializedName("id") val id: Long = 0,
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("status") val status: String? = null,
    )

    class ChannelInfo(
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("channels") val channels: Array<ChannelEntry>? = null,
    )

    class ChannelEntry(
        @SerializedName("id") val id: Long = 0,
        @SerializedName("status") val status: String? = null,
    )

    /** For READY / RESUMED: none of the data is needed, so Gson skips it. */
    class Empty

    private val statuses = ConcurrentHashMap<Long, String>()
    private val requestedGuilds = ConcurrentHashMap.newKeySet<Long>()

    @Volatile private var running = false
    private var channelList: WeakReference<RecyclerView.Adapter<*>>? = null
    private var sheet: WeakReference<WidgetVoiceBottomSheet>? = null
    private var sheetState: WidgetVoiceBottomSheetViewModel.ViewState? = null
    private var fullscreen: WeakReference<AppFragment>? = null

    fun start() {
        running = true
        registerEvents()

        // Channel list: the status goes in the row's second line, which Discord only uses for an event's topic.
        patcher.patch(
            WidgetChannelsListAdapter.ItemChannelVoice::class.java.getDeclaredMethod(
                "onConfigure",
                Int::class.javaPrimitiveType,
                ChannelListItem::class.java,
            ),
            Hook { param ->
                val holder = param.thisObject as WidgetChannelsListAdapter.ItemChannelVoice
                val item = param.args[1] as? ChannelListItemVoiceChannel ?: return@Hook
                channelList = WeakReference(WidgetChannelsListAdapter.ItemChannelVoice.`access$getAdapter$p`(holder))
                val channel = item.channel
                requestStatuses(channel.i())
                val topic = holder.itemView.findViewById<TextView>(id("channels_item_voice_channel_event_topic"))
                    ?: return@Hook
                if (item.guildScheduledEvent != null) {
                    // An event's topic wins, like on current Discord; it keeps its own two lines.
                    topic.maxLines = 2
                    return@Hook
                }
                val status = statuses[channel.k()]
                if (status == null) {
                    topic.visibility = View.GONE
                } else {
                    // One line, like current Discord (rows are recycled, so set it every time).
                    topic.maxLines = 1
                    topic.text = render(status, topic.textSize)
                    topic.visibility = View.VISIBLE
                }
            },
        )

        // Voice panel: the status replaces the server name under the channel name; tapping it edits it.
        // configureUI is a private method that ART can inline, so hook the view state observer that calls it
        // (its bridge invoke(Object), which RxJava calls through Function1, so it's never inlined).
        val observer = Class.forName("com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet\$onResume\$3")
        val owner = observer.getDeclaredField("this\$0").apply { isAccessible = true }
        patcher.patch(
            observer.getDeclaredMethod("invoke", Any::class.java),
            Hook { param ->
                val s = owner.get(param.thisObject) as? WidgetVoiceBottomSheet ?: return@Hook
                val state = param.args[0] as? WidgetVoiceBottomSheetViewModel.ViewState ?: return@Hook
                sheet = WeakReference(s)
                sheetState = state
                configureHeader(s, state)
            },
        )

        // Fullscreen call screens: with text in voice (now on in practically every server), tapping a voice
        // channel opens these instead of the voice panel. Both set up their toolbar menu through this public
        // method (the call screen only when its menu changes), so the status goes in the toolbar's subtitle and
        // tapping the title edits it.
        patcher.patch(
            AppFragment::class.java.getDeclaredMethod(
                "setActionBarOptionsMenu",
                Int::class.javaPrimitiveType,
                Action2::class.java,
                Action1::class.java,
            ),
            Hook { param ->
                val fragment = param.thisObject
                if (fragment !is WidgetCallFullscreen && fragment !is WidgetCallPreviewFullscreen) return@Hook
                fullscreen = WeakReference(fragment as AppFragment)
                configureToolbar(fragment)
            },
        )
    }

    fun stop() {
        running = false
        unregisterEvents()
        statuses.clear()
        requestedGuilds.clear()
        refresh()
    }

    // region gateway

    private fun registerEvents() {
        GatewayAPI.onEvent("VOICE_CHANNEL_STATUS_UPDATE", StatusUpdate::class.java) { e ->
            if (!running) return@onEvent
            setStatus(e.id, e.status)
            refresh()
        }
        GatewayAPI.onEvent("CHANNEL_INFO", ChannelInfo::class.java) { e ->
            if (!running) return@onEvent
            e.channels?.forEach { setStatus(it.id, it.status) }
            refresh()
        }
        // A new or resumed session: ask again, like the web client does.
        GatewayAPI.onEvent("READY", Empty::class.java) { requestedGuilds.clear() }
        GatewayAPI.onEvent("RESUMED", Empty::class.java) { requestedGuilds.clear() }
    }

    private fun unregisterEvents() {
        try {
            val ours = setOf(StatusUpdate::class.java, ChannelInfo::class.java, Empty::class.java)
            val list = GatewayAPI::class.java.getMethod("getRegisteredEventListeners").invoke(GatewayAPI)
                as MutableList<*>
            val typeGetter = Class.forName("com.aliucord.api.GatewayAPI\$RegisteredEventListener").getMethod("getType")
            list.removeAll { it != null && typeGetter.invoke(it) in ours }
        } catch (e: Throwable) {
            logger.error("Couldn't remove the gateway listeners", e)
        }
    }

    private fun setStatus(channelId: Long, status: String?) {
        if (status == null || status.trim().isEmpty()) statuses.remove(channelId) else statuses[channelId] = status
    }

    /** Asks the gateway for a server's voice channel statuses once per session (opcode 43, REQUEST_CHANNEL_INFO). */
    private fun requestStatuses(guildId: Long) {
        if (guildId == 0L || !requestedGuilds.add(guildId)) return
        try {
            val payload = HashMap<String, Any>()
            payload["guild_id"] = guildId.toString()
            payload["fields"] = arrayListOf("status")
            if (!GatewaySend.send(REQUEST_CHANNEL_INFO, payload)) requestedGuilds.remove(guildId)
        } catch (e: Throwable) {
            requestedGuilds.remove(guildId)
            logger.error("Couldn't request voice channel statuses", e)
        }
    }

    // endregion

    // region UI

    /**
     * [status] with its custom emojis (<:name:id>, <a:name:id>) drawn as images sized to the text, like current
     * Discord. Emojis whose image isn't loaded yet show as :name: and are fetched; the views refresh once they're in.
     */
    private fun render(status: String, textSize: Float): CharSequence {
        val text = status.replace('\n', ' ')
        val matcher = EMOJI.matcher(text)
        if (!matcher.find()) return text
        val out = SpannableStringBuilder()
        val size = Math.round(textSize * 1.2f).coerceAtLeast(1)
        var last = 0
        do {
            out.append(text, last, matcher.start())
            val name = matcher.group(1).orEmpty()
            val emojiId = matcher.group(2)?.toLongOrNull()
            val span = emojiId?.let { emojiSpan(it, size) }
            if (span == null) {
                out.append(':').append(name).append(':')
            } else {
                val start = out.length
                out.append(name.ifEmpty { "emoji" })
                out.setSpan(span, start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = matcher.end()
        } while (matcher.find())
        out.append(text, last, text.length)
        return out
    }

    /** The emoji's image if it's loaded; otherwise it's fetched and the views refresh once it's in. */
    private fun emojiImage(emojiId: Long): Bitmap? = EmojiImages.get(emojiId) { refresh() }

    private fun refresh() {
        Utils.mainThread.post {
            channelList?.get()?.notifyDataSetChanged()
            val s = sheet?.get()
            val state = sheetState
            if (s != null && state != null && s.isAdded && running) configureHeader(s, state)
            val f = fullscreen?.get()
            if (f != null && f.isAdded) configureToolbar(f)
            editing?.get()?.let { showEmojis(it.text, it.textSize) }
        }
    }

    private fun configureToolbar(fragment: AppFragment) {
        val title = fragment.appActivity?.e() ?: return
        val channelId = fragment.mostRecentIntent.getLongExtra("com.discord.intent.extra.EXTRA_CHANNEL_ID", -1L)
        val channel = StoreStream.getChannels().getChannel(channelId)
        if (!running || channel == null || channel.i() == 0L) {
            fragment.setActionBarSubtitle(null)
            title.setOnClickListener(null)
            title.isClickable = false
            return
        }
        requestStatuses(channel.i())
        val canSet = canSetStatus(channel)
        val textSize = title.findViewById<TextView>(id("toolbar_title_subtext"))?.textSize ?: title.title.textSize
        val status = statuses[channel.k()]?.let { render(it, textSize) }
        fragment.setActionBarSubtitle(status ?: if (canSet) "Set a channel status" else null)
        title.setOnClickListener(if (canSet) View.OnClickListener { editStatus(fragment, channel) } else null)
        title.isClickable = canSet
    }

    private fun configureHeader(sheet: WidgetVoiceBottomSheet, state: WidgetVoiceBottomSheetViewModel.ViewState) {
        val root = sheet.view ?: return
        val title = root.findViewById<TextView>(id("voice_bottom_sheet_header_title")) ?: return
        val subtitle = root.findViewById<TextView>(id("voice_bottom_sheet_header_subtitle")) ?: return
        val channel = state.channel
        if (channel == null || channel.i() == 0L) return // DM calls have no status
        requestStatuses(channel.i())
        val status = statuses[channel.k()]?.let { render(it, subtitle.textSize) }
        val canSet = canSetStatus(channel)
        val text = status ?: if (canSet) "Set a channel status" else state.subtitle
        subtitle.text = text
        subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val click = if (canSet) View.OnClickListener { editStatus(sheet, channel) } else null
        title.setOnClickListener(click)
        subtitle.setOnClickListener(click)
        title.isClickable = canSet
        subtitle.isClickable = canSet
    }

    /**
     * Server owners and Administrators get Discord 126.21's Permission.ALL, a fixed mask of the permissions it
     * knew about, which predates Set Voice Channel Status (bit 48). So Administrator counts as having it too.
     */
    private fun canSetStatus(channel: Channel): Boolean {
        val permissions = StoreStream.getPermissions().permissionsByChannel[channel.k()] ?: return false
        return permissions and (SET_VOICE_CHANNEL_STATUS or ADMINISTRATOR) != 0L
    }

    private fun editStatus(sheet: Fragment, channel: Channel) {
        val current = statuses[channel.k()].orEmpty()
        val dialog = InputDialog()
        dialog.setTitle("Channel status")
            .setDescription("Shown under the channel's name. Leave it empty to clear it.")
            .setPlaceholderText("What's going on?")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            .setOnOkListener {
                val text = dialog.input.trim()
                if (text.length > MAX_LENGTH) {
                    dialog.inputLayout.error = "That's ${text.length} characters; the limit is $MAX_LENGTH"
                    return@setOnOkListener
                }
                dialog.dismiss()
                save(channel.k(), text)
            }
        dialog.setOnDialogShownListener {
            val input = dialog.inputLayout.editText ?: return@setOnDialogShownListener
            editing = WeakReference(input)
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) = showEmojis(s, input.textSize)
            })
            input.setText(current)
            input.setSelection(input.text.length)
            addEmojiButton(dialog, input, channel.i())
        }
        val fm = if (sheet.isAdded) sheet.parentFragmentManager else Utils.appActivity.supportFragmentManager
        dialog.show(fm, "VoiceChannelStatus")
    }

    /** The status editor's text field, so its emojis are drawn once their images have loaded. */
    private var editing: WeakReference<EditText>? = null

    /**
     * An emoji button at the end of the text field, opening Discord's own emoji picker (the one reactions use).
     * The picked emoji goes in at the cursor in its message form (`<:name:id>`, or the character itself).
     */
    private fun addEmojiButton(dialog: InputDialog, input: EditText, guildId: Long) {
        val layout = dialog.inputLayout
        layout.endIconMode = TextInputLayout.END_ICON_CUSTOM
        layout.endIconDrawable = ContextCompat.getDrawable(input.context, Utils.getResId("ic_emoji_24dp", "drawable"))
        layout.endIconContentDescription = "Emoji"
        layout.setEndIconOnClickListener {
            val listener = object : EmojiPickerListener {
                override fun onEmojiPicked(emoji: Emoji) {
                    val code = emoji.messageContentReplacement ?: return
                    val text = input.text
                    val start = input.selectionStart.coerceIn(0, text.length)
                    val end = input.selectionEnd.coerceIn(start, text.length)
                    text.replace(start, end, code)
                    input.setSelection((start + code.length).coerceAtMost(input.text.length))
                }
            }
            val context = if (guildId != 0L) EmojiPickerContextType.Guild(guildId) else EmojiPickerContextType.Global.INSTANCE
            EmojiPickerNavigator.launchBottomSheet(dialog.parentFragmentManager, listener, context, null)
        }
    }

    /** Draws the custom emoji codes in [text] as their images, keeping the codes as the text underneath. */
    private fun showEmojis(text: Editable, textSize: Float) {
        for (span in text.getSpans(0, text.length, EmojiSpan::class.java)) text.removeSpan(span)
        val size = Math.round(textSize * 1.2f).coerceAtLeast(1)
        val matcher = EMOJI.matcher(text)
        while (matcher.find()) {
            val emojiId = matcher.group(2)?.toLongOrNull() ?: continue
            val span = emojiSpan(emojiId, size) ?: continue
            text.setSpan(span, matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private class EmojiSpan(drawable: Drawable) : ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM)

    /** An image span for the emoji, or null while its image loads. */
    private fun emojiSpan(emojiId: Long, size: Int): EmojiSpan? {
        val bitmap = emojiImage(emojiId) ?: return null
        val drawable = BitmapDrawable(Utils.appContext.resources, bitmap)
        val width = if (bitmap.height > 0) size * bitmap.width / bitmap.height else size
        drawable.setBounds(0, 0, width, size)
        return EmojiSpan(drawable)
    }

    private fun save(channelId: Long, text: String) {
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest("/channels/$channelId/voice-status", "PUT").use { request ->
                    request.setHeader("Content-Type", "application/json")
                    request.executeWithBody(JSONObject().put("status", text).toString()).use { response ->
                        if (response.ok()) {
                            setStatus(channelId, text)
                            refresh()
                        } else {
                            val body = try {
                                request.conn.errorStream?.bufferedReader()?.use { it.readText() }
                            } catch (_: Throwable) {
                                null
                            }
                            logger.warn("Setting the voice channel status failed (HTTP ${response.statusCode}): $body")
                            val message = try {
                                JSONObject(body ?: "").optString("message")
                            } catch (_: Throwable) {
                                ""
                            }
                            Utils.showToast(
                                if (message.isNotEmpty()) message else "Couldn't set the channel status",
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error("Setting the voice channel status failed", e)
                Utils.showToast("Couldn't set the channel status")
            }
        }
    }

    // endregion

    private fun id(name: String) = Utils.getResId(name, "id")

    private companion object {
        const val REQUEST_CHANNEL_INFO = 43
        const val SET_VOICE_CHANNEL_STATUS = 1L shl 48
        const val ADMINISTRATOR = 8L
        const val MAX_LENGTH = 500
        val EMOJI: Pattern = Pattern.compile("<a?:([^:>]+):([0-9]+)>")
    }
}
