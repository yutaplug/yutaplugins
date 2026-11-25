package com.github.yutaplug.fixes.messagelink

import android.content.Context
import android.os.SystemClock
import com.aliucord.Utils
import com.aliucord.patcher.PreHook
import com.aliucord.utils.RxUtils.await
import com.discord.api.channel.ChannelUtils
import com.discord.utilities.rest.RestAPI
import java.util.Collections
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.github.yutaplug.fixes.Fix
import rx.functions.Action0

internal class MessageLinkFix :
    Fix(
        "MessageLinkFix",
        "Message links",
        "Opens message, channel and forum post links in Aliucord instead of the Discord app or website, " +
            "and stops them from sometimes jumping to the end of the chat.",
    ) {
    @Volatile private var jump: Jump? = null
    private val fetching: MutableSet<Long> = Collections.synchronizedSet(HashSet())
    private val retrying: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    private val storeCompanion by lazy {
        StoreStream::class.java.getField("Companion").get(null) as StoreStream.Companion
    }

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
                val messageId = destination.messageId
                if (messageId != null) {
                    StoreStream.getMessagesLoader().jumpToMessage(destination.channelId, messageId)
                } else {
                    openChannel(call.args[0] as Context, destination.channelId)
                }
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
                // Discord only waits 1-3 seconds for the channel to show up in
                // the store and never fetches it, so jumping to a forum post or
                // thread that isn't loaded silently does nothing. Fetch it first.
                if (channelId > 0 && messageId > 1 && !retrying.remove(channelId) &&
                    StoreStream.getChannels().getChannel(channelId) == null
                ) {
                    if (fetching.add(channelId)) Utils.threadPool.execute { fetchThenJump(channelId, messageId) }
                    call.result = null
                    return@PreHook
                }
                jump =if (channelId > 0 && messageId > 1) {
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

    /** Same as Discord's own discord://app/channels route, minus its forum experiment check. */
    private fun openChannel(context: Context, channelId: Long) {
        val selector = ChannelSelector.getInstance()
        if (StoreStream.getChannels().getChannel(channelId) != null) {
            selector.findAndSet(context, channelId)
        } else {
            // Threads and forum posts the client hasn't loaded yet: fetch the
            // channel from the API first, then select it.
            selector.findAndSetThread(context, channelId)
        }
        storeCompanion.tabsNavigation.selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
    }

    private fun fetchThenJump(channelId: Long, messageId: Long) {
        try {
            val (channel, _) = RestAPI.getApi().getChannel(channelId).await()
            if (channel != null && ChannelUtils.H(channel)) {
                val selector = ChannelSelector.getInstance()
                selector.dispatcher.schedule { selector.stream.handleThreadCreateOrUpdate(channel) }
            }
        } catch (error: Throwable) {
            logger.error("Could not load channel $channelId", error)
        } finally {
            fetching.remove(channelId)
            // Jump either way: if the fetch failed, Discord's own behavior is unchanged.
            retrying.add(channelId)
            StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        fetching.clear()
        retrying.clear()
        jump = null
    }

    private data class Jump(val channelId: Long, val expiresAt: Long)

    private companion object {
        private const val GUARD_MILLIS = 15_000L
        private const val CHANNEL_CHANGE_CALLBACK =
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$HandlerOfUpdates\$run\$1"
    }
}
