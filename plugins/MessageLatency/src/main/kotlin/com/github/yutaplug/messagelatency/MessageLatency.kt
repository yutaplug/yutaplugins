package com.github.yutaplug.messagelatency

import android.content.Context
import android.text.Annotation
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.search.results.WidgetSearchResults
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.WeakHashMap
import kotlin.math.abs

/** Port of Vencord's MessageLatency: flags messages whose nonce and ID timestamps are far apart. */
@AliucordPlugin
class MessageLatency : Plugin() {
    private class Latency(val icon: String, val text: String)

    private var timestampId = 0
    private var headerId = 0

    // Message text view -> message being bound, between processMessageText and setDraweeSpanStringBuilder.
    private val pending = WeakHashMap<SimpleDraweeSpanTextView, Message>()

    /**
     * Message ID -> nonce for every message received live, including in channels that aren't open.
     * Discord drops those messages and reloads them from history without a nonce when the channel is opened.
     */
    private val nonces = object : LinkedHashMap<Long, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > MAX_REMEMBERED
    }

    val thresholdSeconds get() = settings.getInt(KEY_THRESHOLD, 2)
    val detectKotlin get() = settings.getBool(KEY_DETECT_KOTLIN, true)
    val showMillis get() = settings.getBool(KEY_SHOW_MILLIS, false)
    val ignoreSelf get() = settings.getBool(KEY_IGNORE_SELF, false)

    init {
        settingsTab = SettingsTab(MessageLatencySettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        timestampId = Utils.getResId("chat_list_adapter_item_text_timestamp", "id")
        headerId = Utils.getResId("chat_list_adapter_item_text_header", "id")
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, ChatListEntry::class.java),
            Hook { frame ->
                val item = frame.thisObject as WidgetChatListAdapterItemMessage
                val timestamp = item.itemView.findViewById<View>(timestampId) as? TextView ?: return@Hook
                // Rows are recycled, so always start from a clean timestamp.
                clear(timestamp)
                // IRC moves the timestamp into a narrow column; the icon goes before the author there instead.
                if (ircLayout(item)) return@Hook
                // Only the first message of a group has a header, as with Vencord.
                if (timestamp.visibility != View.VISIBLE) return@Hook
                if ((timestamp.parent as? View)?.id != headerId) return@Hook
                val message = (frame.args[1] as? MessageEntry)?.message ?: return@Hook
                val latency = latency(message) ?: return@Hook
                val icon = ContextCompat.getDrawable(timestamp.context, Utils.getResId(latency.icon, "drawable"))
                timestamp.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
                timestamp.compoundDrawablePadding = (4 * timestamp.resources.displayMetrics.density).toInt()
                // Mobile has no hover tooltips, so tapping the time shows the details.
                timestamp.setOnClickListener { Utils.showToast(latency.text, true) }
            },
        )
        patchInline()
        patcher.patch(
            StoreStream::class.java,
            "handleMessageCreate",
            arrayOf(com.discord.api.message.Message::class.java),
            PreHook { frame ->
                val message = frame.args[0] as? com.discord.api.message.Message ?: return@PreHook
                val nonce = message.v() ?: return@PreHook
                synchronized(nonces) { nonces[message.o()] = nonce }
            },
        )
    }

    /**
     * With IRC, puts the icon before the inline author, like Vencord does in desktop compact mode.
     * The icon is marked with IRC's leading Annotation so the author is inserted after it, whichever
     * plugin edits the text first.
     */
    private fun patchInline() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            PreHook { frame ->
                val text = frame.args[0] as SimpleDraweeSpanTextView
                if (ircLayout(frame.thisObject)) {
                    pending[text] = (frame.args[1] as MessageEntry).message
                } else {
                    pending.remove(text)
                }
            },
        )
        patcher.patch(
            SimpleDraweeSpanTextView::class.java,
            "setDraweeSpanStringBuilder",
            arrayOf(DraweeSpanStringBuilder::class.java),
            PreHook { frame ->
                val view = frame.thisObject as SimpleDraweeSpanTextView
                val message = pending.remove(view) ?: return@PreHook
                val builder = frame.args[0] as? DraweeSpanStringBuilder ?: return@PreHook
                val latency = latency(message) ?: return@PreHook
                prependIcon(builder, view, latency)
            },
        )
    }

    private fun prependIcon(builder: SpannableStringBuilder, view: TextView, latency: Latency) {
        val icon = ContextCompat.getDrawable(view.context, Utils.getResId(latency.icon, "drawable"))?.mutate() ?: return
        // Size the bars to the text, keeping the drawable's 11:12 aspect ratio.
        val height = (view.textSize * 0.8f).toInt()
        icon.setBounds(0, 0, height * 11 / 12, height)
        builder.insert(0, "￼ ")
        builder.setSpan(ImageSpan(icon, DynamicDrawableSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ClickableSpan(null, false, null) {
                Utils.showToast(latency.text, true)
                kotlin.Unit.a
            },
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(Annotation(IRC_LEADING_KEY, "messageLatency"), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Whether IRC lays out this row. IRC leaves search results in Discord's regular layout. */
    private fun ircLayout(holder: Any): Boolean {
        if (!PluginManager.plugins.containsKey("IRC") || !PluginManager.isPluginEnabled("IRC")) return false
        val adapter = runCatching { adapterField.get(holder) }.getOrNull() as? WidgetChatListAdapter ?: return true
        return adapter.eventHandler !is WidgetSearchResults.SearchResultAdapterEventHandler
    }

    private val adapterField by lazy {
        MGRecyclerViewHolder::class.java.getDeclaredField("adapter").apply { isAccessible = true }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        pending.clear()
        synchronized(nonces) { nonces.clear() }
    }

    private fun clear(timestamp: TextView) {
        timestamp.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        timestamp.setOnClickListener(null)
        timestamp.isClickable = false
    }

    private fun latency(message: Message): Latency? {
        // Only messages received live from the gateway carry a nonce; history reloads lose it, so fall back
        // to the one remembered when the message arrived.
        val raw = message.nonce ?: synchronized(nonces) { nonces[message.id] }
        val nonce = raw?.toLongOrNull() ?: return null
        if (nonce <= 0 || nonce == message.id || message.isLocal) return null
        val author = message.author ?: return null
        if (author.e() == true) return null
        if (ignoreSelf && author.id == StoreStream.getUsers().me.id) return null

        var delta = timestampOf(message.id) - timestampOf(nonce)
        // Old Discord Android clients send nonces about 17 days ahead of the real time. Aliucord 2.11+
        // replaces NonceGenerator.computeNonce without the offset, so only older Aliucord and stock clients match.
        val kotlin = detectKotlin && -delta >= DISCORD_KT_DELAY - DAY_MS
        if (kotlin) delta += DISCORD_KT_DELAY
        val threshold = thresholdSeconds * 1000L
        val delayed = abs(delta) >= threshold
        if (!delayed && !kotlin) return null

        val icon = when {
            kotlin -> "ic_voice_quality_fine"
            delta >= 2 * MINUTE_MS || delta < 0 -> "ic_voice_quality_unknown"
            delta >= threshold * 2 -> "ic_voice_quality_bad"
            else -> "ic_voice_quality_average"
        }
        val lines = ArrayList<String>()
        if (delayed) {
            lines += if (delta < 0) {
                "This user's clock is ${format(-delta)} ahead."
            } else {
                "This message was sent with a delay of ${format(delta)}."
            }
        }
        if (kotlin) lines += "User is suspected to be on an old Discord Android client (or Aliucord before 2.11)."
        return Latency(icon, lines.joinToString("\n"))
    }

    /** Formats like Vencord's stringDelta: "1 minute, 2 seconds and 300 milliseconds". */
    private fun format(ms: Long): String {
        val units = arrayOf(
            "day" to ms / DAY_MS,
            "hour" to ms / HOUR_MS % 24,
            "minute" to ms / MINUTE_MS % 60,
            "second" to ms / 1000 % 60,
            "millisecond" to if (showMillis) ms % 1000 else 0L,
        )
        val parts = ArrayList<String>()
        for ((unit, value) in units) {
            if (value > 0) parts += "$value $unit${if (value == 1L) "" else "s"}"
        }
        return when (parts.size) {
            0 -> "0 seconds"
            1 -> parts[0]
            else -> parts.subList(0, parts.size - 1).joinToString(", ") + " and " + parts.last()
        }
    }

    private fun timestampOf(snowflake: Long) = (snowflake ushr 22) + DISCORD_EPOCH

    companion object {
        const val KEY_THRESHOLD = "latency"
        const val KEY_DETECT_KOTLIN = "detectDiscordKotlin"
        const val KEY_SHOW_MILLIS = "showMillis"
        const val KEY_IGNORE_SELF = "ignoreSelf"

        // Must match IRC's InlineAuthorText.LEADING_KEY.
        private const val IRC_LEADING_KEY = "ircLeading"
        private const val DISCORD_EPOCH = 1420070400000L
        private const val MAX_REMEMBERED = 5000
        private const val DISCORD_KT_DELAY = 1471228928L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}
