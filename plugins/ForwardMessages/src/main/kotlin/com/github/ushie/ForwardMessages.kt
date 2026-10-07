package com.github.ushie

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.discord.databinding.WidgetIncomingShareBinding
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.intent.IntentUtils
import com.discord.utilities.time.Clock
import com.discord.widgets.chat.list.ViewEmbedGameInvite
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.share.WidgetIncomingShare
import com.discord.widgets.user.search.ViewGlobalSearchItem
import com.discord.widgets.user.search.WidgetGlobalSearchAdapter
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import com.google.android.material.appbar.AppBarLayout
import com.lytefast.flexinput.R
import java.util.Collections
import java.util.Random
import java.util.WeakHashMap
import b.i.d.p.b as SerializedName

@AliucordPlugin(requiresRestart = true)
class ForwardMessages : Plugin() {
    private val favorites = linkedMapOf<Long, String>()
    private val favoriteServers = linkedMapOf<Long, String>()
    private val originalPositions = linkedMapOf<Long, Int>()
    private lateinit var preferences: SharedPreferences
    private var activeShare: WidgetIncomingShare? = null
    private var activeResultsRecyclerView: RecyclerView? = null
    private val listeners = WeakHashMap<RecyclerView, RecyclerView.OnItemTouchListener>()
    private val changedRows = Collections.newSetFromMap(WeakHashMap<ViewGlobalSearchItem, Boolean>())
    private val sending = Collections.newSetFromMap(WeakHashMap<WidgetIncomingShare, Boolean>())
    private val forwardButtons = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private var lastFavoriteActionId = 0L
    private var lastFavoriteActionAt = 0L

    @Volatile private var running = false

    override fun start(context: Context) {
        running = true
        preferences = context.getSharedPreferences("ForwardMessages", Context.MODE_PRIVATE)
        loadFavorites()
        val forwardId = View.generateViewId()
        val bindingMethod = WidgetIncomingShare::class.java
            .getDeclaredMethod(
                "getBinding",
            ).apply { isAccessible = true }
        val commentField = WidgetIncomingShare.Model::class.java
            .getDeclaredField(
                "comment",
            ).apply { isAccessible = true }

        fun binding(share: WidgetIncomingShare) = bindingMethod.invoke(share) as WidgetIncomingShareBinding

        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            PreHook { param ->
                val actions = param.thisObject as WidgetChatListActions
                val layout = (actions.view as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return@PreHook
                val model = param.args[0] as WidgetChatListActions.Model
                val existing = layout.findViewById<TextView>(forwardId)
                val button = existing ?: TextView(layout.context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = forwardId
                    text = "Forward"
                    val replyId = Utils.getResId("dialog_chat_actions_reply", "id")
                    val reply = layout.findViewById<TextView>(replyId)
                    val icon = ContextCompat.getDrawable(context, R.e.ic_reply_24dp)?.mutate()
                    if (icon != null) {
                        icon.isAutoMirrored = true
                        val colorId = Utils.getResId("colorInteractiveNormal", "attr")
                        if (colorId !=
                            0
                        ) {
                            DrawableCompat.setTint(icon, ColorCompat.getThemedColor(this.context, colorId))
                        }
                        setCompoundDrawablesRelativeWithIntrinsicBounds(MirroredDrawable(icon), null, null, null)
                    }
                    if (reply != null) {
                        setTextColor(reply.textColors)
                        TextViewCompat.setCompoundDrawableTintList(
                            this,
                            TextViewCompat.getCompoundDrawableTintList(reply),
                        )
                    }
                    val index = reply?.let { layout.indexOfChild(it) }?.takeIf { it >= 0 }
                    layout.addView(this, index?.plus(1) ?: minOf(5, layout.childCount))
                    forwardButtons.add(this)
                }
                // Rebind the callback each time the menu is configured for another message.
                button.setOnClickListener {
                    val intent = Intent()
                        .putExtra(MESSAGE_CONTENT, model.message.content)
                        .putExtra(MESSAGE_ID, model.message.id)
                        .putExtra(CHANNEL_ID, model.channel.k())
                    Utils.openPage(Utils.appActivity, WidgetIncomingShare::class.java, intent)
                    actions.dismiss()
                }
            },
        )

        patcher.patch(
            WidgetIncomingShare::class.java.getDeclaredMethod(
                "initialize",
                WidgetIncomingShare.ContentModel::class.java,
            ),
            Hook { param ->
                val share = param.thisObject as WidgetIncomingShare
                if (!isForward(share)) return@Hook
                val binding = binding(share)
                activate(share, binding.h)
                val appBar = binding.a.getChildAt(0) as? AppBarLayout
                (appBar?.getChildAt(0) as? Toolbar)?.title = "Forward"
                val layout = binding.j.getChildAt(0) as? LinearLayout ?: return@Hook
                if (layout.findViewWithTag<View>(PREVIEW) == null) {
                    (layout.getChildAt(4) as? TextView)?.text = "Forward To"
                    (layout.getChildAt(0) as? TextView)?.text = "Optional Message"
                    val header = TextView(layout.context, null, 0, R.i.UiKit_Search_Header).apply {
                        text = "Message Preview"
                        tag = PREVIEW
                    }
                    val preview = TextView(layout.context, null, 0, R.i.UiKit_TextAppearance).apply {
                        text = share.mostRecentIntent.getStringExtra(MESSAGE_CONTENT)
                        setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(2), 0, 0)
                    }
                    layout.addView(header, 0)
                    layout.addView(preview, 1)
                }
                installFavoriteLongPress(binding.h)
            },
        )

        patcher.patch(
            WidgetIncomingShare::class.java.getDeclaredMethod(
                "onSendClicked",
                Context::class.java,
                WidgetGlobalSearchModel.ItemDataPayload::class.java,
                ViewEmbedGameInvite.Model::class.java,
                WidgetIncomingShare.ContentModel::class.java,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                CaptchaHelper.CaptchaPayload::class.java,
            ),
            PreHook { param ->
                val share = param.thisObject as WidgetIncomingShare
                if (!isForward(share)) return@PreHook
                val receiver = param.args[1] as? WidgetGlobalSearchModel.ItemDataPayload ?: return@PreHook
                val destination = getChannelId(receiver)
                if (destination == 0L) return@PreHook
                val intent = share.mostRecentIntent
                val comment = binding(share).d.editText?.text?.toString().orEmpty()
                if (!sending.add(share)) {
                    param.result = null
                    return@PreHook
                }
                val reference =
                    MessageReference(1, intent.getLongExtra(MESSAGE_ID, 0), intent.getLongExtra(CHANNEL_ID, 0))
                Utils.threadPool.execute {
                    try {
                        Http.Request.newDiscordRNRequest("/channels/$destination/messages", "POST").use {
                            val response = it.executeWithJson(Message(reference, ""))
                            check(response.ok()) { "HTTP ${response.statusCode}" }
                        }
                        if (comment.isNotEmpty()) {
                            try {
                                Http.Request.newDiscordRNRequest("/channels/$destination/messages", "POST").use {
                                    val response = it.executeWithJson(Message(null, comment))
                                    check(response.ok()) { "HTTP ${response.statusCode}" }
                                }
                            } catch (error: Throwable) {
                                logger.error("Forwarded message, but could not send comment", error)
                                Utils.mainThread.post {
                                    Utils.showToast(
                                        "Message forwarded; the optional message failed",
                                    )
                                }
                            }
                        }
                        Utils.mainThread.post {
                            if (running && share.isAdded) {
                                share.startActivity(
                                    IntentUtils.RouteBuilders
                                        .selectChannel(destination, 0, null)
                                        .setPackage(Utils.appContext.packageName),
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        logger.error("Forwarding failed", error)
                        Utils.mainThread.post { Utils.showToast("Forwarding failed: ${error.message}") }
                    } finally {
                        Utils.mainThread.post { sending.remove(share) }
                    }
                }
                param.result = null
            },
        )

        patcher.patch(
            WidgetGlobalSearchAdapter::class.java.getMethod("setData", List::class.java),
            PreHook { param ->
                if (activeShare?.let(::isForward) == true && param.thisObject == activeResultsRecyclerView?.adapter) {
                    val data = param.args[0] as? List<*> ?: return@PreHook
                    rememberOriginalPositions(data)
                    param.args[0] = reorderFavorites(data)
                }
            },
        )
        for (holderClass in listOf(
            WidgetGlobalSearchAdapter.ChannelViewHolder::class.java,
            WidgetGlobalSearchAdapter.UserViewHolder::class.java,
            WidgetGlobalSearchAdapter.GuildViewHolder::class.java,
        )) {
            patcher.patch(
                holderClass.getDeclaredMethod(
                    "onConfigure",
                    Int::class.javaPrimitiveType!!,
                    WidgetGlobalSearchModel.ItemDataPayload::class.java,
                ),
                Hook { param ->
                    val holder = param.thisObject as WidgetGlobalSearchAdapter.SearchViewHolder
                    val row = holder.viewGlobalSearchItem
                    val payload = param.args[1] as? WidgetGlobalSearchModel.ItemDataPayload
                    val recycler = activeResultsRecyclerView
                    if (activeShare?.let(::isForward) == true &&
                        recycler != null &&
                        holder.adapter === recycler.adapter
                    ) {
                        configureFavoriteRow(row, payload)
                    } else if (row in changedRows) {
                        resetFavoriteRow(row)
                    }
                },
            )
        }
        patcher.patch(
            WidgetIncomingShare::class.java.getDeclaredMethod(
                "configureUi",
                WidgetIncomingShare.Model::class.java,
                Clock::class.java,
            ),
            PreHook { param ->
                val share = param.thisObject as WidgetIncomingShare
                if (!isForward(share)) return@PreHook
                commentField.set(param.args[0], "...")
                val binding = binding(share)
                activate(share, binding.h)
                installFavoriteLongPress(binding.h)
            },
        )
        patcher.patch(
            WidgetIncomingShare::class.java.getMethod("onDestroyView"),
            Hook { param ->
                if (param.thisObject == activeShare) clearActiveShare()
            },
        )
    }

    private fun isForward(share: WidgetIncomingShare): Boolean = share.mostRecentIntent.let {
        it.getLongExtra(MESSAGE_ID, 0) != 0L && it.getLongExtra(CHANNEL_ID, 0) != 0L
    }

    private fun activate(share: WidgetIncomingShare, recycler: RecyclerView) {
        if (activeShare !== share || activeResultsRecyclerView !== recycler) {
            clearActiveShare()
            originalPositions.clear()
        }
        activeShare = share
        activeResultsRecyclerView = recycler
    }

    private fun clearActiveShare() {
        activeResultsRecyclerView?.let { recycler ->
            listeners.remove(recycler)?.let { recycler.removeOnItemTouchListener(it) }
        }
        changedRows.toList().forEach(::resetFavoriteRow)
        changedRows.clear()
        activeShare = null
        activeResultsRecyclerView = null
        originalPositions.clear()
    }

    private fun loadFavorites() {
        favorites.clear()
        favoriteServers.clear()
        for (entry in preferences.getString(FAVORITES, "").orEmpty().split('\n')) {
            val fields = entry.split('|')
            val id = fields.firstOrNull()?.toLongOrNull() ?: continue
            if (fields.size < 2) continue
            favorites[id] = fields[1]
            favoriteServers[id] = fields.getOrElse(2) { "" }
        }
    }

    private fun saveFavorites() {
        fun clean(value: String) = value.replace("\n", " ").replace("|", " ")
        val serialized = favorites.entries.joinToString("\n") {
            "${it.key}|${clean(it.value)}|${clean(favoriteServers[it.key].orEmpty())}"
        }
        preferences.edit().putString(FAVORITES, serialized).apply()
    }

    private fun configureFavoriteRow(row: ViewGlobalSearchItem, payload: WidgetGlobalSearchModel.ItemDataPayload?) {
        changedRows.add(row)
        val channelId = getChannelId(payload)
        val favorite = channelId in favorites
        var star = row.findViewWithTag<ImageButton>(FAVORITE_STAR)
        if (favorite && star == null) {
            star = ImageButton(row.context).apply {
                tag = FAVORITE_STAR
                id = View.generateViewId()
                setImageResource(R.e.abc_ic_star_black_16dp)
                imageTintList = ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.b.colorInteractiveNormal))
                background = null
                setPadding(0, 0, 0, 0)
                contentDescription = "Remove from Favorites"
            }
            row.addView(
                star,
                ConstraintLayout.LayoutParams(DimenUtils.dpToPx(20), DimenUtils.dpToPx(20)).apply {
                    endToStart = Utils.getResId("item_mentions_tv", "id")
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    marginEnd = DimenUtils.dpToPx(4)
                },
            )
        }
        star?.visibility = if (favorite) View.VISIBLE else View.GONE
        star?.setOnClickListener(if (favorite) View.OnClickListener { favoritePayload(row.context, payload) } else null)
        if (star != null) {
            row.findViewById<TextView>(Utils.getResId("item_group_tv", "id"))?.let { server ->
                val params = server.layoutParams as ConstraintLayout.LayoutParams
                val target = if (favorite) star.id else Utils.getResId("item_mentions_tv", "id")
                params.endToStart = target
                params.rightToLeft = target
                params.marginEnd = DimenUtils.dpToPx(if (favorite) 4 else 8)
                server.layoutParams = params
            }
        }
        installFavoriteChildLongPress(row, payload)
    }

    private fun resetFavoriteRow(row: ViewGlobalSearchItem) {
        row.findViewWithTag<View>(FAVORITE_STAR)?.let { star ->
            row.removeView(star)
            row.findViewById<View>(Utils.getResId("item_group_tv", "id"))?.let { server ->
                val params = server.layoutParams as ConstraintLayout.LayoutParams
                val target = Utils.getResId("item_mentions_tv", "id")
                params.endToStart = target
                params.rightToLeft = target
                params.marginEnd = DimenUtils.dpToPx(8)
                server.layoutParams = params
            }
        }
        for (name in listOf("item_icon_iv", "item_name_tv", "item_group_tv")) {
            row.findViewById<View>(Utils.getResId(name, "id"))?.apply {
                setOnLongClickListener(null)
                setOnClickListener(null)
            }
        }
    }

    private fun installFavoriteLongPress(recycler: RecyclerView) {
        if (listeners.containsKey(recycler)) return
        val detector = GestureDetector(
            recycler.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(event: MotionEvent) {
                    val child = recycler.findChildViewUnder(event.x, event.y) ?: return
                    val payload = getAdapterItem(recycler, recycler.getChildAdapterPosition(child)) ?: return
                    if (getGuildName(payload).isEmpty()) {
                        val name = child.findViewById<View>(Utils.getResId("item_name_tv", "id"))
                        if (name != null && name.visibility == View.VISIBLE) {
                            val hit = Rect()
                            name.getHitRect(hit)
                            hit.offset(child.left, child.top)
                            if (hit.contains(event.x.toInt(), event.y.toInt())) return
                        }
                    }
                    favoritePayload(recycler.context, payload)
                }
            },
        )
        val listener = object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                detector.onTouchEvent(event)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
                detector.onTouchEvent(event)
            }
        }
        listeners[recycler] = listener
        recycler.addOnItemTouchListener(listener)
    }

    private fun installFavoriteChildLongPress(
        row: ViewGlobalSearchItem,
        payload: WidgetGlobalSearchModel.ItemDataPayload?,
    ) {
        row.findViewById<View>(Utils.getResId("item_icon_iv", "id"))?.apply {
            setOnLongClickListener { favoritePayload(context, payload) }
            setOnClickListener { row.performClick() }
        }
        val isDm = getGuildName(payload).isEmpty()
        for (name in listOf("item_name_tv", "item_group_tv")) {
            row.findViewById<View>(Utils.getResId(name, "id"))?.apply {
                setOnLongClickListener(
                    if (isDm) null else View.OnLongClickListener { favoritePayload(context, payload) },
                )
                setOnClickListener(if (isDm) null else View.OnClickListener { row.performClick() })
            }
        }
    }

    private fun rememberOriginalPositions(source: List<*>) {
        for ((index, value) in source.withIndex()) {
            val id = getChannelId(value)
            if (id != 0L) originalPositions.putIfAbsent(id, index)
        }
    }

    private fun reorderFavorites(source: List<*>): List<WidgetGlobalSearchModel.ItemDataPayload> {
        val remaining = mutableListOf<WidgetGlobalSearchModel.ItemDataPayload>()
        val favoriteItems = linkedMapOf<Long, WidgetGlobalSearchModel.ItemDataPayload>()
        for (value in source.filterIsInstance<WidgetGlobalSearchModel.ItemDataPayload>()) {
            val id = getChannelId(value)
            if (id in favorites) favoriteItems[id] = value else remaining += value
        }
        val indexes = ArrayList<Int>()
        for (i in 0 until remaining.size) if (getChannelId(remaining[i]) != 0L) indexes += i
        val sorted = indexes.map { remaining[it] }.sortedBy { originalPositions[getChannelId(it)] ?: Int.MAX_VALUE }
        indexes.forEachIndexed { index, position -> remaining[position] = sorted[index] }
        var headerCount = 0
        val secondHeader = remaining.indexOfFirst { it is WidgetGlobalSearchModel.ItemHeader && ++headerCount == 2 }
        val insertion = if (secondHeader >= 0) secondHeader + 1 else minOf(1, remaining.size)
        remaining.addAll(insertion, favorites.keys.mapNotNull { favoriteItems[it] })
        return remaining
    }

    private fun reorderCurrentResults() {
        val recycler = activeResultsRecyclerView ?: return
        val adapter = recycler.adapter ?: return
        try {
            val data = adapter.javaClass.getMethod("getInternalData").invoke(adapter) as? List<*> ?: return
            adapter.javaClass.getMethod("setData", List::class.java).invoke(adapter, reorderFavorites(data))
        } catch (error: ReflectiveOperationException) {
            logger.error("Could not reorder favorite channels", error)
        }
        adapter.notifyDataSetChanged()
    }

    private fun favoritePayload(context: Context, payload: Any?): Boolean {
        val id = getChannelId(payload)
        if (id == 0L) return false
        val now = SystemClock.uptimeMillis()
        if (id == lastFavoriteActionId && now - lastFavoriteActionAt < 750) return true
        lastFavoriteActionId = id
        lastFavoriteActionAt = now
        val name = getChannelName(payload).ifEmpty { "Channel $id" }
        val removed = favorites.remove(id) != null
        if (removed) {
            favoriteServers.remove(id)
        } else {
            favorites[id] = name
            favoriteServers[id] = getGuildName(payload)
        }
        saveFavorites()
        reorderCurrentResults()
        Utils.showToast("$name ${if (removed) "removed from" else "added to"} Favorites")
        return true
    }

    private fun getAdapterItem(recycler: RecyclerView, position: Int): Any? {
        if (position == RecyclerView.NO_POSITION) return null
        val adapter = recycler.adapter ?: return null
        for (name in listOf("getItem", "getData", "getItemAt")) {
            try {
                return adapter.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(adapter, position)
            } catch (
                _: ReflectiveOperationException,
            ) {
            }
        }
        return null
    }

    private fun getChannelId(payload: Any?): Long = try {
        val channel = payload?.javaClass?.getMethod("getChannel")?.invoke(payload)
        (channel?.javaClass?.getMethod("k")?.invoke(channel) as? Number)?.toLong() ?: 0L
    } catch (_: ReflectiveOperationException) {
        0L
    }

    private fun getChannelName(payload: Any?): String {
        try {
            val channel = payload?.javaClass?.getMethod("getChannel")?.invoke(payload) ?: return ""
            for (name in listOf("getName", "p")) {
                try {
                    val value = channel.javaClass.getMethod(name).invoke(channel)?.toString().orEmpty()
                    if (value.isNotEmpty()) return value
                } catch (_: ReflectiveOperationException) {
                }
            }
        } catch (_: ReflectiveOperationException) {
        }
        return ""
    }

    private fun getGuildName(payload: Any?): String = try {
        val guild = payload?.javaClass?.getMethod("getGuild")?.invoke(payload)
        guild?.javaClass?.getMethod("getName")?.invoke(guild)?.toString().orEmpty()
    } catch (_: ReflectiveOperationException) {
        ""
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        clearActiveShare()
        listeners.entries.toList().forEach { (recycler, listener) -> recycler.removeOnItemTouchListener(listener) }
        listeners.clear()
        forwardButtons.toList().forEach { (it.parent as? LinearLayout)?.removeView(it) }
        forwardButtons.clear()
        sending.clear()
    }

    class MessageReference(
        val type: Int,
        @SerializedName("message_id") val messageId: Long,
        @SerializedName("channel_id") val channelId: Long,
        @SerializedName("guild_id") val guildId: Long? = null,
        @SerializedName("fail_if_not_exists") val failIfNotExists: Boolean = false,
    )

    fun nextBits(rng: Random, bits: Int): Int {
        require(bits in 0..32) { "bits must be 0..32" }
        return when (bits) {
            0 -> 0
            32 -> rng.nextInt()
            else -> rng.nextInt() and ((1 shl bits) - 1)
        }
    }

    fun nextBits(bits: Int) = nextBits(Random(), bits)

    @SuppressLint("MissingPermission")
    inner class Message(
        @SerializedName("message_reference") val messageReference: MessageReference?,
        val content: String,
    ) {
        val flags = 0
        val tts = false
        val nonce = (SnowflakeUtils.fromTimestamp(System.currentTimeMillis()) + nextBits(23)).toString()

        @SerializedName("mobile_network_type")
        var mobileNetworkType = "unknown"

        @SerializedName("signal_strength")
        var signalStrength = 0

        init {
            val context = Utils.appContext
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (Build.VERSION.SDK_INT >= 23) {
                val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                mobileNetworkType = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    else -> "unknown"
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        signalStrength = telephony?.signalStrength?.level ?: 0
                    } catch (_: SecurityException) {
                    }
                }
            }
        }
    }

    companion object {
        private const val FAVORITES = "forward_message_favorites"
        private const val FAVORITE_STAR = "forward_message_favorite_star"
        private const val PREVIEW = "forward_message_preview"
        private const val MESSAGE_CONTENT = "io.gh.reisxd.aliuplugins.MESSAGE_CONTENT"
        private const val MESSAGE_ID = "io.gh.reisxd.aliuplugins.MESSAGE_ID"
        private const val CHANNEL_ID = "io.gh.reisxd.aliuplugins.CHANNEL_ID"
    }
}
