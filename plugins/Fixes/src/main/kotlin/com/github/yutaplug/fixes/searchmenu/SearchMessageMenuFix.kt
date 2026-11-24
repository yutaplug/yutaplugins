package com.github.yutaplug.fixes.searchmenu

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.search.results.WidgetSearchResults
import com.github.yutaplug.fixes.Fix

/**
 * Holding a message in search jumps to it, like tapping does. This opens the same message menu as in
 * chat instead. Search results usually aren't loaded in the channel, so the menu, which looks the
 * message up in the channel, is handed the search result while it sets itself up.
 */
internal class SearchMessageMenuFix :
    Fix(
        "SearchMessageMenuFix",
        "Message menu in search",
        "Holding a message in search opens its menu, like in chat, instead of jumping to it. " +
            "Tapping still jumps to the message.",
    ) {
    /** Recent search results that were held, by message ID. */
    private val held = object : LinkedHashMap<Long, Message>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Message>?) = size > MAX_HELD
    }

    /** The search result whose menu is setting itself up right now. */
    private var resuming: Message? = null

    override fun start(context: Context) {
        val handler = WidgetSearchResults.SearchResultAdapterEventHandler::class.java
        val fragmentManagerField = handler.getDeclaredField("fragmentManager").apply { isAccessible = true }

        patcher.patch(
            handler,
            "onMessageLongClicked",
            arrayOf(Message::class.java, CharSequence::class.java, Boolean::class.javaPrimitiveType!!),
            PreHook { call ->
                val message = call.args[0] as Message
                val manager = fragmentManagerField.get(call.thisObject) as? FragmentManager ?: return@PreHook
                if (message.isLocal || message.id <= 0L) return@PreHook
                held[message.id] = message
                WidgetChatListActions()
                    .apply {
                        arguments = Bundle().apply {
                            putLong(EXTRA_CHANNEL_ID, message.channelId)
                            putLong(EXTRA_MESSAGE_ID, message.id)
                            putCharSequence(EXTRA_CONTENT, call.args[1] as? CharSequence ?: "")
                            putInt(EXTRA_TYPE, 0)
                            putBoolean(EXTRA_FROM_SEARCH, true)
                        }
                    }.show(manager, WidgetChatListActions::class.java.name)
                call.result = null
            },
        )

        // The menu builds its model in onResume, which looks the message up in the channel synchronously.
        patcher.patch(
            WidgetChatListActions::class.java,
            "onResume",
            emptyArray(),
            PreHook { call ->
                val args = (call.thisObject as WidgetChatListActions).arguments ?: return@PreHook
                if (!args.getBoolean(EXTRA_FROM_SEARCH, false)) return@PreHook
                resuming = held[args.getLong(EXTRA_MESSAGE_ID)]
            },
        )
        patcher.patch(
            WidgetChatListActions::class.java,
            "onResume",
            emptyArray(),
            Hook { resuming = null },
        )

        patcher.patch(
            StoreMessages::class.java,
            "observeMessagesForChannel",
            arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
            Hook { call ->
                val message = resuming ?: return@Hook
                if (message.channelId != call.args[0] as Long || message.id != call.args[1] as Long) return@Hook
                call.result = j0.l.e.k(message)
            },
        )
    }

    override fun stop(context: Context) {
        held.clear()
        resuming = null
    }

    private companion object {
        const val MAX_HELD = 20
        const val EXTRA_CHANNEL_ID = "INTENT_EXTRA_MESSAGE_CHANNEL_ID"
        const val EXTRA_MESSAGE_ID = "INTENT_EXTRA_MESSAGE_ID"
        const val EXTRA_CONTENT = "INTENT_EXTRA_MESSAGE_CONTENT"
        const val EXTRA_TYPE = "INTENT_EXTRA_TYPE"
        const val EXTRA_FROM_SEARCH = "yutaplug.fixes.FROM_SEARCH"
    }
}
