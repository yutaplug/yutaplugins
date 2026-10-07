package com.github.yutaplug.translatemessages

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AliucordPlugin
class TranslateMessages : Plugin() {
    private class Translation(
        val original: String,
        val target: String,
        val text: String,
        val source: String?,
        @Volatile var shown: Boolean,
    )

    private val translations = ConcurrentHashMap<Long, Translation>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()

    // Auto-translate failures are not retried on every rebind; manual requests still retry.
    private val failed = ConcurrentHashMap.newKeySet<Long>()
    private var executor: ExecutorService? = null
    private var adapterRef: WeakReference<WidgetChatListAdapter>? = null
    private val buttonId = View.generateViewId()

    // Set while Discord renders a message whose translation is shown.
    private val rendering = ThreadLocal<Translation?>()

    init {
        settingsTab = SettingsTab(TranslateMessagesSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    var targetLanguage: String
        get() = settings.getString(KEY_LANGUAGE, null) ?: Languages.deviceDefault()
        set(value) {
            settings.setString(KEY_LANGUAGE, value)
            translations.clear()
            failed.clear()
            refreshChat()
        }

    var autoTranslate: Boolean
        get() = settings.getBool(KEY_AUTO, false)
        set(value) {
            settings.setBool(KEY_AUTO, value)
            refreshChat()
        }

    var autoTranslateOwn: Boolean
        get() = settings.getBool(KEY_AUTO_OWN, false)
        set(value) {
            settings.setBool(KEY_AUTO_OWN, value)
            refreshChat()
        }

    /** One of [SERVICE_GOOGLE], [SERVICE_DEEPL] or [SERVICE_LIBRE]. */
    var serviceId: String
        // Before the service picker existed, a filled-in API URL meant LibreTranslate.
        get() = settings.getString(KEY_SERVICE, null) ?: if (apiUrl.isNotEmpty()) SERVICE_LIBRE else SERVICE_GOOGLE
        set(value) {
            settings.setString(KEY_SERVICE, value)
            serviceChanged()
        }

    var apiUrl: String
        get() = settings.getString(KEY_API_URL, "").trim()
        set(value) {
            settings.setString(KEY_API_URL, value.trim())
            serviceChanged()
        }

    var apiKey: String
        get() = settings.getString(KEY_API_KEY, "").trim()
        set(value) {
            settings.setString(KEY_API_KEY, value.trim())
            failed.clear()
        }

    var deeplKey: String
        get() = settings.getString(KEY_DEEPL_KEY, "").trim()
        set(value) {
            settings.setString(KEY_DEEPL_KEY, value.trim())
            failed.clear()
        }

    private val service: Translator.Service
        get() = when (serviceId) {
            SERVICE_DEEPL -> Translator.Service.DeepL(deeplKey)
            SERVICE_LIBRE -> Translator.Service.Libre(apiUrl, apiKey)
            else -> Translator.Service.Google
        }

    private fun serviceChanged() {
        translations.clear()
        failed.clear()
        refreshChat()
    }

    override fun start(context: Context) {
        executor = Executors.newFixedThreadPool(2)
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "processMessageText",
                SimpleDraweeSpanTextView::class.java,
                MessageEntry::class.java,
            ),
            PreHook { call ->
                val item = call.thisObject as WidgetChatListAdapterItemMessage
                adapterRef = WeakReference(item.adapter as WidgetChatListAdapter)
                val message = (call.args[1] as MessageEntry).message
                val content = message.content.orEmpty()
                val translation = translations[message.id]?.takeIf { it.original == content && it.target == targetLanguage }
                if (translation != null) {
                    rendering.set(translation.takeIf { it.shown })
                } else {
                    rendering.set(null)
                    if (autoTranslate && message.id !in failed && (autoTranslateOwn || !isOwn(message)) && shouldTranslate(content)) {
                        requestTranslation(message.id, content, show = true, manual = false)
                    }
                }
            },
        )
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "processMessageText",
                SimpleDraweeSpanTextView::class.java,
                MessageEntry::class.java,
            ),
            Hook { rendering.remove() },
        )
        patcher.patch(
            Message::class.java.getDeclaredMethod("getContent"),
            Hook { call ->
                val translation = rendering.get() ?: return@Hook
                if (call.result == translation.original) call.result = translation.text
            },
        )
        patcher.patch(
            SimpleDraweeSpanTextView::class.java.getDeclaredMethod(
                "setDraweeSpanStringBuilder",
                DraweeSpanStringBuilder::class.java,
            ),
            PreHook { call ->
                val translation = rendering.get() ?: return@PreHook
                val builder = call.args[0] as DraweeSpanStringBuilder
                val view = call.thisObject as View
                // Styled like Discord's "(edited)" tag.
                val start = builder.length
                val source = translation.source?.let { "${Languages.displayCode(it)} → " }.orEmpty()
                builder.append(" (translated $source${Languages.displayCode(translation.target)})")
                builder.setSpan(RelativeSizeSpan(0.75f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(
                    ForegroundColorSpan(ColorCompat.getThemedColor(view.context, R.b.colorTextMuted)),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            },
        )
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { call -> configureMenu(call.thisObject as WidgetChatListActions, call.args[0] as WidgetChatListActions.Model) },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        executor?.shutdownNow()
        executor = null
        translations.clear()
        pending.clear()
        failed.clear()
        refreshChat()
        adapterRef = null
    }

    private fun configureMenu(actions: WidgetChatListActions, model: WidgetChatListActions.Model) {
        val layout = (actions.view as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val message = model.message
        val content = message.content.orEmpty()
        val existing = layout.findViewById<TextView>(buttonId)
        if (!shouldTranslate(content)) {
            existing?.visibility = View.GONE
            return
        }
        val button = existing ?: createButton(layout)
        button.visibility = View.VISIBLE
        val translation = translations[message.id]?.takeIf { it.original == content && it.target == targetLanguage }
        val showing = translation?.shown == true
        button.text = if (showing) "Show original" else "Translate"
        button.setOnClickListener {
            actions.dismiss()
            when {
                showing -> {
                    translation!!.shown = false
                    refreshMessage(message.id)
                }

                translation != null && !Languages.same(translation.source, translation.target) -> {
                    translation.shown = true
                    refreshMessage(message.id)
                }

                else -> {
                    Utils.showToast("Translating…")
                    requestTranslation(message.id, content, show = true, manual = true)
                }
            }
        }
    }

    private fun createButton(layout: LinearLayout): TextView {
        val copy = layout.findViewById<TextView>(Utils.getResId("dialog_chat_actions_copy", "id"))
        return TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            id = buttonId
            val icon = ContextCompat.getDrawable(context, Utils.getResId("ic_locale_24dp", "drawable"))?.mutate()
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            if (copy != null) {
                setTextColor(copy.textColors)
                TextViewCompat.setCompoundDrawableTintList(this, TextViewCompat.getCompoundDrawableTintList(copy))
            }
            val index = layout.indexOfChild(copy)
            layout.addView(this, if (copy != null && index >= 0) index + 1 else layout.childCount)
        }
    }

    private fun requestTranslation(messageId: Long, content: String, show: Boolean, manual: Boolean) {
        val pool = executor ?: return
        if (!pending.add(messageId)) return
        val target = targetLanguage
        val service = service
        pool.execute {
            try {
                val result = Translator.translate(content, target, service)
                val alreadyTarget = Languages.same(result.sourceLanguage, target) || result.text == content
                translations[messageId] = Translation(content, target, result.text, result.sourceLanguage, show && !alreadyTarget)
                Utils.mainThread.post {
                    if (alreadyTarget) {
                        if (manual) Utils.showToast("Message is already in ${Languages.name(target)}")
                    } else {
                        refreshMessage(messageId)
                    }
                }
            } catch (e: Throwable) {
                logger.error("Failed to translate message $messageId", e)
                if (!manual) failed += messageId
                if (manual) {
                    val reason = (e as? Translator.TranslationException)?.message
                    Utils.mainThread.post {
                        Utils.showToast(if (reason != null) "Translation failed: $reason" else "Translation failed")
                    }
                }
            } finally {
                pending.remove(messageId)
            }
        }
    }

    private fun refreshMessage(messageId: Long) {
        val adapter = adapterRef?.get() ?: return
        val index = adapter.internalData.indexOfFirst { (it as? MessageEntry)?.message?.id == messageId }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun refreshChat() {
        Utils.mainThread.post { adapterRef?.get()?.notifyDataSetChanged() }
    }

    private fun isOwn(message: Message) = message.author?.id == StoreStream.getUsers().me.id

    private fun shouldTranslate(content: String) = content.any { Character.isLetter(it) }

    companion object {
        private const val KEY_LANGUAGE = "targetLanguage"
        private const val KEY_AUTO = "autoTranslate"
        private const val KEY_AUTO_OWN = "autoTranslateOwn"
        private const val KEY_SERVICE = "service"
        private const val KEY_DEEPL_KEY = "deeplKey"

        const val SERVICE_GOOGLE = "google"
        const val SERVICE_DEEPL = "deepl"
        const val SERVICE_LIBRE = "libre"
        private const val KEY_API_URL = "apiUrl"
        private const val KEY_API_KEY = "apiKey"
    }
}
