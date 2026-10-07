package com.github.yutaplug.instantmessages

import android.content.Context
import android.os.Build
import android.view.WindowInsetsAnimation
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.models.message.Message
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.input.SmoothKeyboardReactionHelper
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Makes outgoing messages appear immediately, optionally without grey text or chat animations. */
@AliucordPlugin
class InstantMessages : Plugin() {
    private class Deferred(var data: WidgetChatListAdapter.Data, val callback: Runnable)

    private val deferred = WeakHashMap<WidgetChatListAdapter, Deferred>()
    private var applyingDeferred: WidgetChatListAdapter? = null

    /** Discord's original animator for every chat recycler whose animator we removed. */
    private val animators = WeakHashMap<RecyclerView, RecyclerView.ItemAnimator?>()

    /** Pending-message text views whose grey alpha we removed. */
    private val brightenedViews: MutableSet<SimpleDraweeSpanTextView> =
        Collections.newSetFromMap(WeakHashMap())

    val greyPending get() = settings.getBool(GREY_PENDING, false)
    val removeAnimations get() = settings.getBool(REMOVE_ANIMATIONS, true)

    init {
        settingsTab = SettingsTab(InstantMessagesSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
            .withArgs(this)
    }

    override fun start(context: Context) {
        patchChatList()
        patchAdapter()
        patchPendingText()
        // WindowInsetsAnimation does not exist before Android 11.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) patchKeyboardAnimation()
    }

    override fun stop(context: Context) {
        for ((adapter, entry) in deferred.entries.toList()) {
            adapter.recycler.removeCallbacks(entry.callback)
            applyDeferred(adapter)
        }
        deferred.clear()
        applyingDeferred = null
        patcher.unpatchAll()
        restoreAnimations()
        restoreGrey()
    }

    // region Settings

    fun setGreyPending(enabled: Boolean) {
        settings.setBool(GREY_PENDING, enabled)
        if (enabled) restoreGrey()
    }

    fun setRemoveAnimations(enabled: Boolean) {
        settings.setBool(REMOVE_ANIMATIONS, enabled)
        // When enabled, the next chat update removes the animator.
        if (!enabled) restoreAnimations()
    }

    // endregion

    // region Animations

    private fun patchChatList() {
        val hook = Hook { frame ->
            if (!removeAnimations) return@Hook
            val chatList = frame.thisObject as WidgetChatList
            val adapter = WidgetChatList.`access$getAdapter$p`(chatList) ?: return@Hook
            val recycler = adapter.recycler
            disableAnimations(recycler)
            // onViewBoundOrOnResume stores the recycler's current animator, which may already be
            // null. Keep Discord's original so enableItemAnimations still works after we restore.
            animators[recycler]?.let { ReflectUtils.setField(chatList, "defaultItemAnimator", it) }
        }
        patcher.patch(WidgetChatList::class.java, "onViewBoundOrOnResume", hook = hook)
        patcher.patch(WidgetChatList::class.java, "enableItemAnimations", hook = hook)
    }

    private fun patchKeyboardAnimation() {
        // Returning the bounds without starting the callback makes the chat jump with the keyboard.
        patcher.patch(
            SmoothKeyboardReactionHelper.Callback::class.java,
            "onStart",
            arrayOf(WindowInsetsAnimation::class.java, WindowInsetsAnimation.Bounds::class.java),
            PreHook { frame -> if (removeAnimations) frame.result = frame.args[1] },
        )
    }

    private fun disableAnimations(recycler: RecyclerView) {
        val current = recycler.itemAnimator ?: return
        if (animators[recycler] == null) animators[recycler] = current
        recycler.itemAnimator = null
    }

    private fun restoreAnimations() {
        for ((recycler, animator) in animators) {
            if (recycler.itemAnimator == null) recycler.itemAnimator = animator
        }
        animators.clear()
    }

    // endregion

    // region Pending text

    private fun patchPendingText() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { frame ->
                val view = frame.args[0] as SimpleDraweeSpanTextView
                // Discord sets the alpha on every bind, including when a row is recycled.
                brightenedViews.remove(view)
                if (greyPending || !isPending((frame.args[1] as MessageEntry).message)) return@Hook
                view.alpha = 1f
                brightenedViews.add(view)
            },
        )
    }

    private fun restoreGrey() {
        for (view in brightenedViews) view.alpha = PENDING_ALPHA
        brightenedViews.clear()
    }

    // endregion

    // region Chat updates

    private fun patchAdapter() {
        patcher.patch(
            WidgetChatListAdapter::class.java,
            "setData",
            arrayOf(WidgetChatListAdapter.Data::class.java),
            PreHook { frame ->
                val adapter = frame.thisObject as WidgetChatListAdapter
                val data = frame.args[0] as WidgetChatListAdapter.Data
                if (removeAnimations) disableAnimations(adapter.recycler)
                if (applyingDeferred !== adapter && defer(adapter, data)) {
                    frame.result = null
                } else {
                    scrollToNewOutgoing(adapter, data)
                }
            },
        )
    }

    /**
     * While a message is acknowledged Discord briefly shows both the pending and the sent copy,
     * or neither. Hold such updates back so the message never flickers or jumps.
     */
    private fun defer(adapter: WidgetChatListAdapter, incoming: WidgetChatListAdapter.Data): Boolean {
        val current = adapter.data
        if (current.channelId != incoming.channelId || current.userId != incoming.userId) {
            cancelDeferred(adapter)
            return false
        }
        val incomingMessages = messages(incoming.list)
        val currentPending = messages(current.list).filter(::isPending)
        val transient = incomingMessages.any { pending ->
            isPending(pending) && incomingMessages.any { !it.isLocal && sameMessage(it, pending) }
        } || currentPending.any { pending -> incomingMessages.none { sameMessage(it, pending) } }
        // Never hide a newly sent message behind an older acknowledgement.
        val newPending = incomingMessages.any { pending ->
            isPending(pending) && currentPending.none { sameMessage(it, pending) }
        }
        if (!transient || newPending) {
            cancelDeferred(adapter)
            return false
        }

        deferred[adapter]?.let {
            // Replace the payload without extending the original deadline.
            it.data = incoming
            return true
        }
        val reference = WeakReference(adapter)
        val callback = Runnable { reference.get()?.let(::applyDeferred) }
        deferred[adapter] = Deferred(incoming, callback)
        if (!adapter.recycler.postDelayed(callback, DEFER_MS)) {
            deferred.remove(adapter)
            return false
        }
        return true
    }

    private fun applyDeferred(adapter: WidgetChatListAdapter) {
        val entry = deferred.remove(adapter) ?: return
        val data = entry.data
        if (adapter.data.channelId != data.channelId || adapter.data.userId != data.userId) return
        applyingDeferred = adapter
        try {
            adapter.setData(data)
        } finally {
            applyingDeferred = null
        }
    }

    private fun cancelDeferred(adapter: WidgetChatListAdapter) {
        val entry = deferred.remove(adapter) ?: return
        adapter.recycler.removeCallbacks(entry.callback)
    }

    private fun scrollToNewOutgoing(adapter: WidgetChatListAdapter, data: WidgetChatListAdapter.Data) {
        val current = adapter.data
        // Initial binds and channel switches keep Discord's chosen position.
        if (current.channelId != data.channelId) return
        val messages = messages(data.list)
        val previous = messages(current.list)
        val previousIds = previous.mapTo(HashSet()) { it.id }
        val previousNonces = previous.mapNotNullTo(HashSet()) { it.nonce }
        fun isNewOwn(message: Message) = message.author?.id == data.userId &&
            message.id !in previousIds && (message.nonce == null || message.nonce !in previousNonces)

        val newest = messages.firstOrNull() ?: return
        val newPending = messages.any { isPending(it) && isNewOwn(it) }
        val newSent = !newest.isLocal && isNewOwn(newest) &&
            previous.firstOrNull()?.let { newest.id > it.id } == true
        if (!newPending && !newSent) return
        val layoutManager = adapter.layoutManager ?: return
        adapter.recycler.stopScroll()
        layoutManager.scrollToPositionWithOffset(0, 0)
    }

    // endregion

    private fun messages(entries: List<ChatListEntry>): List<Message> =
        entries.mapNotNull { (it as? MessageEntry)?.message }

    private fun isPending(message: Message) = message.type == PENDING_TYPE

    // Nonces identify acknowledgements; text and timestamps are ambiguous for repeated messages.
    private fun sameMessage(candidate: Message, target: Message) =
        candidate.channelId == target.channelId &&
            (candidate.id == target.id || (target.nonce != null && target.nonce == candidate.nonce))

    companion object {
        const val GREY_PENDING = "greyPending"
        const val REMOVE_ANIMATIONS = "removeAnimations"
        private const val PENDING_TYPE = -1
        private const val PENDING_ALPHA = 0.5f
        private const val DEFER_MS = 500L
    }
}
