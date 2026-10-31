package com.github.yutaplug.swipetoreplyandedit

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.message.Message
import com.discord.panels.OverlappingPanelsLayout
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.min

@AliucordPlugin
class SwipeToReplyAndEdit : Plugin() {
    private val log = Logger("SwipeToReplyAndEdit")
    private val helpers = WeakHashMap<RecyclerView, ItemTouchHelper>()
    private var currentRecycler: WeakReference<RecyclerView>? = null

    /** Whether the open channel's chat input currently allows typing. */
    @Volatile
    private var canType = false

    var editOwn: Boolean
        get() = settings.getBool(EDIT_OWN, false)
        set(value) = settings.setBool(EDIT_OWN, value)

    init {
        settingsTab = SettingsTab(SwipeToReplyAndEditSettings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(this)
    }

    override fun start(context: Context) {
        patcher.patch(
            WidgetChatList::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { param ->
                val view = param.args[0] as View
                val recycler = view as? RecyclerView
                    ?: view.findViewById(Utils.getResId("chat_list_recycler", "id"))
                    ?: return@Hook
                attach(recycler)
            },
        )

        // Track whether the current channel's chat input lets you type (permissions, timeouts,
        // member verification, blocked DMs, locked threads, ...), as decided by Discord itself.
        patcher.patch(
            WidgetChatInput::class.java.getDeclaredMethod("configureUI", ChatInputViewModel.ViewState::class.java),
            Hook { param ->
                val state = param.args[0] as? ChatInputViewModel.ViewState.Loaded
                canType = state != null && state.isInputShowing && state.ableToSendMessage
            },
        )

        // Swiping left over the chat normally opens the member list panel. Keep that panel from
        // stealing leftward swipes that start on a message, so the swipe reaches the message.
        val panelsClass = OverlappingPanelsLayout::class.java
        val downX = panelsClass.getDeclaredField("xFromInterceptActionDown").apply { isAccessible = true }
        val downY = panelsClass.getDeclaredField("yFromInterceptActionDown").apply { isAccessible = true }
        val scrolling = panelsClass.getDeclaredField("isScrollingHorizontally").apply { isAccessible = true }
        val closedCenter = panelsClass.getDeclaredField("wasActionDownOnClosedCenterPanel").apply { isAccessible = true }
        patcher.patch(
            panelsClass.getDeclaredMethod("onInterceptTouchEvent", MotionEvent::class.java),
            PreHook { param ->
                val event = param.args[0] as MotionEvent
                if (event.actionMasked != MotionEvent.ACTION_MOVE) return@PreHook
                val panels = param.thisObject
                if (scrolling.getBoolean(panels) || closedCenter.getBoolean(panels)) return@PreHook
                val dx = event.x - downX.getFloat(panels)
                val dy = event.y - downY.getFloat(panels)
                if (dx >= 0 || abs(dx) <= abs(dy)) return@PreHook
                val recycler = currentRecycler?.get() ?: return@PreHook
                if (!recycler.isAttachedToWindow || !isOverSwipeableMessage(recycler, event)) return@PreHook
                param.result = false
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        for (helper in helpers.values) helper.attachToRecyclerView(null)
        helpers.clear()
        currentRecycler = null
    }

    private fun isOverSwipeableMessage(recycler: RecyclerView, event: MotionEvent): Boolean {
        val loc = IntArray(2)
        recycler.getLocationOnScreen(loc)
        val child = recycler.findChildViewUnder(event.rawX - loc[0], event.rawY - loc[1]) ?: return false
        val holder = recycler.getChildViewHolder(child) ?: return false
        return swipeableMessage(recycler, holder) != null
    }

    private fun attach(recycler: RecyclerView) {
        currentRecycler = WeakReference(recycler)
        if (helpers.containsKey(recycler)) return
        val helper = ItemTouchHelper(SwipeCallback(recycler.context))
        helper.attachToRecyclerView(recycler)
        helpers[recycler] = helper
    }

    private fun entryAt(recycler: RecyclerView, holder: RecyclerView.ViewHolder): ChatListEntry? {
        val adapter = recycler.adapter as? WidgetChatListAdapter ?: return null
        val pos = holder.adapterPosition
        val data = adapter.internalData
        if (pos == RecyclerView.NO_POSITION || pos >= data.size) return null
        return data[pos]
    }

    private fun messageOf(entry: ChatListEntry?): Message? = when (entry) {
        is MessageEntry -> entry.message
        is AttachmentEntry -> entry.message
        is EmbedEntry -> entry.message
        is StickerEntry -> entry.message
        is ReactionsEntry -> entry.message
        is BotUiComponentEntry -> entry.message
        else -> null
    }

    /** The message this row belongs to, if swiping it can reply to or edit it. */
    private fun swipeableMessage(recycler: RecyclerView, holder: RecyclerView.ViewHolder): Message? {
        if (!canType) return null
        val message = messageOf(entryAt(recycler, holder)) ?: return null
        if (message.isLocal || message.isFailed || message.isEphemeralMessage) return null
        if (!message.isUserMessage && !message.isApplicationMessage) return null
        return message
    }

    private fun isOwnEditable(message: Message): Boolean {
        val me = runCatching { StoreStream.getUsers().me.id }.getOrNull() ?: return false
        return message.author?.id == me && message.isUserMessage
    }

    private fun perform(message: Message) {
        try {
            if (editOwn && isOwnEditable(message)) {
                edit(message)
            } else {
                reply(message)
            }
            openKeyboard()
        } catch (e: Throwable) {
            log.error("Failed to handle swipe", e)
        }
    }

    private fun reply(message: Message) {
        val channel = StoreStream.getChannels().getChannel(message.channelId) ?: return
        val me = StoreStream.getUsers().me.id
        val isOwn = message.author?.id == me
        val isWebhook = message.isWebhook
        // Same mention defaults as Discord's own Reply action.
        val shouldMention = !isWebhook && !isOwn
        val showMentionToggle = !channelIsDm(channel) && !isWebhook && !isOwn
        val companion = StoreStream::class.java.getField("Companion").get(null)!!
        val pendingReplies = companion.javaClass.getMethod("getPendingReplies").invoke(companion) as StorePendingReplies
        pendingReplies.onCreatePendingReply(channel, message, shouldMention, showMentionToggle)
    }

    private fun channelIsDm(channel: com.discord.api.channel.Channel): Boolean =
        com.discord.api.channel.ChannelUtils.B(channel)

    private fun edit(message: Message) {
        // Reuse Discord's own edit action so the message content gets unparsed the same way.
        val method = WidgetChatListActions::class.java.getDeclaredMethod("editMessage", Message::class.java)
        method.isAccessible = true
        method.invoke(WidgetChatListActions(), message)
    }

    private fun openKeyboard() {
        val activity = Utils.appActivity ?: return
        val input = activity.findViewById<EditText>(Utils.getResId("text_input", "id")) ?: return
        input.postDelayed({
            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private inner class SwipeCallback(context: Context) : ItemTouchHelper.Callback() {
        private val density = context.resources.displayMetrics.density
        private val maxShift = 72 * density
        private val trigger = 56 * density
        private val iconSize = (24 * density).toInt()
        private val replyIcon = loadIcon(context, "ic_reply_24dp")
        private val editIcon = loadIcon(context, "ic_edit_24dp")

        private var swipingMessage: Message? = null
        private var triggered = false
        private val shifted = mutableSetOf<View>()

        private fun loadIcon(context: Context, name: String): Drawable? =
            ContextCompat.getDrawable(context, Utils.getResId(name, "drawable"))?.mutate()?.apply {
                setTint(ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")))
            }

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (swipeableMessage(recyclerView, viewHolder) == null) return 0
            return makeMovementFlags(0, ItemTouchHelper.LEFT)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = false

        // Never let the row actually get swiped away; it always springs back.
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = Float.MAX_VALUE

        override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

        override fun getAnimationDuration(recyclerView: RecyclerView, animationType: Int, animateDx: Float, animateDy: Float) =
            180L

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder != null) {
                val recycler = currentRecycler?.get()
                swipingMessage = recycler?.let { swipeableMessage(it, viewHolder) }
                triggered = false
            } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                // Finger lifted.
                val message = swipingMessage
                swipingMessage = null
                if (triggered && message != null) perform(message)
                triggered = false
            }
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            val shift = -min(abs(dX.coerceAtMost(0f)), maxShift)
            val message = messageOf(entryAt(recyclerView, viewHolder))

            if (isCurrentlyActive && swipingMessage != null) {
                val nowTriggered = abs(dX) >= trigger
                if (nowTriggered && !triggered) {
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                triggered = nowTriggered
            }

            // Move every row of the message (text, attachments, embeds, reactions) together.
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                val holder = recyclerView.getChildViewHolder(child) ?: continue
                val same = holder === viewHolder ||
                    (message != null && messageOf(entryAt(recyclerView, holder))?.id == message.id)
                if (same) {
                    child.translationX = shift
                    shifted.add(child)
                }
            }

            val icon = (if (message != null && editOwn && isOwnEditable(message)) editIcon else replyIcon) ?: return
            val progress = min(abs(shift) / trigger, 1f)
            if (progress <= 0f) return
            val item = viewHolder.itemView
            val size = (iconSize * (0.6f + 0.4f * progress)).toInt()
            val cx = (item.right + shift / 2).toInt()
            val cy = (item.top + item.bottom) / 2
            icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            icon.alpha = (255 * progress).toInt()
            icon.draw(c)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            for (view in shifted) view.translationX = 0f
            shifted.clear()
        }
    }

    private companion object {
        const val EDIT_OWN = "edit_own"
    }
}
