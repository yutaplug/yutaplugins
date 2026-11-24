package com.github.yutaplug.fixes.messagelink

import android.content.Context
import android.os.SystemClock
import com.aliucord.patcher.PreHook
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.github.yutaplug.fixes.Fix
import rx.functions.Action0

internal class MessageLinkFix :
    Fix(
        "MessageLinkFix",
        "Message links",
        "Opens message links in Aliucord instead of the Discord app or website, " +
            "and stops them from sometimes jumping to the end of the chat.",
    ) {
    @Volatile private var jump: Jump? = null

    private val activeScrollField by lazy {
        WidgetChatListAdapter::class.java.getDeclaredField("scrollToWithHighlight").apply {
            isAccessible = true
        }
    }

    override fun start(context: Context) {
        patcher.patch(
            UriHandler::class.java,
            "handle",
            arrayOf<Class<*>>(
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Function0::class.java,
            ),
            PreHook { call ->
                val destination = MessageLink.parse(call.args[1] as? String ?: return@PreHook) ?: return@PreHook
                StoreStream.getMessagesLoader().jumpToMessage(destination.channelId, destination.messageId)
                call.result = null
            },
        )

        patcher.patch(
            StoreMessagesLoader::class.java,
            "jumpToMessage",
            arrayOf<Class<*>>(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
            PreHook { call ->
                val channelId = call.args[0] as Long
                val messageId = call.args[1] as Long
                jump = if (channelId > 0 && messageId > 1) {
                    Jump(channelId, SystemClock.uptimeMillis() + GUARD_MILLIS)
                } else {
                    null
                }
            },
        )

        patcher.patch(
            WidgetChatListAdapter::class.java,
            "scrollToMessageId",
            arrayOf<Class<*>>(Long::class.javaPrimitiveType!!, Action0::class.java),
            PreHook { call ->
                if (call.args[0] != StoreMessagesLoader.SCROLL_TO_LAST_UNREAD) return@PreHook
                val adapter = call.thisObject as? WidgetChatListAdapter ?: return@PreHook
                val completed = call.args[1] as? Action0 ?: return@PreHook
                // Only the channel-change update has this callback. Normal
                // unread navigation uses a different callback and still runs.
                if (completed.javaClass.name != CHANNEL_CHANGE_CALLBACK) return@PreHook
                val activeJump = jump ?: return@PreHook
                if (SystemClock.uptimeMillis() >= activeJump.expiresAt) {
                    jump = null
                    return@PreHook
                }
                if (adapter.data.channelId != activeJump.channelId) return@PreHook

                // This callback is a no-op in Discord 126.21. Calling it
                // preserves scrollToMessageId's completion contract.
                completed.call()
                call.result = null
            },
        )

        patcher.patch(
            WidgetChatListAdapter.ScrollToWithHighlight::class.java,
            "run",
            emptyArray(),
            PreHook { call ->
                val scroll = call.thisObject as? WidgetChatListAdapter.ScrollToWithHighlight ?: return@PreHook
                val adapter = scroll.adapter
                val activeJump = jump ?: return@PreHook
                if (SystemClock.uptimeMillis() >= activeJump.expiresAt) return@PreHook
                if (adapter.data.channelId != activeJump.channelId) return@PreHook
                if (scroll.messageId > StoreMessagesLoader.SCROLL_TO_LATEST) return@PreHook
                // cancel() only removes posted callbacks; its coroutine retry
                // may still call run() after another scroll has replaced it.
                if (activeScrollField.get(adapter) !== scroll) call.result = null
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        jump = null
    }

    private data class Jump(val channelId: Long, val expiresAt: Long)

    private companion object {
        private const val GUARD_MILLIS = 15_000L
        private const val CHANNEL_CHANGE_CALLBACK =
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$HandlerOfUpdates\$run\$1"
    }
}
