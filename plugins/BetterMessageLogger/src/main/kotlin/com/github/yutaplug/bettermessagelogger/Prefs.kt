package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Color
import com.aliucord.api.SettingsAPI
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream

/** Setting keys and defaults. Keys of earlier versions are kept so existing settings carry over. */
internal object Prefs {
    const val PERSIST = "database"
    const val PREFETCH_MODE = "prefetchMode"
    private const val LEGACY_PREFETCH = "prefetchMedia"
    const val PREFETCH_OWN = "prefetchOwn"
    const val MEDIA_ATTACHMENTS = "mediaAttachments"
    const val MEDIA_EMBEDS = "mediaEmbeds"
    const val MEDIA_IMAGES = "mediaImages"
    const val MEDIA_VIDEOS = "mediaVideos"
    const val MEDIA_AUDIO = "mediaAudio"
    const val MEDIA_FILES = "mediaFiles"
    const val MEDIA_MAX_MB = "mediaMaxMb"

    const val SHOW_DELETED_TAG = "showDeletedTag"
    const val DELETED_LABEL_COLOR = "deletedLabelColor"
    const val DELETED_MESSAGE_COLOR = "deletedMessageColor"
    const val DEFAULT_DELETED_LABEL_COLOR = "#FFFF0000"
    const val DEFAULT_DELETED_MESSAGE_COLOR = "#FFFFFFFF"

    const val HIDE_ENABLED = "hideMessages"
    const val HIDE_PERSIST = "hidePersist"
    const val HIDDEN_IDS = "hiddenMessages"

    const val IGNORE_OWN = "ignoreOwn"
    const val IGNORE_BOTS = "ignoreBots"

    const val PREFETCH_OFF = 0
    const val PREFETCH_ALL = 1
    const val PREFETCH_SELECTED = 2

    val MAX_MB_CHOICES = intArrayOf(5, 10, 25, 50, 100)

    fun prefetchMode(settings: SettingsAPI): Int {
        val mode = settings.getInt(PREFETCH_MODE, -1)
        if (mode in PREFETCH_OFF..PREFETCH_SELECTED) return mode
        // Versions before 1.1.0 had a single on/off switch.
        return if (settings.getBool(LEGACY_PREFETCH, false)) PREFETCH_ALL else PREFETCH_OFF
    }

    fun persist(settings: SettingsAPI) = settings.getBool(PERSIST, false)

    fun hideEnabled(settings: SettingsAPI) = settings.getBool(HIDE_ENABLED, true)

    fun hidePersist(settings: SettingsAPI) = settings.getBool(HIDE_PERSIST, false)

    fun showDeletedTag(settings: SettingsAPI) =settings.getBool(SHOW_DELETED_TAG, true)

    fun labelColor(settings: SettingsAPI) = color(settings, DELETED_LABEL_COLOR, DEFAULT_DELETED_LABEL_COLOR)

    fun textColor(settings: SettingsAPI) = color(settings, DELETED_MESSAGE_COLOR, DEFAULT_DELETED_MESSAGE_COLOR)

    fun defaultColor(key: String) =
        if (key == DELETED_LABEL_COLOR) DEFAULT_DELETED_LABEL_COLOR else DEFAULT_DELETED_MESSAGE_COLOR

    private fun color(settings: SettingsAPI, key: String, fallback: String): Int = try {
        Color.parseColor(settings.getString(key, fallback))
    } catch (_: Exception) {
        Color.parseColor(fallback)
    }
}

/** What a comma-separated ID list holds, which decides how its entries are named. */
internal enum class IdKind { USER, CHANNEL, SERVER }

/** A comma-separated ID list stored in the plugin settings. */
internal class IdList(val key: String, val title: String, val kind: IdKind) {
    fun read(settings: SettingsAPI): LinkedHashSet<Long> = settings
        .getString(key, "")
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull()?.takeIf { value -> value > 0 } }
        .toCollection(LinkedHashSet())

    fun contains(settings: SettingsAPI, id: Long) = id in read(settings)

    fun set(settings: SettingsAPI, id: Long, listed: Boolean) {
        val ids = read(settings)
        if (if (listed) ids.add(id) else ids.remove(id)) settings.setString(key, ids.joinToString(","))
    }

    companion object {
        // Logging filters (keys from earlier versions).
        val IGNORED_USERS = IdList("ignoredUsers", "Ignored users", IdKind.USER)
        val ALLOWED_USERS = IdList("allowedUsers", "Allowed users", IdKind.USER)
        val BLOCKED_SERVERS = IdList("blackServers", "Blocked servers", IdKind.SERVER)
        val ALLOWED_SERVERS = IdList("whiteServers", "Allowed servers", IdKind.SERVER)
        val BLOCKED_CHANNELS = IdList("blackChannels", "Blocked channels", IdKind.CHANNEL)
        val ALLOWED_CHANNELS = IdList("whiteChannels", "Allowed channels", IdKind.CHANNEL)
        val BLOCKED_DMS = IdList("blackDms", "Blocked DMs", IdKind.CHANNEL)
        val ALLOWED_DMS = IdList("whiteDms", "Allowed DMs", IdKind.CHANNEL)

        // Pre-download filters. Channel lists include DMs.
        val PREFETCH_USERS = IdList("prefetchUsers", "Selected users", IdKind.USER)
        val PREFETCH_CHANNELS = IdList("prefetchChannels", "Selected channels and DMs", IdKind.CHANNEL)
        val PREFETCH_SERVERS = IdList("prefetchServers", "Selected servers", IdKind.SERVER)
        val SKIP_USERS = IdList("prefetchSkipUsers", "Excluded users", IdKind.USER)
        val SKIP_CHANNELS = IdList("prefetchSkipChannels", "Excluded channels and DMs", IdKind.CHANNEL)
        val SKIP_SERVERS = IdList("prefetchSkipServers", "Excluded servers", IdKind.SERVER)

        val ALL = listOf(
            IGNORED_USERS, ALLOWED_USERS, BLOCKED_SERVERS, ALLOWED_SERVERS, BLOCKED_CHANNELS, ALLOWED_CHANNELS, BLOCKED_DMS,
            ALLOWED_DMS, PREFETCH_USERS, PREFETCH_CHANNELS, PREFETCH_SERVERS, SKIP_USERS, SKIP_CHANNELS,
            SKIP_SERVERS,
        )

        fun byKey(key: String?) = ALL.firstOrNull { it.key == key }

        /** A readable name for an ID, or null when Discord hasn't loaded it. */
        fun describe(context: Context, kind: IdKind, id: Long): String? = try {
            when (kind) {
                IdKind.USER -> StoreStream.getUsers().users[id]?.username
                IdKind.SERVER -> StoreStream.getGuilds().getGuild(id)?.name
                IdKind.CHANNEL -> StoreStream.getChannels().getChannel(id)?.let { channel ->
                    val name = ChannelUtils.d(channel, context, true)
                    val guild = StoreStream.getGuilds().getGuild(channel.i())?.name
                    if (guild == null) name else "$name · $guild"
                }
            }?.takeIf { name -> name.trim().isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** An immutable snapshot of the logging and pre-download filters; rebuilt whenever settings change. */
internal class Filters(settings: SettingsAPI) {
    private val ignoreOwn = settings.getBool(Prefs.IGNORE_OWN, false)
    private val ignoreBots = settings.getBool(Prefs.IGNORE_BOTS, false)
    private val lists = IdList.ALL.associate { it.key to it.read(settings) }
    private val prefetchMode = Prefs.prefetchMode(settings)
    private val prefetchOwn = settings.getBool(Prefs.PREFETCH_OWN, true)

    private fun ids(list: IdList) = lists.getValue(list.key)

    private fun allowed(id: Long, blocked: IdList, allowed: IdList) =
        id !in ids(blocked) && (ids(allowed).isEmpty() || id in ids(allowed))

    /** Whether messages from this author and place are logged at all. */
    fun logs(authorId: Long, bot: Boolean, channelId: Long, guildId: Long?): Boolean {
        if ((ignoreBots && bot) || !allowed(authorId, IdList.IGNORED_USERS, IdList.ALLOWED_USERS)) return false
        if (ignoreOwn && authorId == myId()) return false
        return if (guildId == null || guildId == 0L) {
            allowed(channelId, IdList.BLOCKED_DMS, IdList.ALLOWED_DMS)
        } else {
            allowed(channelId, IdList.BLOCKED_CHANNELS, IdList.ALLOWED_CHANNELS) &&
                allowed(guildId, IdList.BLOCKED_SERVERS, IdList.ALLOWED_SERVERS)
        }
    }

    fun logs(record: MessageRecord) = logs(record.authorId, record.bot, record.channelId, record.guildId)

    /** Whether media of a live message is downloaded ahead of time, in case it gets deleted. */
    fun prefetches(authorId: Long, channelId: Long, guildId: Long?): Boolean {
        if (prefetchMode == Prefs.PREFETCH_OFF) return false
        val server = guildId?.takeIf { it != 0L }
        if (authorId in ids(IdList.SKIP_USERS) || channelId in ids(IdList.SKIP_CHANNELS)) return false
        if (server != null && server in ids(IdList.SKIP_SERVERS)) return false
        if (!prefetchOwn && authorId == myId()) return false
        if (prefetchMode == Prefs.PREFETCH_ALL) return true
        return authorId in ids(IdList.PREFETCH_USERS) || channelId in ids(IdList.PREFETCH_CHANNELS) ||
            (server != null && server in ids(IdList.PREFETCH_SERVERS))
    }

    companion object {
        fun myId(): Long? = try {
            StoreStream.getUsers().me.id
        } catch (_: Exception) {
            null
        }
    }
}
