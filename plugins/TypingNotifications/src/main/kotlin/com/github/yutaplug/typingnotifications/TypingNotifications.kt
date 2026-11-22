package com.github.yutaplug.typingnotifications

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.users.globalName
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelUtils
import com.discord.api.user.TypingUser
import com.discord.api.message.Message
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.app.AppComponent
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserTyping
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.intent.IntentUtils
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.notice.NoticePopup
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.WidgetUserMentions
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import android.text.SpannableStringBuilder
import b.a.k.b as FormatUtils
import b.p.a.b as Alert
import d0.e0.c as KClass
import d0.z.d.a0 as Reflection

@AliucordPlugin
class TypingNotifications : Plugin() {
    /** When each user last sent TYPING_START in each channel, keyed by "channel:user". */
    private val lastTyping = ConcurrentHashMap<String, Long>()

    /** Names of this plugin's in-app banners that may still be queued or on screen. Main thread only. */
    private val shownBanners = mutableListOf<String>()
    private var bannerCount = 0L

    /** Everyone currently typing who passed the filters, oldest first, keyed like [lastTyping]. Main thread only. */
    private val activeTypers = LinkedHashMap<String, Typer>()

    private val compactBanner = CompactBanner { action, error -> logger.error("Failed to $action", error) }

    /** Discord's typing banner on screen, updated in place while it stays visible. Main thread only. */
    private var currentAlert: WeakReference<Alert>? = null

    /** Where tapping the banner goes: the newest typer at the last update. */
    private var bannerTarget: Typer? = null

    /** Set once the user touches the banner, which (like Discord's own) stops it hiding by itself. */
    private var bannerTouched = false
    private val hideBanner = Runnable {
        val alert = currentAlert?.get()
        currentAlert = null
        bannerTitle = null
        if (alert != null && isShowing(alert)) alert.d()
    }

    /** The title of the latest Discord banner this plugin requested, used to find it on screen. */
    private var bannerTitle: String? = null

    /** Every main tab (home, friends, search, mentions, profile), not just the home tab like Aliucord's default. */
    @Suppress("UNCHECKED_CAST")
    private val bannerScreens = listOf(WidgetTabsHost::class.java, WidgetHome::class.java, WidgetUserMentions::class.java)
        .map { Reflection.getOrCreateKotlinClass(it) as KClass<out AppComponent> }

    init {
        settingsTab = SettingsTab(TypingNotificationsSettings::class.java)
        instance = this
    }

    /**
     * The screen in front. Aliucord's Utils.appActivity only changes when a screen is created, so after
     * leaving a screen opened on top (such as plugin settings) it still points at the closed one.
     */
    private var resumedActivity: WeakReference<Activity>? = null
    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumedActivity?.get() === activity) resumedActivity = null
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    private fun usable(activity: Activity?) = activity?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun currentActivity(): Activity? = usable(resumedActivity?.get()) ?: usable(Utils.appActivity)

    override fun start(context: Context) {
        resumedActivity = Utils.appActivity?.let { WeakReference(it) }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityTracker)
        patcher.patch(
            StoreUserTyping::class.java,
            "handleTypingStart",
            arrayOf(TypingUser::class.java),
            Hook { frame ->
                try {
                    onTyping(frame.args[0] as TypingUser)
                } catch (error: Throwable) {
                    logger.error("Failed to send typing notification", error)
                }
            },
        )
        // Someone who sent a message and starts typing again counts as starting to type.
        patcher.patch(
            StoreUserTyping::class.java,
            "handleMessageCreate",
            arrayOf(Message::class.java),
            Hook { frame ->
                val message = frame.args[0] as Message
                val author = message.e() ?: return@Hook
                val key = "${message.g()}:${author.getId()}"
                lastTyping.remove(key)
                Utils.mainThread.post { activeTypers.remove(key) }
            },
        )
        patchBannerShown()
        ContextMenus(settings) { action, error -> logger.error("Failed to $action", error) }.patch(patcher)
    }

    /** Remembers the Alert view Discord builds for our latest banner so later updates can edit it. */
    private fun patchBannerShown() {
        val showClass = Class.forName("com.discord.widgets.notice.NoticePopup\$enqueue\$notice\$1")
        val nameField = showClass.getDeclaredField("\$noticeName").apply { isAccessible = true }
        patcher.patch(
            showClass,
            "invoke2",
            arrayOf(FragmentActivity::class.java),
            Hook { frame ->
                if (nameField.get(frame.thisObject) != shownBanners.lastOrNull()) return@Hook
                logger.debug("Discord typing banner shown (result ${frame.result})")
                // Discord adds the Alert to the window in a posted runnable; this one runs after it.
                Utils.mainThread.post { findAndTrackBanner(frame.args[0] as? Activity) }
            },
        )
    }

    /**
     * Finds Discord's banner showing [bannerTitle] and starts tracking it. Matching by title (instead of
     * trusting the show hook alone) still works if the hook misses, and never grabs Discord's own banners.
     */
    private fun findAndTrackBanner(activity: Activity? = currentActivity()): Alert? {
        try {
            val tracked = currentAlert?.get()
            if (tracked != null && isShowing(tracked)) return tracked
            val title = bannerTitle ?: return null
            val decor = activity?.window?.decorView as? ViewGroup ?: return null
            val titleId = Utils.getResId("popup_title", "id")
            // A plain loop: iterating an IntRange breaks on Discord's obfuscated stdlib.
            var index = decor.childCount - 1
            while (index >= 0) {
                val alert = decor.getChildAt(index) as? Alert
                index--
                if (alert == null || !isShowing(alert)) continue
                val shown = alert.layoutContainer?.findViewById<TextView>(titleId)?.text?.toString()
                if (shown != title) continue
                currentAlert = WeakReference(alert)
                bannerTouched = false
                watchTouches(alert)
                scheduleHide()
                logger.debug("Tracking Discord typing banner")
                return alert
            }
        } catch (error: Throwable) {
            logger.error("Failed to track typing banner", error)
        }
        return null
    }

    /**
     * Discord's queue shows a new banner up to a second or more after it is requested. Until it appears,
     * keep looking for it so the hide timer always starts (Discord's own timer is off for these banners).
     */
    private val watchForBanner = object : Runnable {
        var tries = 0

        override fun run() {
            if (bannerTitle == null || findAndTrackBanner() != null || ++tries > WATCH_TRIES) return
            Utils.mainThread.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    private fun startWatchingForBanner() {
        Utils.mainThread.removeCallbacks(watchForBanner)
        watchForBanner.tries = 0
        Utils.mainThread.postDelayed(watchForBanner, WATCH_INTERVAL_MS)
    }

    /** Keeps Discord's touch handling, and stops the banner hiding by itself once touched. */
    private fun watchTouches(alert: Alert) {
        val container = alert.layoutContainer ?: return
        val original = runCatching {
            Class.forName("com.discord.widgets.notice.NoticePopup\$enqueue\$notice\$1\$3\$1")
                .getConstructor(ValueAnimator::class.java)
                .newInstance(null) as View.OnTouchListener
        }.getOrNull()
        container.setOnTouchListener { view, event ->
            bannerTouched = true
            Utils.mainThread.removeCallbacks(hideBanner)
            original?.onTouch(view, event) ?: false
        }
    }

    private fun scheduleHide() {
        Utils.mainThread.removeCallbacks(hideBanner)
        if (!bannerTouched) Utils.mainThread.postDelayed(hideBanner, BANNER_SECONDS * 1000L)
    }

    /** Attached and not fading out (Discord fades old banners when it shows a new one). */
    private fun isShowing(alert: Alert) = alert.parent != null && alert.windowToken != null && alert.alpha >= 1f

    /** Puts new content into the banner on screen. Returns false if there is none to update. */
    private fun updateShownBanner(title: String, body: String, iconUrl: String?): Boolean {
        val alert = findAndTrackBanner()
        if (alert == null) {
            logger.debug("No Discord typing banner on screen to update")
            return false
        }
        val container = alert.layoutContainer ?: return false
        val titleView = container.findViewById<TextView>(Utils.getResId("popup_title", "id")) ?: return false
        val bodyView = container.findViewById<TextView>(Utils.getResId("popup_body", "id")) ?: return false
        Utils.mainThread.removeCallbacks(watchForBanner)
        bannerTitle = title
        titleView.text = title
        bodyView.text = body
        bodyView.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        val icon = container.findViewById<ImageView>(Utils.getResId("popup_icon", "id"))
        if (icon != null && iconUrl != null) MGImages.setImage(icon, iconUrl)
        scheduleHide()
        return true
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(activityTracker)
        resumedActivity = null
        lastTyping.clear()
        Utils.mainThread.post {
            compactBanner.clear()
            hideBanner.run()
            Utils.mainThread.removeCallbacks(hideBanner)
            Utils.mainThread.removeCallbacks(watchForBanner)
            shownBanners.clear()
            activeTypers.clear()
            bannerTarget = null
        }
    }

    private fun onTyping(typing: TypingUser) {
        val userId = typing.d()
        val channelId = typing.a()
        val me = StoreStream.getUsers().me
        if (me != null && me.id == userId) return

        // Clients repeat TYPING_START every few seconds while typing; only the first one notifies.
        val now = System.currentTimeMillis()
        val key = "$channelId:$userId"
        val previous = lastTyping.put(key, now)
        if (previous != null && now - previous < CONTINUATION_MS) {
            // Still typing: keep them in the combined banner.
            Utils.mainThread.post { activeTypers[key]?.lastSeen = now }
            return
        }

        val channel = StoreStream.getChannels().getChannel(channelId)
        val guildId = typing.b().takeIf { it > 0 } ?: channel?.i() ?: 0L
        val parentId = channel?.takeIf { it.D() in THREAD_TYPES }?.u() ?: 0L
        if (!Filters.allows(settings, userId, guildId, channelId, parentId)) return

        val foreground = isForeground()
        if (settings.getBool(KEY_IGNORE_CURRENT, false) && foreground &&
            StoreStream.getChannelsSelected().id == channelId
        ) {
            return
        }

        val user: User? = StoreStream.getUsers().users[userId] ?: typing.c()?.m()?.let(::CoreUser)
        if (user?.isBot == true && settings.getBool(KEY_IGNORE_BOTS, false)) return
        val nick = if (guildId == 0L) null else StoreStream.getGuilds().getMember(guildId, userId)?.nick ?: typing.c()?.h()
        val name = nick?.takeIf { it.trim().isNotEmpty() } ?: user?.globalName ?: user?.username ?: "Someone"
        val place = describePlace(channel, guildId)

        if (settings.getBool(KEY_IN_APP, true)) {
            val typer = Typer(name, place, user?.let { IconUtils.getForUser(it) }, channelId, guildId, now)
            Utils.mainThread.post {
                activeTypers.remove(key)
                activeTypers[key] = typer // Re-inserting keeps the newest typer last.
                showInApp()
            }
        }
        if (settings.getBool(KEY_PUSH, true) && (!foreground || !settings.getBool(KEY_PUSH_BACKGROUND_ONLY, false))) {
            showPush(name, if (place.isEmpty()) "is typing" else "is typing in $place", channelId, guildId, userId)
        }
    }

    /** "#general · Server", "your DMs", a group DM's name, or "" when the channel is unknown. */
    private fun describePlace(channel: Channel?, guildId: Long): String {
        channel ?: return ""
        val context = Utils.appContext
        if (channel.D() == 1) return "your DMs"
        val name = runCatching { ChannelUtils.d(channel, context, true) }.getOrNull().orEmpty()
        if (guildId == 0L) return name
        val guild = StoreStream.getGuilds().getGuild(guildId)?.name
        return when {
            guild == null -> name
            name.isEmpty() -> guild
            else -> "$name · $guild"
        }
    }

    /**
     * Shows one banner for everyone typing right now. Discord shows in-app banners one at a time from a
     * queue, each for its full duration, so separate banners would pile up and show late. Each update
     * replaces the previous banner instead: marking the old one seen drops it from the queue, and showing
     * the new one fades out the one on screen.
     */
    private fun showInApp() {
        try {
            val now = System.currentTimeMillis()
            activeTypers.values.removeAll { now - it.lastSeen > TYPING_EXPIRY_MS }
            val typers = activeTypers.values.toList().asReversed() // Newest first.
            val newest = typers.firstOrNull() ?: return

            // One person: "Name" / "is typing in #general · Server".
            // Several: "A, B and C are typing" / where, one line per person when they're in different places.
            val names = typers.map { it.name }.distinct()
            val places = typers.map { it.place }.distinct()
            val title: String
            val body: String
            if (typers.size == 1) {
                title = newest.name
                body = if (newest.place.isEmpty()) "is typing" else "is typing in ${newest.place}"
            } else {
                title = when (names.size) {
                    1 -> "${names[0]} is typing"
                    2 -> "${names[0]} and ${names[1]} are typing"
                    3 -> "${names[0]}, ${names[1]} and ${names[2]} are typing"
                    else -> "${names[0]}, ${names[1]} and ${names.size - 2} others are typing"
                }
                body = if (places.size == 1) {
                    if (places[0].isEmpty()) "" else "in ${places[0]}"
                } else {
                    val lines = typers.take(MAX_BANNER_LINES).map { if (it.place.isEmpty()) it.name else "${it.name} in ${it.place}" }
                    val more = typers.size - lines.size
                    lines.joinToString("\n") + if (more > 0) "\nand $more more" else ""
                }
            }

            bannerTarget = newest
            if (settings.getBool(KEY_COMPACT_BANNER, false)) {
                val activity = currentActivity() ?: return
                compactBanner.show(activity, compactText(typers, names, places), BANNER_SECONDS * 1000L) {
                    bannerTarget?.let { open(it, activity) }
                }
                return
            }
            if (updateShownBanner(title, body, newest.iconUrl)) return

            // Nothing on screen: replace any banner still waiting in Discord's queue with a new one.
            val notices = StoreStream.getNotices()
            shownBanners.forEach { notices.markSeen(it, now) }
            shownBanners.clear()
            currentAlert = null
            val noticeName = "${StoreNotices.IN_APP_NOTICE_TAG}#TypingNotifications#${++bannerCount}"
            shownBanners += noticeName
            bannerTitle = title
            startWatchingForBanner()
            NoticePopup.INSTANCE.enqueue(
                noticeName,
                title,
                null,
                body,
                null,
                null,
                null,
                null,
                newest.iconUrl,
                null,
                null,
                // No auto-dismiss from Discord; scheduleHide restarts our own timer on every update.
                null,
                bannerScreens,
                { _ -> },
                { view -> open(bannerTarget ?: newest, view.context) },
            )
        } catch (error: Throwable) {
            logger.error("Failed to show in-app typing notification", error)
        }
    }

    private fun open(target: Typer, context: Context) {
        IntentUtils.INSTANCE.consumeExternalRoutingIntent(
            IntentUtils.RouteBuilders.selectChannel(target.channelId, target.guildId, null),
            context,
        )
    }

    /**
     * One line in the style of Discord's typing bar, "**A** and **B** are typing in #general · Server",
     * or a summary line plus one line per person when they're typing in different places.
     */
    private fun compactText(typers: List<Typer>, names: List<String>, places: List<String>): CharSequence {
        val text = SpannableStringBuilder()
        val shown = if (names.size > 3) names.take(2) else names
        shown.forEachIndexed { index, name ->
            if (index > 0) text.append(if (index == shown.size - 1 && names.size <= 3) " and " else ", ")
            text.append(bold(name))
        }
        if (names.size > 3) text.append(" and ${names.size - 2} others")
        text.append(if (names.size == 1) " is typing" else " are typing")
        if (places.size == 1) {
            if (places[0].isNotEmpty()) text.append(" in ").append(places[0])
            return text
        }
        val lines = typers.take(MAX_BANNER_LINES)
        lines.forEach { typer ->
            text.append('\n').append(bold(typer.name))
            if (typer.place.isNotEmpty()) text.append(" in ").append(typer.place)
        }
        if (typers.size > lines.size) text.append("\nand ${typers.size - lines.size} more")
        return text
    }

    /** Bolds a name with Discord's own formatter, as its typing bar does (`**!!{a}!!**`). */
    private fun bold(name: String): CharSequence =
        runCatching { FormatUtils.l("**!!{a}!!**", arrayOf(name), null, 2) }.getOrNull() ?: name

    /** Someone in the combined banner. */
    private class Typer(
        val name: String,
        val place: String,
        val iconUrl: String?,
        val channelId: Long,
        val guildId: Long,
        var lastSeen: Long,
    )

    private fun showPush(name: String, body: String, channelId: Long, guildId: Long, userId: Long) {
        try {
            val context = Utils.appContext
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Typing notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Someone started typing"
                    },
                )
            }
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
            }
            val id = (channelId * 31 + userId).hashCode()
            val intent = IntentUtils.RouteBuilders.selectChannel(channelId, guildId, null).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val icon = Utils.getResId("ic_notification_message_24dp", "drawable")
            builder
                .setSmallIcon(if (icon != 0) icon else android.R.drawable.stat_notify_chat)
                .setContentTitle(name)
                .setContentText(body)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(CHANNEL)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
            manager.notify(TAG, id, builder.build())
        } catch (error: Throwable) {
            logger.error("Failed to show typing push notification", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun isForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    companion object {
        /** The loaded plugin, so the settings page shares its SettingsAPI. */
        @JvmStatic
        var instance: TypingNotifications? = null
            private set

        const val KEY_IN_APP = "inApp"
        const val KEY_PUSH = "push"
        const val KEY_PUSH_BACKGROUND_ONLY = "pushBackgroundOnly"
        const val KEY_IGNORE_CURRENT = "ignoreCurrentChannel"
        const val KEY_IGNORE_BOTS = "ignoreBots"
        const val KEY_COMPACT_BANNER = "useCompactBanner"

        private const val CHANNEL = "typingnotifications_typing"
        private const val TAG = "TypingNotifications"

        /** Discord expires a typing indicator after 10 seconds; a gap longer than that is a new session. */
        private const val CONTINUATION_MS = 12_000L

        /** Matches Discord's own typing indicator, which drops someone 10 seconds after their last TYPING_START. */
        private const val TYPING_EXPIRY_MS = 10_000L
        private const val MAX_BANNER_LINES = 3

        /** How long the banner stays after its last update. */
        private const val BANNER_SECONDS = 5

        /** Look for a requested Discord banner every 250 ms for up to 30 seconds. */
        private const val WATCH_INTERVAL_MS = 250L
        private const val WATCH_TRIES = 120
        private val THREAD_TYPES = setOf(10, 11, 12)
    }
}
