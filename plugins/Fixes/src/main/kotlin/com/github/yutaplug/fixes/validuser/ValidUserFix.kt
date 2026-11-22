package com.github.yutaplug.fixes.validuser

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.RxUtils.await
import com.discord.api.user.User
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.UserMentionNode
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.github.yutaplug.fixes.Fix
import retrofit2.HttpException
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Ported from js6pak's ValidUser (GPL-3.0): https://github.com/js6pak/ValidUser
 *
 * Mentions of users Discord has not cached render as "invalid-user". This fetches them and
 * redraws the messages that mention them.
 */
internal class ValidUserFix : Fix(
    "ValidUser",
    "Mentioned users",
    "Fetches users that mentions show as invalid-user because Discord has not loaded them yet.",
    author = "js6pak",
) {
    private class MessageRef(val messageId: Long, val adapter: WeakReference<WidgetChatListAdapter>)

    /** Which chat message each render context draws, so a fetched user can redraw it. */
    private val contexts = Collections.synchronizedMap(WeakHashMap<MessageRenderContext, MessageRef>())

    /** Users being fetched, with the messages waiting for them. Guarded by its own lock. */
    private val pending = HashMap<Long, MutableList<MessageRef>>()
    private val invalidUsers: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    override fun start(context: Context) {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "getMessageRenderContext",
            arrayOf(Context::class.java, MessageEntry::class.java, Function1::class.java),
            Hook { param ->
                val renderContext = param.result as? MessageRenderContext ?: return@Hook
                val item = param.thisObject as WidgetChatListAdapterItemMessage
                val entry = param.args[1] as MessageEntry
                contexts[renderContext] = MessageRef(entry.message.id, WeakReference(item.adapter))
            },
        )

        patcher.patch(
            UserMentionNode::class.java,
            "renderUserMention",
            arrayOf(SpannableStringBuilder::class.java, UserMentionNode.RenderContext::class.java),
            PreHook { param ->
                val node = param.thisObject as UserMentionNode<*>
                val builder = param.args[0] as SpannableStringBuilder
                val renderContext = param.args[1] as UserMentionNode.RenderContext? ?: return@PreHook
                val userId = node.userId

                if (userId in invalidUsers) {
                    appendMention(renderContext.context, builder, "<@!$userId>")
                    param.result = null
                    return@PreHook
                }
                val names = renderContext.userNames
                if (names != null && names.containsKey(userId)) return@PreHook

                val cached = StoreStream.getUsers().users[userId]
                if (cached != null) {
                    // Let Discord draw the mention (with its click handler) from the name.
                    val added = try {
                        names?.put(userId, cached.username)
                        names != null
                    } catch (_: UnsupportedOperationException) {
                        false
                    }
                    if (!added) {
                        appendMention(renderContext.context, builder, "@${cached.username}")
                        param.result = null
                    }
                    return@PreHook
                }

                appendMention(renderContext.context, builder, "@loading")
                param.result = null
                val ref = (renderContext as? MessageRenderContext)?.let { contexts[it] }
                val first = synchronized(pending) {
                    val waiting = pending[userId]
                    if (waiting == null) pending[userId] = ArrayList()
                    ref?.let { pending[userId]?.add(it) }
                    waiting == null
                }
                if (first) Utils.threadPool.execute { fetchUser(userId) }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        contexts.clear()
        synchronized(pending) { pending.clear() }
    }

    private fun fetchUser(userId: Long) {
        val (user, error) = RestAPI.getApi().userGet(userId).await()
        if (user != null) {
            val store = StoreStream.getUsers()
            StoreUser.`access$getDispatcher$p`(store).schedule {
                @Suppress("UNCHECKED_CAST")
                (StoreUser.`access$getNotifyUserUpdated$p`(store) as Function1<User, Unit>).invoke(user)
                Utils.mainThread.post { redraw(userId) }
            }
            return
        }
        if (error is HttpException && error.a() == 404) {
            invalidUsers.add(userId)
            Utils.mainThread.post { redraw(userId) }
        } else {
            logger.error("Could not fetch user $userId", error)
            // Leave the mention as is; the next redraw of the message tries again.
            synchronized(pending) { pending.remove(userId) }
        }
    }

    private fun redraw(userId: Long) {
        val waiting = synchronized(pending) { pending.remove(userId) } ?: return
        for (ref in waiting) {
            val adapter = ref.adapter.get() ?: continue
            val index = adapter.internalData.indexOfFirst { it is MessageEntry && it.message.id == ref.messageId }
            if (index == -1) continue
            try {
                adapter.notifyItemChanged(index)
            } catch (error: Throwable) {
                logger.warn("Could not redraw message ${ref.messageId}: $error")
            }
        }
    }

    /** Draws text styled like Discord's own mentions. */
    private fun appendMention(context: Context, builder: SpannableStringBuilder, text: String) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        val spans = listOf(
            StyleSpan(1),
            BackgroundColorSpan(themedColor(context, "theme_chat_mention_background")),
            ForegroundColorSpan(themedColor(context, "theme_chat_mention_foreground")),
        )
        for (span in spans) builder.setSpan(span, start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun themedColor(context: Context, attribute: String): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id == 0) 0 else ColorCompat.getThemedColor(context, id)
    }

    private companion object {
        const val SPAN_EXCLUSIVE_EXCLUSIVE = 33
    }
}
