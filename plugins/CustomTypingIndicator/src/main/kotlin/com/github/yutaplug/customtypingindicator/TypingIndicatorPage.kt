package com.github.yutaplug.customtypingindicator

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.wrappers.users.globalName
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.views.typing.TypingDots
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.discord.widgets.chat.list.actions.EmojiItem
import com.discord.widgets.chat.list.actions.WidgetChatListActionsEmojisAdapter
import com.lytefast.flexinput.R
import org.json.JSONObject
import b.a.k.b as FormatUtils

/** Edits your own custom typing indicator, stored on your account as `typing_indicator_style`. */
class TypingIndicatorPage : SettingsPage() {
    private val logger = Logger("CustomTypingIndicator")
    private val emojis = mutableListOf<TypingEmoji>()
    private var shown = listOf<Emoji>()
    private var animation = TypingStyle.ANIMATION_WAVE
    private var suggestion = 0
    private var loading = true
    private var saving = false

    // Aliucord attaches the proxy fragment, so this page's isAdded stays false; track the view ourselves.
    private var alive = false

    /** Discord's premium_type for your account: 0 none, 1 Nitro Classic, 2 Nitro, 3 Nitro Basic. Null until loaded. */
    private var premiumType: Int? = null
    private val hasNitro get() = premiumType == 1 || premiumType == 2

    private lateinit var bar: RelativeLayout
    private lateinit var dots: TypingDots
    private lateinit var barText: TextView
    private lateinit var adapter: WidgetChatListActionsEmojisAdapter
    private lateinit var status: TextView
    private lateinit var phraseValue: TextView
    private lateinit var animationValue: TextView
    private lateinit var saveRow: View

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Typing Indicator")
        setActionBarSubtitle("CustomTypingIndicator")
        val context = requireContext()
        alive = true

        add(
            note(
                "Show up to three emojis and a phrase to everyone when you're the only one typing. " +
                    "Discord requires Nitro (not Nitro Basic) for this, and it's still in beta.",
            ),
        )

        add(header("Preview"))
        addPreview()

        add(header("Emojis"))
        // Detach Discord's own reaction row so emojis and the add button render exactly as in the actions sheet.
        val template = LayoutInflater.from(context)
            .inflate(Utils.getResId("widget_chat_list_actions", "layout"), null, false)
        val row = template.findViewById<RecyclerView>(Utils.getResId("dialog_chat_actions_add_reaction_emojis_list", "id"))
        (row.parent as ViewGroup).removeView(row)
        adapter = MGRecyclerAdapter.configure(WidgetChatListActionsEmojisAdapter(row))
        adapter.onClickEmoji = { emoji ->
            val index = shown.indexOf(emoji)
            if (index >= 0 && index < emojis.size) {
                emojis.removeAt(index)
                render()
            }
        }
        adapter.onClickMoreEmojis = ::pickEmoji
        add(row)
        status = note("")
        add(status)

        add(header("Style"))
        phraseValue = row("Text") { pickPhrase() }
        animationValue = row("Animation") { pickAnimation() }

        add(View(context, null, 0, R.i.UiKit_Settings_Divider).apply { layoutParams = LinearLayout.LayoutParams(-1, 1) })
        saveRow = row("Save") { save(false) }.parent as View
        row("Remove custom typing indicator", ColorCompat.getColor(context, R.c.status_red_500)) { confirmRemove() }

        render()
        load()
    }

    override fun onDestroyView() {
        alive = false
        super.onDestroyView()
    }

    private fun addPreview() {
        val overlay = LayoutInflater.from(requireContext())
            .inflate(Utils.getResId("widget_chat_overlay", "layout"), null, false)
        bar = overlay.findViewById(Utils.getResId("chat_overlay_typing", "id"))
        val height = bar.layoutParams.height
        (bar.parent as ViewGroup).removeView(bar)
        bar.visibility = View.VISIBLE
        dots = bar.findViewById(Utils.getResId("chat_overlay_typing_dots", "id"))
        barText = bar.findViewById(Utils.getResId("chat_typing_users_typing", "id"))
        bar.findViewById<View>(Utils.getResId("chat_typing_users_slowmode", "id"))?.visibility = View.GONE
        bar.findViewById<View>(Utils.getResId("chat_typing_users_slowmode_icon", "id"))?.visibility = View.GONE
        linearLayout.addView(
            bar,
            LinearLayout.LayoutParams(-1, height).apply {
                setMargins(DimenUtils.dpToPx(16), DimenUtils.dpToPx(4), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
    }

    private fun current() = TypingStyle(emojis.toList(), animation, suggestion)

    private fun render() {
        if (!alive) return
        val context = requireContext()
        shown = emojis.map(::toEmoji)
        val items = mutableListOf<EmojiItem>()
        shown.forEach { items += EmojiItem.EmojiData(it) }
        // Discord's setData(emojis, count) drops the add button when the list is empty, so build the items directly.
        if (emojis.size < TypingStyle.MAX_EMOJIS) items += EmojiItem.MoreEmoji.INSTANCE
        adapter.setData(items)
        status.text = when {
            loading -> "Loading your current typing indicator…"
            saving -> "Saving…"
            premiumType != null && !hasNitro ->
                "You need Nitro to set a custom typing indicator. You can still try styles in the preview."
            emojis.isEmpty() -> "Tap + to add up to ${TypingStyle.MAX_EMOJIS} emojis. Without emojis, the dots are kept."
            else -> "Tap an emoji to remove it."
        }
        phraseValue.text = TypingStyle.phrase(suggestion)
        animationValue.text = TypingStyle.animationLabel(animation)
        saveRow.alpha = if (premiumType != null && !hasNitro) 0.5f else 1f

        val me = StoreStream.getUsers().me
        val name = FormatUtils.l("!!${me.globalName ?: me.username}!!", arrayOf(), null, 2)
        barText.text = FormatUtils.i(context.resources, Utils.getResId("one_user_typing", "string"), arrayOf(name), null, 4)
        dots.visibility = View.VISIBLE
        dots.a(false)
        CustomTypingIndicator.render(bar, dots, barText, current(), name)
    }

    private fun toEmoji(emoji: TypingEmoji): Emoji {
        val id = emoji.customId
        if (id != null) {
            return ModelEmojiCustom(id, "emoji", emptyList(), true, false, 0, "emoji", emoji.animated, true, true, 0L)
        }
        val surrogates = emoji.unicode ?: ""
        return runCatching { StoreStream.getEmojis().unicodeEmojiSurrogateMap[surrogates] }.getOrNull()
            ?: ModelEmojiUnicode(listOf("emoji"), surrogates, false, false, false, false, emptyList())
    }

    private fun pickEmoji() {
        EmojiPickerNavigator.launchBottomSheet(
            Utils.appActivity.supportFragmentManager,
            object : EmojiPickerListener {
                override fun onEmojiPicked(emoji: Emoji) {
                    val picked = when (emoji) {
                        is ModelEmojiCustom -> TypingEmoji(null, emoji.id, emoji.isAnimated)
                        is ModelEmojiUnicode -> TypingEmoji(emoji.surrogates, null, false)
                        else -> null
                    } ?: return
                    if (emojis.size < TypingStyle.MAX_EMOJIS) emojis += picked
                    render()
                }
            },
            EmojiPickerContextType.Global.INSTANCE,
        ) {}
    }

    private fun pickPhrase() {
        SelectDialog().apply {
            title = "Text"
            items = TypingStyle.PHRASES
            onResultListener = { which ->
                suggestion = which
                render()
            }
        }.show(Utils.appActivity.supportFragmentManager, "CustomTypingIndicatorText")
    }

    private fun pickAnimation() {
        SelectDialog().apply {
            title = "Animation"
            items = TypingStyle.ANIMATIONS.map(TypingStyle::animationLabel).toTypedArray()
            onResultListener = { which ->
                animation = TypingStyle.ANIMATIONS[which]
                render()
            }
        }.show(Utils.appActivity.supportFragmentManager, "CustomTypingIndicatorAnimation")
    }

    private fun apply(style: TypingStyle?) {
        emojis.clear()
        emojis += style?.emojis.orEmpty()
        animation = style?.animation?.takeIf { it in TypingStyle.ANIMATIONS } ?: TypingStyle.ANIMATION_WAVE
        suggestion = style?.suggestion?.takeIf { it >= 0 && it < TypingStyle.PHRASES.size } ?: 0
    }

    private fun load() {
        Utils.threadPool.execute {
            val result = runCatching {
                Http.Request.newDiscordRNRequest("/users/@me").use { request ->
                    request.setRequestTimeout(REQUEST_TIMEOUT)
                    request.execute().use { response ->
                        check(response.ok()) { "HTTP ${response.statusCode}" }
                        val user = JSONObject(response.text())
                        user.optInt("premium_type", 0) to TypingStyle.fromParent(user)
                    }
                }
            }
            Utils.mainThread.post {
                loading = false
                result.onSuccess { (premium, style) ->
                    premiumType = premium
                    apply(style)
                }.onFailure {
                    logger.error("Failed to load your typing indicator", it)
                    Utils.showToast("Could not load your current typing indicator")
                }
                render()
            }
        }
    }

    private fun confirmRemove() {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Remove custom typing indicator?")
            .setDescription("Everyone will see the normal typing dots for you again.")
            .setIsDangerous(true)
            .setOnOkListener {
                dialog.dismiss()
                save(true)
            }.show(Utils.appActivity.supportFragmentManager, "CustomTypingIndicatorRemove")
    }

    private fun save(remove: Boolean) {
        if (saving || loading) return
        val style = if (remove) null else current()
        if (style != null && style.emojis.isEmpty() && style.suggestion == 0) {
            showError("Pick at least one emoji or a phrase first.")
            return
        }
        if (style != null && premiumType != null && !hasNitro) {
            showError(
                "Discord only lets Nitro members set a custom typing indicator, and this account doesn't have Nitro. " +
                    "Nitro Basic doesn't include it either.",
                "Nitro required",
            )
            return
        }
        saving = true
        render()
        Utils.threadPool.execute {
            val error = try {
                patch(JSONObject().put("typing_indicator_style", style?.toJson() ?: JSONObject.NULL))
                null
            } catch (e: Throwable) {
                logger.error("Failed to save your typing indicator", e)
                e.message ?: "Could not save your typing indicator."
            }
            Utils.mainThread.post {
                saving = false
                if (error == null) {
                    if (remove) apply(null)
                    Utils.showToast(if (remove) "Custom typing indicator removed" else "Typing indicator saved")
                } else if (alive) {
                    showError(error)
                }
                render()
            }
        }
    }

    private fun patch(body: JSONObject) {
        Http.Request.newDiscordRNRequest("/users/@me", "PATCH").use { request ->
            request.setRequestTimeout(REQUEST_TIMEOUT).setHeader("Content-Type", "application/json")
            request.executeWithBody(body.toString()).use { response ->
                if (response.ok()) return
                // Response.text() refuses non-2xx responses, so read Discord's error body directly.
                val text = try {
                    request.conn.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Throwable) {
                    null
                }
                logger.warn("PATCH /users/@me failed with HTTP ${response.statusCode}: $text")
                throw IllegalStateException(errorMessage(text) ?: "HTTP ${response.statusCode}")
            }
        }
    }

    private fun errorMessage(text: String?): String? {
        val body = try {
            JSONObject(text ?: return null)
        } catch (_: Throwable) {
            return null
        }
        return findFieldError(body.optJSONObject("errors")) ?: body.optString("message").takeIf { it.isNotEmpty() }
    }

    private fun findFieldError(node: JSONObject?): String? {
        if (node == null) return null
        node.optJSONArray("_errors")?.optJSONObject(0)?.optString("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        val keys = node.keys()
        while (keys.hasNext()) findFieldError(node.optJSONObject(keys.next()))?.let { return it }
        return null
    }

    private fun showError(message: String, title: String = "Couldn't save typing indicator") {
        val dialog = ConfirmDialog()
        dialog
            .setTitle(title)
            .setDescription(message)
            .setOnOkListener { dialog.dismiss() }
            .show(Utils.appActivity.supportFragmentManager, "CustomTypingIndicatorError")
    }

    private fun add(view: View) {
        linearLayout.addView(view, view.layoutParams ?: LinearLayout.LayoutParams(-1, -2))
    }

    private fun header(title: String) = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
        text = title
    }

    private fun note(value: CharSequence) = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
        text = value
        background = null
        setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
    }

    /** A settings row like Discord's Account settings: a label with an optional value on the right. */
    private fun row(title: String, color: Int? = null, click: () -> Unit): TextView {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            val value = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                setBackgroundResource(value.resourceId)
            }
            setOnClickListener { click() }
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = title
                background = null
                if (color != null) setTextColor(color)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val trailing = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right)
        row.addView(trailing, LinearLayout.LayoutParams(-2, -2))
        add(row)
        return trailing
    }

    private companion object {
        const val REQUEST_TIMEOUT = 15_000
    }
}
