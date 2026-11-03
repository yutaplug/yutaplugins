package com.github.yutaplug.replacetext

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.api.message.embed.EmbedAuthor
import com.discord.api.message.embed.EmbedField
import com.discord.api.message.embed.EmbedFooter
import com.discord.api.message.embed.EmbedProvider
import com.discord.api.message.embed.MessageEmbed
import com.discord.models.message.Message
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.MessageContent
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListItem
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.lang.ref.WeakReference

@AliucordPlugin
class ReplaceText : Plugin() {
    private var adapterRef: WeakReference<WidgetChatListAdapter>? = null

    // Chat list views are only bound on the main thread, so plain counters are enough.
    private var renderingMessage = 0
    private var renderingEmbed = 0

    init {
        settingsTab = SettingsTab(ReplaceTextSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    var rules: List<Rule> = emptyList()
        private set

    fun saveRules(newRules: List<Rule>) {
        rules = newRules.map { it.copy() }
        settings.setString(KEY_RULES, Rule.listToJson(rules))
        refreshChat()
    }

    private fun replace(text: String, enabled: (Rule) -> Boolean): String {
        var result = text
        for (rule in rules) if (enabled(rule)) result = rule.apply(result)
        return result
    }

    override fun start(context: Context) {
        rules = Rule.listFromJson(settings.getString(KEY_RULES, "[]"))
        patchOutgoing()
        patchMessages()
        patchEmbeds()
    }

    /** Replaces text typed in the chat box before it is sent or saved as an edit. */
    private fun patchOutgoing() {
        patcher.patch(
            ChatInputViewModel::class.java.getDeclaredMethod(
                "sendMessage",
                Context::class.java,
                com.discord.widgets.chat.MessageManager::class.java,
                MessageContent::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                Class.forName("kotlin.jvm.functions.Function1"),
            ),
            PreHook { call ->
                if (rules.none { it.unsent }) return@PreHook
                val content = call.args[2] as MessageContent
                val text = content.textContent ?: return@PreHook
                val replaced = replace(text) { it.unsent }
                if (replaced != text) ReflectUtils.setField(content, "textContent", replaced)
            },
        )
    }

    /** Shows replacements in chat messages; the stored message is left as is. */
    private fun patchMessages() {
        val processMessageText = WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
            "processMessageText",
            SimpleDraweeSpanTextView::class.java,
            MessageEntry::class.java,
        )
        patcher.patch(
            processMessageText,
            PreHook { call ->
                adapterRef = WeakReference((call.thisObject as WidgetChatListAdapterItemMessage).adapter as WidgetChatListAdapter)
                renderingMessage++
            },
        )
        patcher.patch(processMessageText, Hook { renderingMessage = maxOf(0, renderingMessage - 1) })
        patcher.patch(
            Message::class.java.getDeclaredMethod("getContent"),
            Hook { call ->
                if (renderingMessage == 0) return@Hook
                val text = call.result as? String ?: return@Hook
                call.result = replace(text) { it.sent }
            },
        )
    }

    /** Shows replacements in embed titles, descriptions, fields, authors, providers and footers. */
    private fun patchEmbeds() {
        val begin = PreHook { call ->
            (call.thisObject as WidgetChatListItem).adapter?.let { adapterRef = WeakReference(it as WidgetChatListAdapter) }
            renderingEmbed++
        }
        val end = Hook { renderingEmbed = maxOf(0, renderingEmbed - 1) }
        val onConfigure = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "onConfigure",
            Int::class.javaPrimitiveType,
            ChatListEntry::class.java,
        )
        val configureUI = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod(
            "configureUI",
            WidgetChatListAdapterItemEmbed.Model::class.java,
        )
        for (method in listOf(onConfigure, configureUI)) {
            patcher.patch(method, begin)
            patcher.patch(method, end)
        }

        val replaceResult = Hook { call ->
            if (renderingEmbed == 0) return@Hook
            val text = call.result as? String ?: return@Hook
            call.result = replace(text) { it.embeds }
        }
        val getters = listOf(
            MessageEmbed::class.java to "j", // title
            MessageEmbed::class.java to "c", // description
            EmbedField::class.java to "a", // name
            EmbedField::class.java to "b", // value
            EmbedAuthor::class.java to "a", // name
            EmbedFooter::class.java to "b", // text
            EmbedProvider::class.java to "a", // name
        )
        for ((clazz, name) in getters) patcher.patch(clazz.getDeclaredMethod(name), replaceResult)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        renderingMessage = 0
        renderingEmbed = 0
        refreshChat()
        adapterRef = null
    }

    private fun refreshChat() {
        Utils.mainThread.post { adapterRef?.get()?.notifyDataSetChanged() }
    }

    companion object {
        private const val KEY_RULES = "rules"
    }
}
