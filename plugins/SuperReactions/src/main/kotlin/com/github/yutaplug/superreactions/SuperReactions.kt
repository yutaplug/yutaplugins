package com.github.yutaplug.superreactions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.GsonUtils.toJson
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.api.premium.PremiumTier
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.restapi.BreadcrumbInterceptor
import com.discord.stores.StoreMessageReactions
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.user.UserUtils
import com.discord.views.ReactionView
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.managereactions.ManageReactionsEmojisAdapter
import com.discord.widgets.chat.managereactions.ManageReactionsModel
import com.discord.widgets.chat.managereactions.ManageReactionsResultsAdapter
import com.discord.widgets.chat.managereactions.WidgetManageReactions
import com.google.gson.stream.JsonReader
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import java.io.StringReader
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.util.WeakHashMap
import java.util.regex.Pattern

@AliucordPlugin
@Suppress("unused")
class SuperReactions : Plugin() {
    private val store = BurstStore()
    private var api: BurstApi? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Burst pills this plugin created; normal and burst models can be value-equal. */
    private val burstPills = WeakIdentityMap<MessageReaction, Boolean>()
    private val gatewayEvents = WeakIdentityMap<MessageReactionUpdate, GatewayBurst>()

    // Main-thread state.
    private val rows = WeakHashMap<WidgetChatListAdapterItemReactions, Row>()
    private val nativeBackgrounds = WeakHashMap<ReactionView, Drawable?>()
    private val pillBackgrounds = WeakHashMap<ReactionView, GradientDrawable>()
    private val nativeCounterColors = WeakHashMap<ReactionView, Pair<ColorStateList?, ColorStateList?>>()
    private val snapshotQueue = HashMap<Long, MutableSet<Long>>()
    private val snapshotAttempts = HashMap<Long, Int>()
    private val inFlight = HashSet<String>()
    private var superMode = false
    private var pickerButton: WeakReference<ImageView>? = null

    // Reactions sheet (long-press on a pill).
    private val manageStates = WeakHashMap<WidgetManageReactions, ManageState>()
    private val manageAdapters = WeakHashMap<ManageReactionsEmojisAdapter, WeakReference<WidgetManageReactions>>()
    private val burstTabs = WeakIdentityMap<ManageReactionsEmojisAdapter.ReactionEmojiItem, Boolean>()
    private val burstUsers = HashMap<String, List<User>>()
    private val burstUserFetches = HashSet<String>()
    private var pendingManageBurst: Pair<Long, String>? = null

    /** Local add/remove requests, so the gateway echo isn't applied twice. Guarded by itself. */
    private val ops = HashMap<String, Op>()

    private class Row(val position: Int, val entry: ReactionsEntry)
    private class GatewayBurst(val burst: Boolean, val color: Int?)
    private enum class Op { SENT, ECHOED, APPLIED }

    private class ManageState(val channelId: Long, val messageId: Long) {
        /** The emoji whose super reaction tab is selected, or null for its normal tab. */
        var burstKey: String? = null
        var awaiting: String? = null
        var model: ManageReactionsModel? = null
    }

    override fun start(context: Context) {
        api = BurstApi()
        patchRestCapture()
        patchGateway()
        patchReactionRows()
        patchReactionActions()
        patchEmojiPicker()
        patchManageReactions()
    }

    // region Data: REST payloads and gateway events

    /**
     * Discord already loads message history over REST, and those payloads carry
     * count_details, me_burst and burst_colors that its models discard. Reading
     * them from the response (without consuming it) needs no extra requests.
     */
    private fun patchRestCapture() {
        patcher.patch(
            BreadcrumbInterceptor::class.java,
            "intercept",
            arrayOf(Interceptor.Chain::class.java),
            Hook { frame ->
                val response = frame.result as? Response ?: return@Hook
                try {
                    captureMessages(response)
                } catch (error: Throwable) {
                    logger.error("Could not read reaction details", error)
                }
            },
        )
    }

    private fun captureMessages(response: Response) {
        if (response.m != 200 || response.j.c != "GET") return
        val path = Uri.parse(response.j.b.toString()).path ?: return
        if (!MESSAGES_PATH.matcher(path).matches()) return
        val source = response.p?.c() ?: return
        // okio: request(Long.MAX_VALUE) buffers the whole body; reading a clone of
        // the buffer leaves it intact for Discord's own parser.
        source.j(Long.MAX_VALUE)
        val buffer = source.g()
        val copy = BUFFER_CLONE.invoke(buffer) as g0.e
        val body = copy.M(StandardCharsets.UTF_8)
        // Parse even lists without reactions: it marks those messages as known.
        val changed = store.ingestMessages(JSONArray(body))
        if (changed.isNotEmpty()) mainHandler.post { refreshRows(changed) }
    }

    private fun patchGateway() {
        // The legacy MessageReactionUpdate model drops `burst` and `burst_colors`; keep them.
        patcher.patch(
            InboundGatewayGsonParser::class.java,
            "fromJson",
            arrayOf(JsonReader::class.java, Class::class.java),
            PreHook { frame ->
                if (frame.args[1] != MessageReactionUpdate::class.java) return@PreHook
                val raw = GsonUtils.gson.d<Map<*, *>>(frame.args[0] as JsonReader, Map::class.java)
                val update = raw?.let {
                    InboundGatewayGsonParser.INSTANCE.getGatewayGsonInstance().d<MessageReactionUpdate>(
                        JsonReader(StringReader(GsonUtils.gson.toJson(it))),
                        MessageReactionUpdate::class.java,
                    )
                }
                if (raw != null && update != null) {
                    val burst = (raw["burst"] as? Boolean) ?: ((raw["type"] as? Number)?.toInt() == 1)
                    gatewayEvents.put(update, GatewayBurst(burst, BurstStore.pickColor(raw["burst_colors"] as? List<*>)))
                }
                frame.result = update
            },
        )
        // Burst users don't belong in Discord's normal reaction user lists.
        for (method in listOf("handleReactionAdd", "handleReactionRemove")) {
            patcher.patch(
                StoreMessageReactions::class.java,
                method,
                arrayOf(MessageReactionUpdate::class.java),
                PreHook { frame ->
                    if (gatewayEvents[frame.args[0] as MessageReactionUpdate]?.burst == true) frame.result = null
                },
            )
        }
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionUpdate",
            arrayOf(List::class.java, BOOLEAN),
            PreHook { frame -> frame.args[0] = applyBurstUpdates(frame.args[0] as? List<*>, frame.args[1] == true) },
        )
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionsRemoveAll",
            arrayOf(MessageReactionUpdate::class.java),
            Hook { frame -> store.clear((frame.args[0] as MessageReactionUpdate).c()) },
        )
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionsRemoveEmoji",
            arrayOf(MessageReactionUpdate::class.java),
            Hook { frame ->
                val update = frame.args[0] as MessageReactionUpdate
                store.clearEmoji(update.c(), update.b()?.c())
            },
        )
    }

    /**
     * Records burst events and lets Discord count them in the reaction's total.
     * Our own burst events get another user id so Discord doesn't mark the normal
     * reaction as ours, and echoes of a change already applied locally are dropped.
     */
    private fun applyBurstUpdates(updates: List<*>?, add: Boolean): List<*>? {
        updates ?: return null
        val me = currentUserId()
        var changed = false
        val result = ArrayList<Any?>(updates.size)
        for (raw in updates) {
            val update = raw as? MessageReactionUpdate
            val event = update?.let { gatewayEvents[it] }
            if (update == null || event?.burst != true) {
                result.add(raw)
                continue
            }
            val key = update.b()?.c()
            val mine = me != 0L && update.d() == me
            if (mine) {
                val opKey = opKey(update.c(), key, add)
                val alreadyApplied = synchronized(ops) {
                    when (ops[opKey]) {
                        Op.APPLIED -> {
                            ops.remove(opKey)
                            true
                        }
                        Op.SENT -> {
                            ops[opKey] = Op.ECHOED
                            false
                        }
                        else -> false
                    }
                }
                if (alreadyApplied) {
                    // Applied locally without colors; the echo carries them.
                    store.recolor(update.c(), key, event.color)
                    changed = true
                    continue
                }
            }
            store.apply(update.c(), key, add, mine, event.color)
            invalidateBurstUsers(update.c())
            if (mine) {
                result.add(MessageReactionUpdate(otherUserId(me), update.a(), update.c(), update.b()))
                changed = true
            } else {
                result.add(raw)
            }
        }
        return if (changed) result else updates
    }

    // endregion

    // region Reaction pills

    private fun patchReactionRows() {
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "onConfigure",
            arrayOf(INT, ChatListEntry::class.java),
            Hook { frame ->
                val item = frame.thisObject as WidgetChatListAdapterItemReactions
                val entry = frame.args[1] as? ReactionsEntry ?: return@Hook
                rows[item] = Row(frame.args[0] as Int, entry)
                val message = entry.message
                if (!store.hasSnapshot(message.id) && message.reactionsMap.isNotEmpty()) {
                    requestSnapshot(message.channelId, message.id)
                }
            },
        )
        // Split each reaction into a super pill (first) and a normal pill, like Discord does.
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "displayReactions",
            arrayOf(Collection::class.java, LONG, BOOLEAN, BOOLEAN, BOOLEAN),
            PreHook { frame ->
                val messageId = frame.args[1] as Long
                if (!store.hasAny(messageId)) return@PreHook
                val reactions = frame.args[0] as? Collection<*> ?: return@PreHook
                frame.args[0] = splitReactions(reactions, messageId)
            },
        )
        patcher.patch(
            ReactionView::class.java,
            "a",
            arrayOf(MessageReaction::class.java, LONG, BOOLEAN),
            Hook { frame ->
                val view = frame.thisObject as ReactionView
                val reaction = frame.args[0] as MessageReaction
                if (burstPills[reaction] == true) {
                    styleBurstPill(view, store.get(frame.args[1] as Long, reaction.b()?.c()))
                } else {
                    restorePill(view)
                }
            },
        )
    }

    private fun splitReactions(reactions: Collection<*>, messageId: Long): List<MessageReaction> {
        val result = ArrayList<MessageReaction>(reactions.size + 4)
        for (raw in reactions) {
            val reaction = raw as? MessageReaction ?: continue
            val burst = store.get(messageId, reaction.b()?.c())
            if (burst == null || burst.count <= 0) {
                result.add(reaction)
                continue
            }
            val pill = MessageReaction(burst.count, reaction.b(), false)
            burstPills.put(pill, true)
            result.add(pill)
            // Discord's count is the total of both kinds.
            val normal = maxOf(reaction.a() - burst.count, if (reaction.c()) 1 else 0)
            if (normal > 0) result.add(MessageReaction(normal, reaction.b(), reaction.c()))
        }
        return result
    }

    /** A flat tint of the emoji's color, outlined when it's yours. No shine, like Discord. */
    private fun styleBurstPill(view: ReactionView, burst: Burst?) {
        if (!nativeBackgrounds.containsKey(view)) {
            nativeBackgrounds[view] = view.background
            nativeCounterColors[view] = counter(view, COUNTER_1)?.textColors to counter(view, COUNTER_2)?.textColors
        }
        val density = view.resources.displayMetrics.density
        val color = burst?.color ?: DEFAULT_BURST_COLOR
        val mine = burst?.me == true
        val background = pillBackgrounds.getOrPut(view) {
            GradientDrawable().apply { cornerRadius = 6 * density }
        }
        background.setColor(withAlpha(color, if (mine) 0x4D else 0x33))
        if (mine) background.setStroke(maxOf(1, density.toInt()), color) else background.setStroke(0, 0)
        if (view.background !== background) setBackgroundKeepingPadding(view, background)
        view.isActivated = false
        view.isSelected = false
        val text = blend(color, Color.WHITE, 0.4f)
        counter(view, COUNTER_1)?.setTextColor(text)
        counter(view, COUNTER_2)?.setTextColor(text)
        view.contentDescription = if (mine) "Super reaction, reacted by you" else "Super reaction"
    }

    private fun restorePill(view: ReactionView) {
        if (!nativeBackgrounds.containsKey(view)) return
        val native = nativeBackgrounds[view]
        if (view.background !== native) setBackgroundKeepingPadding(view, native)
        restoreCounterColors(view)
        view.contentDescription = null
    }

    /**
     * Recycled views keep the burst tint on their counters: ReactionView only resets
     * them when it has its own color lists, which pills in reaction rows don't.
     */
    private fun restoreCounterColors(view: ReactionView) {
        val (first, second) = nativeCounterColors[view] ?: return
        val own = if (view.reaction?.c() == true) view.n else view.m
        (own ?: first)?.let { counter(view, COUNTER_1)?.setTextColor(it) }
        (own ?: second)?.let { counter(view, COUNTER_2)?.setTextColor(it) }
    }

    private fun setBackgroundKeepingPadding(view: View, background: Drawable?) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = background
        view.setPadding(left, top, right, bottom)
    }

    private fun counter(view: ReactionView, name: String): TextView? = view.findViewById(Utils.getResId(name, "id"))

    /** Re-binds visible reaction rows of [messageIds] so their pills reflect new data. */
    private fun refreshRows(messageIds: Collection<Long>) {
        if (messageIds.isEmpty()) return
        for ((item, row) in rows.entries.toList()) {
            if (row.entry.message.id in messageIds && item.itemView.isAttachedToWindow) {
                item.onConfigure(row.position, row.entry)
            }
        }
    }

    /**
     * Messages shown from Discord's cache were never loaded over REST in this
     * session. Fetch the visible window once (batched per channel) for those.
     */
    private fun requestSnapshot(channelId: Long, messageId: Long) {
        val attempts = snapshotAttempts[messageId] ?: 0
        if (attempts >= MAX_SNAPSHOT_ATTEMPTS) return
        snapshotAttempts[messageId] = attempts + 1
        val queue = snapshotQueue.getOrPut(channelId) { HashSet() }
        val schedule = queue.isEmpty()
        queue.add(messageId)
        if (schedule) mainHandler.postDelayed({ flushSnapshots(channelId) }, SNAPSHOT_DEBOUNCE_MS)
    }

    private fun flushSnapshots(channelId: Long) {
        val ids = snapshotQueue.remove(channelId)?.filter { !store.hasSnapshot(it) }?.sorted() ?: return
        if (ids.isEmpty()) return
        api?.messagesAround(channelId, ids[ids.size / 2]) { result ->
            if (!result.ok) return@messagesAround
            val changed = try {
                store.ingestMessages(JSONArray(result.body))
            } catch (_: Throwable) {
                emptyList()
            }
            refreshRows(changed)
        }
    }

    // endregion

    // region Adding and removing super reactions

    private fun patchReactionActions() {
        // Tapping a super pill toggles your super reaction instead of a normal one.
        patcher.patch(
            WidgetChatListAdapterEventsHandler.UserReactionHandler::class.java,
            "toggleReaction",
            arrayOf(LONG, LONG, LONG, MessageReaction::class.java),
            PreHook { frame ->
                val reaction = frame.args[3] as? MessageReaction ?: return@PreHook
                if (burstPills[reaction] != true) return@PreHook
                frame.result = null
                val emoji = reaction.b() ?: return@PreHook
                val channelId = frame.args[1] as Long
                val messageId = frame.args[2] as Long
                if (store.get(messageId, emoji.c())?.me == true) {
                    sendBurst(channelId, messageId, emoji, add = false)
                } else {
                    sendBurst(channelId, messageId, emoji, add = true)
                }
            },
        )
        // Emoji picker opened from the "+" at the end of a reaction row.
        patcher.patch(
            WidgetChatListAdapterEventsHandler.UserReactionHandler::class.java,
            "addNewReaction",
            arrayOf(Emoji::class.java, LONG, LONG),
            PreHook { frame ->
                if (!isSuperPickerActive()) return@PreHook
                frame.result = null
                pickedBurst(frame.args[1] as Long, frame.args[2] as Long, frame.args[0] as Emoji)
            },
        )
        // Emoji picker opened from the message actions sheet.
        patcher.patch(
            WidgetChatListActions::class.java,
            "addReaction",
            arrayOf(Emoji::class.java),
            PreHook { frame ->
                if (!isSuperPickerActive()) return@PreHook
                val actions = frame.thisObject as WidgetChatListActions
                val channelId = longField(actions, "channelId") ?: return@PreHook
                val messageId = longField(actions, "messageId") ?: return@PreHook
                frame.result = null
                pickedBurst(channelId, messageId, frame.args[0] as Emoji)
                actions.dismiss()
            },
        )
        // Removing yourself from a super reaction tab in the reactions sheet.
        patcher.patch(
            StoreMessageReactions::class.java,
            "deleteEmoji",
            arrayOf(LONG, LONG, MessageReactionEmoji::class.java, LONG),
            PreHook { frame ->
                val channelId = frame.args[0] as Long
                val messageId = frame.args[1] as Long
                val emoji = frame.args[2] as? MessageReactionEmoji ?: return@PreHook
                if (frame.args[3] != currentUserId()) return@PreHook
                val key = BurstStore.normalize(emoji.c())
                val onBurstTab = manageStates.values.any { it.messageId == messageId && it.burstKey == key }
                if (!onBurstTab) return@PreHook
                frame.result = null
                mainHandler.post { sendBurst(channelId, messageId, emoji, add = false) }
            },
        )
    }

    private fun pickedBurst(channelId: Long, messageId: Long, emoji: Emoji) {
        val key = emoji.reactionKey ?: return
        val custom = emoji as? ModelEmojiCustom
        val reactionEmoji = if (custom != null) {
            MessageReactionEmoji(custom.id.toString(), custom.name, custom.isAnimated)
        } else {
            MessageReactionEmoji(null, key, false)
        }
        if (store.get(messageId, reactionEmoji.c())?.me == true) {
            Utils.showToast("You already super reacted with this emoji")
            return
        }
        sendBurst(channelId, messageId, reactionEmoji, add = true)
    }

    private fun sendBurst(channelId: Long, messageId: Long, emoji: MessageReactionEmoji, add: Boolean) {
        val api = api ?: return
        if (add && !hasNitro()) {
            Utils.showToast("Super Reactions need Nitro")
            return
        }
        val key = emoji.c()
        val busyKey = "$messageId:${BurstStore.normalize(key)}"
        if (!inFlight.add(busyKey)) return
        val opKey = opKey(messageId, key, add)
        synchronized(ops) { ops[opKey] = Op.SENT }
        val done = { result: BurstApi.Result ->
            inFlight.remove(busyKey)
            if (result.ok) {
                applyLocally(channelId, messageId, emoji, add, opKey)
            } else {
                synchronized(ops) { ops.remove(opKey) }
                Utils.showToast(
                    when {
                        result.status == 429 -> "You're reacting too fast, try again in a moment"
                        add -> "Couldn't add the Super Reaction"
                        else -> "Couldn't remove the Super Reaction"
                    },
                )
            }
        }
        val apiKey = apiKey(emoji)
        if (add) api.addBurst(channelId, messageId, apiKey, done) else api.removeBurst(channelId, messageId, apiKey, done)
    }

    /**
     * Shows a successful change right away instead of waiting for the gateway
     * echo, which then gets dropped. If the echo already arrived, it was applied.
     */
    private fun applyLocally(channelId: Long, messageId: Long, emoji: MessageReactionEmoji, add: Boolean, opKey: String) {
        val apply = synchronized(ops) {
            if (ops[opKey] == Op.ECHOED) {
                ops.remove(opKey)
                false
            } else {
                ops[opKey] = Op.APPLIED
                true
            }
        }
        if (!apply) return
        store.apply(messageId, emoji.c(), add, byMe = true, color = null)
        invalidateBurstUsers(messageId)
        val update = MessageReactionUpdate(otherUserId(currentUserId()), channelId, messageId, emoji)
        STORE_COMPANION.dispatcherYesThisIsIntentional.schedule {
            StoreStream.getMessages().handleReactionUpdate(listOf(update), add)
        }
        mainHandler.postDelayed({
            synchronized(ops) { if (ops[opKey] == Op.APPLIED) ops.remove(opKey) }
        }, ECHO_TIMEOUT_MS)
    }

    // endregion

    // region Emoji picker toggle

    /** Adds Discord's super reaction toggle next to the search bar of reaction pickers. */
    private fun patchEmojiPicker() {
        val listenerField = WidgetEmojiPicker::class.java.getDeclaredField("emojiPickerListener").apply {
            isAccessible = true
        }
        val sheetDelegateField = WidgetEmojiPickerSheet::class.java.getDeclaredField("emojiPickerListenerDelegate").apply {
            isAccessible = true
        }
        patcher.patch(
            WidgetEmojiPicker::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { frame ->
                val picker = frame.thisObject as WidgetEmojiPicker
                val root = frame.args[0] as View
                superMode = false
                // The sheet sets its listener right after binding the picker view.
                root.post {
                    var listener = listenerField.get(picker) ?: return@post
                    // In the bottom sheet the picker's listener is the sheet, which forwards to its delegate.
                    if (listener is WidgetEmojiPickerSheet) listener = sheetDelegateField.get(listener) ?: return@post
                    if (listener.javaClass.name in REACTION_PICKER_LISTENERS && hasNitro()) addSuperButton(root)
                }
            },
        )
    }

    private fun addSuperButton(root: View) {
        val searchBar = root.findViewById<View>(Utils.getResId("emoji_search_bar", "id")) as? FrameLayout ?: return
        val toolbar = searchBar.parent as? ViewGroup ?: return
        if (toolbar is LinearLayout) return
        val context = root.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val index = toolbar.indexOfChild(searchBar)
        toolbar.removeView(searchBar)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            searchBar,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), dp(12), 0, dp(8))
            },
        )

        val smileyId = Utils.getResId("ic_reaction_24dp", "drawable")
        val smiley = (if (smileyId > 0) ContextCompat.getDrawable(context, smileyId) else null)?.mutate() ?: return
        val icon = SuperReactIcon(smiley)
        val backgroundId = Utils.getResId("drawable_bg_corners_4dp", "drawable")
        val button = ImageView(context).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (backgroundId > 0) background = ContextCompat.getDrawable(context, backgroundId)?.mutate()
            isClickable = true
            isFocusable = true
        }
        fun render() {
            val on = superMode
            button.background?.setTint(
                if (on) themeColor(context, "color_brand_560", BRAND) else themeColor(context, "colorBackgroundTertiary", Color.DKGRAY),
            )
            icon.setColor(if (on) Color.WHITE else themeColor(context, "colorInteractiveNormal", Color.LTGRAY))
            button.contentDescription = if (on) "Super Reaction on" else "Super Reaction off"
        }
        button.setOnClickListener {
            superMode = !superMode
            render()
        }
        render()
        row.addView(
            button,
            LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(8), dp(12), dp(12), dp(8))
            },
        )
        toolbar.addView(row, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pickerButton = WeakReference(button)
    }

    /** The toggle only applies while its picker is open, never to later quick reactions. */
    private fun isSuperPickerActive(): Boolean =
        superMode && pickerButton?.get()?.isAttachedToWindow == true

    // endregion

    // region Reactions sheet

    private fun patchManageReactions() {
        // Remember which pill opened the sheet, so a super pill opens its super tab.
        patcher.patch(
            WidgetManageReactions.Companion::class.java,
            "create",
            arrayOf(LONG, LONG, Context::class.java, MessageReaction::class.java),
            PreHook { frame ->
                val reaction = frame.args[3] as? MessageReaction
                val key = BurstStore.normalize(reaction?.b()?.c())
                pendingManageBurst = if (reaction != null && key != null && burstPills[reaction] == true) {
                    (frame.args[1] as Long) to key
                } else {
                    null
                }
            },
        )
        patcher.patch(
            WidgetManageReactions::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { frame ->
                val widget = frame.thisObject as WidgetManageReactions
                (adapterField(widget, "emojisAdapter") as? ManageReactionsEmojisAdapter)?.let {
                    manageAdapters[it] = WeakReference(widget)
                }
            },
        )
        patcher.patch(
            WidgetManageReactions::class.java,
            "configureUI",
            arrayOf(ManageReactionsModel::class.java),
            PreHook { frame ->
                val widget = frame.thisObject as WidgetManageReactions
                val model = frame.args[0] as? ManageReactionsModel ?: return@PreHook
                val intent = widget.mostRecentIntent
                val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0L)
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
                if (channelId == 0L || messageId == 0L) return@PreHook
                val state = manageStates.getOrPut(widget) {
                    ManageState(channelId, messageId).also { state ->
                        pendingManageBurst?.takeIf { it.first == messageId }?.let { state.burstKey = it.second }
                        pendingManageBurst = null
                    }
                }
                state.model = model
                frame.args[0] = buildManageModel(widget, state, model)
            },
        )
        // Tabs for one emoji share its key, so clicks are routed by tab kind.
        patcher.patch(
            ManageReactionsEmojisAdapter.ReactionEmojiViewHolder::class.java,
            "onConfigure",
            arrayOf(INT, ManageReactionsEmojisAdapter.ReactionEmojiItem::class.java),
            Hook { frame ->
                val holder = frame.thisObject as ManageReactionsEmojisAdapter.ReactionEmojiViewHolder
                val item = frame.args[1] as? ManageReactionsEmojisAdapter.ReactionEmojiItem ?: return@Hook
                val adapter = holder.adapter
                val widget = manageAdapters[adapter]?.get() ?: return@Hook
                val emoji = item.reaction.b() ?: return@Hook
                val key = BurstStore.normalize(emoji.c()) ?: return@Hook
                val isBurstTab = burstTabs[item] == true
                holder.itemView.setOnClickListener {
                    val state = manageStates[widget] ?: return@setOnClickListener
                    state.burstKey = if (isBurstTab) key else null
                    if (selectedKey(state.model) == key) {
                        refreshManage(widget, state)
                    } else {
                        state.awaiting = key
                        adapter.onEmojiSelectedListener?.invoke(emoji.c())
                    }
                }
            },
        )
    }

    private fun selectedKey(model: ManageReactionsModel?): String? =
        BurstStore.normalize(model?.reactionItems?.firstOrNull { it.isSelected }?.reaction?.b()?.c())

    private fun buildManageModel(
        widget: WidgetManageReactions,
        state: ManageState,
        model: ManageReactionsModel,
    ): ManageReactionsModel {
        val selected = selectedKey(model)
        if (state.awaiting != null && state.awaiting == selected) state.awaiting = null
        if (state.awaiting == null && state.burstKey != null && state.burstKey != selected) state.burstKey = null

        val tabs = ArrayList<ManageReactionsEmojisAdapter.ReactionEmojiItem>(model.reactionItems.size + 4)
        var selectedEmoji: MessageReactionEmoji? = null
        for (item in model.reactionItems) {
            val reaction = item.reaction
            val emoji = reaction.b() ?: continue
            val key = BurstStore.normalize(emoji.c())
            val bursts = store.get(state.messageId, key)?.count ?: 0
            if (bursts <= 0) {
                tabs.add(item)
                continue
            }
            val normal = reaction.a() - bursts
            // A reaction that's only super has no normal tab to select.
            if (item.isSelected && normal <= 0) state.burstKey = key
            if (item.isSelected) selectedEmoji = emoji
            val burstTab = ManageReactionsEmojisAdapter.ReactionEmojiItem(
                MessageReaction(bursts, emoji, false),
                item.isSelected && state.burstKey == key,
            )
            burstTabs.put(burstTab, true)
            tabs.add(burstTab)
            if (normal > 0) {
                tabs.add(
                    ManageReactionsEmojisAdapter.ReactionEmojiItem(
                        MessageReaction(normal, emoji, reaction.c()),
                        item.isSelected && state.burstKey != key,
                    ),
                )
            }
        }
        val burstKey = state.burstKey
        val users = if (burstKey != null && selectedEmoji != null) {
            burstUserItems(widget, state, burstKey, selectedEmoji)
        } else {
            model.userItems
        }
        return ManageReactionsModel(tabs, users)
    }

    private fun burstUserItems(
        widget: WidgetManageReactions,
        state: ManageState,
        key: String,
        emoji: MessageReactionEmoji,
    ): List<MGRecyclerDataPayload> {
        val cacheKey = "${state.messageId}:$key"
        val users = burstUsers[cacheKey]
        if (users == null) {
            if (burstUserFetches.add(cacheKey)) {
                val widgetRef = WeakReference(widget)
                api?.burstUsers(state.channelId, state.messageId, apiKey(emoji)) { result ->
                    burstUserFetches.remove(cacheKey)
                    if (!result.ok) return@burstUsers
                    burstUsers[cacheKey] = parseUsers(result.body)
                    val target = widgetRef.get() ?: return@burstUsers
                    manageStates[target]?.takeIf { it.burstKey == key }?.let { refreshManage(target, it) }
                }
            }
            return emptyList()
        }
        val me = currentUserId()
        return users.map {
            ManageReactionsResultsAdapter.ReactionUserItem(it, state.channelId, state.messageId, emoji, it.id == me, null)
        }
    }

    private fun refreshManage(widget: WidgetManageReactions, state: ManageState) {
        val model = state.model ?: return
        val built = buildManageModel(widget, state, model)
        (adapterField(widget, "emojisAdapter") as? ManageReactionsEmojisAdapter)?.setData(built.reactionItems)
        (adapterField(widget, "resultsAdapter") as? ManageReactionsResultsAdapter)?.setData(built.userItems)
    }

    private fun invalidateBurstUsers(messageId: Long) {
        mainHandler.post {
            val prefix = "$messageId:"
            val iterator = burstUsers.keys.iterator()
            while (iterator.hasNext()) if (iterator.next().startsWith(prefix)) iterator.remove()
            for ((widget, state) in manageStates.entries.toList()) {
                if (state.messageId == messageId) refreshManage(widget, state)
            }
        }
    }

    private fun parseUsers(body: String): List<User> {
        val users = ArrayList<User>()
        val array = try {
            JSONArray(body)
        } catch (_: Throwable) {
            return users
        }
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val id = raw.optString("id").toLongOrNull() ?: continue
            users.add(
                CoreUser(
                    id,
                    raw.optString("username").takeIf { it.isNotEmpty() } ?: "Unknown user",
                    raw.optString("avatar").takeIf { it.isNotEmpty() && it != "null" },
                    raw.optString("banner").takeIf { it.isNotEmpty() && it != "null" },
                    raw.optBoolean("bot"),
                    raw.optBoolean("system"),
                    raw.optString("discriminator").toIntOrNull() ?: 0,
                    PremiumTier.NONE,
                    raw.optInt("flags"),
                    raw.optInt("public_flags"),
                    null,
                    null,
                ),
            )
        }
        return users
    }

    private fun adapterField(widget: WidgetManageReactions, name: String): Any? = try {
        WidgetManageReactions::class.java.getDeclaredField(name).apply { isAccessible = true }.get(widget)
    } catch (_: Throwable) {
        null
    }

    // endregion

    // region Helpers

    private fun opKey(messageId: Long, key: String?, add: Boolean) =
        "$messageId:${BurstStore.normalize(key)}:${if (add) "add" else "remove"}"

    /** The key the REST API expects: `name:id` for custom emojis, the character otherwise. */
    private fun apiKey(emoji: MessageReactionEmoji): String =
        if (emoji.e() && emoji.b() != null) "${emoji.d() ?: "_"}:${emoji.b()}" else emoji.d() ?: emoji.c()

    private fun longField(target: Any, name: String): Long? = try {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(target).takeIf { it != 0L }
    } catch (_: Throwable) {
        null
    }

    private fun hasNitro(): Boolean = try {
        UserUtils.INSTANCE.isPremium(StoreStream.getUsers().me)
    } catch (_: Throwable) {
        false
    }

    private fun currentUserId(): Long = try {
        StoreStream.getUsers().me.id
    } catch (_: Throwable) {
        0L
    }

    /** Any id that isn't ours, so Discord counts the update without setting `me`. */
    private fun otherUserId(me: Long) = if (me == 1L) 2L else 1L

    private fun themeColor(context: Context, attr: String, fallback: Int): Int {
        val id = Utils.getResId(attr, "attr")
        if (id == 0) return fallback
        val value = TypedValue()
        if (!context.theme.resolveAttribute(id, value, true)) return fallback
        return when {
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
            else -> fallback
        }
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(color: Int, target: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (Color.red(target) - Color.red(color)) * amount).toInt(),
        (Color.green(color) + (Color.green(target) - Color.green(color)) * amount).toInt(),
        (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * amount).toInt(),
    )

    // endregion

    override fun stop(context: Context) {
        patcher.unpatchAll()
        api?.close()
        api = null
        mainHandler.removeCallbacksAndMessages(null)
        for ((view, native) in nativeBackgrounds.entries.toList()) {
            setBackgroundKeepingPadding(view, native)
            restoreCounterColors(view)
            view.contentDescription = null
        }
        nativeBackgrounds.clear()
        nativeCounterColors.clear()
        pillBackgrounds.clear()
        // Collapse split pills back to Discord's single reactions.
        for ((item, row) in rows.entries.toList()) {
            if (item.itemView.isAttachedToWindow) item.onConfigure(row.position, row.entry)
        }
        rows.clear()
        pickerButton?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pickerButton = null
        superMode = false
        store.clearAll()
        burstPills.clear()
        gatewayEvents.clear()
        burstTabs.clear()
        snapshotQueue.clear()
        snapshotAttempts.clear()
        inFlight.clear()
        manageStates.clear()
        manageAdapters.clear()
        burstUsers.clear()
        burstUserFetches.clear()
        pendingManageBurst = null
        synchronized(ops) { ops.clear() }
    }

    private companion object {
        val LONG = Long::class.javaPrimitiveType!!
        val INT = Int::class.javaPrimitiveType!!
        val BOOLEAN = Boolean::class.javaPrimitiveType!!
        const val COUNTER_1 = "counter_text_1"
        const val COUNTER_2 = "counter_text_2"
        const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        const val SNAPSHOT_DEBOUNCE_MS = 400L
        const val MAX_SNAPSHOT_ATTEMPTS = 2
        const val ECHO_TIMEOUT_MS = 60_000L
        val BRAND = Color.rgb(88, 101, 242)
        val DEFAULT_BURST_COLOR = Color.rgb(255, 196, 61)
        val MESSAGES_PATH: Pattern = Pattern.compile(".*/channels/\\d+/messages")

        /** okio Buffer.clone(); Kotlin resolves `clone()` to the protected Cloneable one. */
        val BUFFER_CLONE: java.lang.reflect.Method = g0.e::class.java.getMethod("clone")

        /** Kotlin resolves `StoreStream.Companion` to the nested class, not the static field. */
        val STORE_COMPANION: StoreStream.Companion by lazy {
            StoreStream::class.java.getField("Companion").get(null) as StoreStream.Companion
        }
        val REACTION_PICKER_LISTENERS = setOf(
            "com.discord.widgets.chat.list.actions.WidgetChatListActions\$onViewCreated\$2\$1",
            "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler\$onQuickAddReactionClicked\$1\$1",
        )
    }
}
