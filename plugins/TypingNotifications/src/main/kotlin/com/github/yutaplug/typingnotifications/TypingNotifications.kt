package com.github.yutaplug.typingnotifications

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.users.globalName
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelUtils
import com.discord.api.guildmember.GuildMember
import com.discord.api.message.Message
import com.discord.api.user.TypingUser
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserTyping
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.intent.IntentUtils
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class TypingNotifications : Plugin() {
    init {
        settingsTab = SettingsTab(TypingNotificationsSettings::class.java)
        instance = this
    }

    private val reportError: (String, Throwable) -> Unit = { action, error -> logger.error("Failed to $action", error) }
    private val discordBanner = DiscordBanner(::currentActivity, reportError)
    private val compactBanner = CompactBanner(reportError)
    private val push = PushNotifier(reportError)

    // Everything below is main thread only; the store hooks post their events there.

    /** When each person last sent TYPING_START in each channel, keyed by "channel:user". */
    private val sessions = HashMap<String, Long>()

    /** Everyone in the in-app banner, oldest first, keyed like [sessions]. */
    private val active = LinkedHashMap<String, Typer>()

    /** Where tapping the banner goes: the newest typer at the last update. */
    private var bannerTarget: Typer? = null

    /** Drops people who stopped typing from the banner. */
    private val expire = Runnable { refreshBanner() }

    /** Activities between onStart and onStop: Discord is on screen while there are any. */
    private val visible = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * The screen in front. Aliucord's Utils.appActivity only changes when a screen is created, so after
     * leaving a screen opened on top (such as plugin settings) it still points at the closed one.
     */
    private var front: WeakReference<Activity>? = null

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visible.add(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            front = WeakReference(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            visible.remove(activity)
            if (front?.get() === activity) front = null
        }

        override fun onActivityDestroyed(activity: Activity) {
            visible.remove(activity)
            if (front?.get() === activity) front = null
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    private val foreground: Boolean get() = visible.isNotEmpty()

    private fun usable(activity: Activity?) = activity?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun currentActivity(): Activity? = usable(front?.get()) ?: usable(Utils.appActivity)

    override fun start(context: Context) {
        // The plugin starts while Discord is open, after its screen has started.
        usable(Utils.appActivity)?.let {
            visible.add(it)
            front = WeakReference(it)
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        patcher.patch(StoreUserTyping::class.java, "handleTypingStart", arrayOf(TypingUser::class.java), Hook { frame ->
            val typing = frame.args[0] as TypingUser
            val userId = typing.d()
            val channelId = typing.a()
            val guildId = typing.b()
            val member = typing.c()
            Utils.mainThread.post { safely("handle typing") { onTyping(userId, channelId, guildId, member) } }
        })
        // A sent message ends that person's typing; typing again afterwards notifies again.
        patcher.patch(StoreUserTyping::class.java, "handleMessageCreate", arrayOf(Message::class.java), Hook { frame ->
            val message = frame.args[0] as Message
            val userId = message.e()?.getId() ?: return@Hook
            val channelId = message.g()
            Utils.mainThread.post { safely("handle message") { onMessage(channelId, userId) } }
        })
        discordBanner.patch(patcher)
        ContextMenus(settings, reportError).patch(patcher)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        (context.applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        onMain {
            Utils.mainThread.removeCallbacks(expire)
            compactBanner.clear()
            discordBanner.hide()
            push.cancelAll()
            sessions.clear()
            active.clear()
            bannerTarget = null
            visible.clear()
            front = null
        }
    }

    private fun onTyping(userId: Long, channelId: Long, eventGuildId: Long, member: GuildMember?) {
        if (StoreStream.getUsers().me?.id == userId) return

        // Clients repeat TYPING_START every few seconds while typing; only the first one notifies.
        val now = SystemClock.elapsedRealtime()
        sessions.values.removeAll { now - it > CONTINUATION_MS }
        val key = "$channelId:$userId"
        if (sessions.put(key, now) != null) {
            active[key]?.lastSeen = now
            return
        }

        val channel = StoreStream.getChannels().getChannel(channelId)
        val guildId = eventGuildId.takeIf { it > 0 } ?: channel?.i() ?: 0L
        val parentId = channel?.takeIf { it.D() in THREAD_TYPES }?.u() ?: 0L
        if (!Filters.allows(settings, userId, guildId, channelId, parentId)) return
        // People you blocked are hidden everywhere else in Discord.
        if (StoreStream.getUserRelationships().relationships[userId] == RELATIONSHIP_BLOCKED) return
        val user: User? = StoreStream.getUsers().users[userId] ?: member?.m()?.let(::CoreUser)
        if (user?.isBot == true && settings.getBool(KEY_IGNORE_BOTS, false)) return
        val foreground = foreground
        if (foreground && settings.getBool(KEY_IGNORE_CURRENT, false) &&
            StoreStream.getChannelsSelected().id == channelId
        ) {
            return
        }

        val nick = if (guildId == 0L) null else StoreStream.getGuilds().getMember(guildId, userId)?.nick ?: member?.h()
        val name = nick?.takeIf { it.trim().isNotEmpty() } ?: user?.globalName ?: user?.username ?: "Someone"
        val typer = Typer(key, userId, name, describePlace(channel, guildId), user?.let { IconUtils.getForUser(it) }, channelId, guildId, now)

        // A banner can only show inside Discord; in the background it would just wait in the queue.
        if (foreground && settings.getBool(KEY_IN_APP, true)) {
            active.remove(key)
            active[key] = typer // Re-inserting keeps the newest typer last.
            showBanner()
        }
        if (settings.getBool(KEY_PUSH, true) && (!foreground || !settings.getBool(KEY_PUSH_BACKGROUND_ONLY, false))) {
            push.show(typer)
        }
    }

    private fun onMessage(channelId: Long, userId: Long) {
        val key = "$channelId:$userId"
        sessions.remove(key)
        push.cancel(channelId, userId)
        if (active.remove(key) != null) refreshBanner()
    }

    /** "#general · Server", "your DMs", a group DM's name, or "" when the channel is unknown. */
    private fun describePlace(channel: Channel?, guildId: Long): String {
        channel ?: return ""
        if (channel.D() == 1) return "your DMs"
        val name = runCatching { ChannelUtils.d(channel, Utils.appContext, true) }.getOrNull().orEmpty()
        if (guildId == 0L) return name
        val guild = StoreStream.getGuilds().getGuild(guildId)?.name
        return when {
            guild == null -> name
            name.isEmpty() -> guild
            else -> "$name · $guild"
        }
    }

    /** Everyone still typing, newest first. */
    private fun currentTypers(): List<Typer> {
        val now = SystemClock.elapsedRealtime()
        active.values.removeAll { now - it.lastSeen > TYPING_EXPIRY_MS }
        Utils.mainThread.removeCallbacks(expire)
        active.values.minOfOrNull { it.lastSeen }?.let { oldest ->
            Utils.mainThread.postDelayed(expire, oldest + TYPING_EXPIRY_MS - now + 50)
        }
        return active.values.reversed()
    }

    /** Shows the banner for everyone typing, or updates the one on screen. */
    private fun showBanner() {
        val typers = currentTypers()
        val newest = typers.firstOrNull() ?: return
        val text = TypingText(typers)
        bannerTarget = newest
        val click: () -> Unit = {
            val target = bannerTarget
            val activity = currentActivity()
            if (target != null && activity != null) open(target, activity)
        }
        if (settings.getBool(KEY_COMPACT_BANNER, false)) {
            discordBanner.hide()
            compactBanner.show(currentActivity() ?: return, text.compact, click)
        } else {
            compactBanner.clear()
            discordBanner.show(text, newest.iconUrl, click)
        }
    }

    /** Brings the banner on screen up to date without showing it again; hides it once nobody is left. */
    private fun refreshBanner() {
        val typers = currentTypers()
        val newest = typers.firstOrNull()
        if (newest == null) {
            bannerTarget = null
            discordBanner.hide()
            compactBanner.hide()
            return
        }
        val text = TypingText(typers)
        bannerTarget = newest
        compactBanner.update(text.compact)
        discordBanner.update(text, newest.iconUrl)
    }

    private fun open(target: Typer, context: Context) {
        IntentUtils.INSTANCE.consumeExternalRoutingIntent(
            IntentUtils.RouteBuilders.selectChannel(target.channelId, target.guildId, null),
            context,
        )
    }

    private inline fun safely(action: String, work: () -> Unit) {
        try {
            work()
        } catch (error: Throwable) {
            reportError(action, error)
        }
    }

    private fun onMain(work: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) work() else Utils.mainThread.post(work)
    }

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

        /** Discord expires a typing indicator after 10 seconds; a longer gap starts a new session. */
        private const val CONTINUATION_MS = 12_000L

        /** Matches Discord's typing indicator, which drops someone 10 seconds after their last TYPING_START. */
        private const val TYPING_EXPIRY_MS = 10_000L

        private const val RELATIONSHIP_BLOCKED = 2
        private val THREAD_TYPES = setOf(10, 11, 12)
    }
}
