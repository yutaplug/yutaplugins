package com.github.yutaplug.customtypingindicator

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.LinearLayoutCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.user.TypingUser
import com.discord.databinding.WidgetChatOverlayBinding
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserTyping
import com.discord.utilities.color.ColorCompat
import com.discord.views.typing.TypingDots
import com.discord.widgets.chat.overlay.ChatTypingModel
import com.discord.widgets.chat.overlay.WidgetChatOverlay
import com.discord.widgets.settings.WidgetSettings
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import org.json.JSONObject
import java.io.StringReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import b.a.k.b as FormatUtils

@AliucordPlugin
class CustomTypingIndicator : Plugin() {
    /** The style each user sent with their latest TYPING_START. */
    private val styles = ConcurrentHashMap<Long, TypingStyle>()
    private val jsonGson = Gson()
    private val bars = mutableListOf<WeakReference<WidgetChatOverlayBinding>>()
    private val rows = WeakHashMap<WidgetSettings, TextView>()
    private val typingSnapshot by lazy {
        StoreUserTyping::class.java.getDeclaredField("typingUsersSnapshot").apply { isAccessible = true }
    }

    init {
        settingsTab = SettingsTab(TypingIndicatorPage::class.java, SettingsTab.Type.PAGE)
    }

    override fun start(context: Context) {
        patchGateway()
        patchTypingBar()
        patchUserSettings()
    }

    private fun patchGateway() {
        // TypingUser has no field for the style, so read the raw event before Discord's adapter drops it.
        patcher.patch(
            InboundGatewayGsonParser::class.java,
            "fromJson",
            arrayOf(JsonReader::class.java, Class::class.java),
            PreHook { frame ->
                if (frame.args[1] != TypingUser::class.java) return@PreHook
                val json = jsonGson.d<JsonElement>(frame.args[0] as JsonReader, JsonElement::class.java).toString()
                val typing = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
                    .d<TypingUser>(JsonReader(StringReader(json)), TypingUser::class.java)
                try {
                    val style = TypingStyle.fromParent(JSONObject(json))
                    if (typing != null) {
                        if (style != null) styles[typing.d()] = style else styles.remove(typing.d())
                    }
                } catch (error: Throwable) {
                    logger.error("Failed to read typing indicator style", error)
                }
                frame.result = typing
            },
        )
    }

    private fun patchTypingBar() {
        val holder = WidgetChatOverlay.TypingIndicatorViewHolder::class.java
        val bindingField = holder.getDeclaredField("binding").apply { isAccessible = true }
        patcher.patch(
            holder,
            "configureTyping",
            arrayOf(ChatTypingModel.Typing::class.java),
            Hook { frame ->
                val binding = bindingField.get(frame.thisObject) as WidgetChatOverlayBinding
                val typing = frame.args[0] as ChatTypingModel.Typing
                if (bars.none { it.get() === binding }) {
                    bars.removeAll { it.get() == null }
                    bars += WeakReference(binding)
                }
                val name = typing.typingUsers.singleOrNull()
                try {
                    render(binding.c, binding.d, binding.g, name?.let { soleTyperStyle() }, name)
                } catch (error: Throwable) {
                    logger.error("Failed to show custom typing indicator", error)
                }
            },
        )
    }

    /** Discord only shows a custom indicator when exactly one person is typing in the channel. */
    private fun soleTyperStyle(): TypingStyle? {
        @Suppress("UNCHECKED_CAST")
        val snapshot = typingSnapshot.get(StoreStream.getUsersTyping()) as? Map<Long, Set<Long>> ?: return null
        val userId = snapshot[StoreStream.getChannelsSelected().id]?.singleOrNull() ?: return null
        return styles[userId]
    }

    private fun patchUserSettings() {
        patcher.patch(WidgetSettings::class.java, "onViewBound", arrayOf(View::class.java), Hook { frame ->
            insertRow(frame.thisObject as WidgetSettings)
        })
        patcher.patch(WidgetSettings::class.java, "onViewBoundOrOnResume", emptyArray(), Hook { frame ->
            insertRow(frame.thisObject as WidgetSettings)
        })
    }

    private fun insertRow(settings: WidgetSettings) {
        val page = settings.view ?: return
        val anchor = page.findViewById<TextView>(Utils.getResId("profile", "id")) ?: return
        val parent = anchor.parent as? LinearLayoutCompat ?: return
        if (parent.findViewWithTag<View>(ROW_TAG) != null) return
        val row = TextView(anchor.context).apply {
            tag = ROW_TAG
            text = "Typing Indicator"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            setTextColor(anchor.textColors)
            typeface = anchor.typeface
            gravity = anchor.gravity
            includeFontPadding = anchor.includeFontPadding
            minHeight = anchor.minimumHeight
            setPaddingRelative(anchor.paddingStart, anchor.paddingTop, anchor.paddingEnd, anchor.paddingBottom)
            compoundDrawablePadding = anchor.compoundDrawablePadding
            background = anchor.background?.constantState?.newDrawable(anchor.resources)?.mutate()
            isClickable = true
            isFocusable = true
            val iconId = Utils.getResId("ic_emoji_24dp", "drawable")
            val icon = if (iconId != 0) AppCompatResources.getDrawable(context, iconId)?.mutate() else null
            val tintAttr = Utils.getResId("colorInteractiveNormal", "attr")
            if (tintAttr != 0) icon?.setTintList(ColorStateList.valueOf(ColorCompat.getThemedColor(context, tintAttr)))
            val iconSize = (24 * resources.displayMetrics.density + 0.5f).toInt()
            icon?.setBounds(0, 0, iconSize, iconSize)
            setCompoundDrawablesRelative(icon, null, null, null)
            setOnClickListener {
                if (settings.isAdded) Utils.openPageWithProxy(context, TypingIndicatorPage())
            }
        }
        parent.addView(row, parent.indexOfChild(anchor) + 1, LinearLayoutCompat.LayoutParams(anchor.layoutParams))
        rows[settings] = row
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        styles.clear()
        rows.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rows.clear()
        for (ref in bars) {
            val binding = ref.get() ?: continue
            val emojis = binding.c.findViewWithTag<EmojiDots>(EMOJI_TAG) ?: continue
            emojis.stop()
            binding.c.removeView(emojis)
            setEndOf(binding.g, binding.d.id)
            if (binding.c.visibility == View.VISIBLE && binding.g.visibility == View.VISIBLE) {
                binding.d.visibility = View.VISIBLE
                binding.d.a(false)
            }
        }
        bars.clear()
    }

    companion object {
        private const val ROW_TAG = "custom_typing_indicator_row"
        private const val EMOJI_TAG = "custom_typing_indicator_emojis"

        /**
         * Swaps the typing dots for [style]'s emojis and its phrase. Shared by the chat's typing bar and the
         * editor's preview, which is a detached copy of the same layout.
         */
        fun render(bar: RelativeLayout, dots: TypingDots, text: TextView, style: TypingStyle?, name: CharSequence?) {
            var emojis = bar.findViewWithTag<EmojiDots>(EMOJI_TAG)
            if (style != null && name != null && style.emojis.isNotEmpty()) {
                if (emojis == null) {
                    emojis = EmojiDots(bar.context).apply {
                        id = View.generateViewId()
                        tag = EMOJI_TAG
                    }
                    bar.addView(
                        emojis,
                        RelativeLayout.LayoutParams(-2, -1).apply {
                            addRule(RelativeLayout.ALIGN_PARENT_START)
                            addRule(RelativeLayout.CENTER_VERTICAL)
                        },
                    )
                }
                emojis.visibility = View.VISIBLE
                emojis.bind(style)
                dots.c()
                dots.visibility = View.GONE
                setEndOf(text, emojis.id)
            } else if (emojis != null) {
                emojis.stop()
                emojis.visibility = View.GONE
                setEndOf(text, dots.id)
            }
            if (style != null && name != null && style.suggestion != 0) {
                text.text = phraseText(name, style.suggestion)
            }
        }

        /** Formats the phrase like 126.21's own `one_user_typing` string, `**!!{a}!!** is typing…`. */
        fun phraseText(name: CharSequence, suggestion: Int): CharSequence =
            FormatUtils.l("**!!{a}!!** ${TypingStyle.phrase(suggestion)}", arrayOf(name), null, 2)

        private fun setEndOf(text: TextView, anchor: Int) {
            val params = text.layoutParams as? RelativeLayout.LayoutParams ?: return
            if (params.getRule(RelativeLayout.END_OF) == anchor) return
            params.addRule(RelativeLayout.END_OF, anchor)
            text.layoutParams = params
        }
    }
}
