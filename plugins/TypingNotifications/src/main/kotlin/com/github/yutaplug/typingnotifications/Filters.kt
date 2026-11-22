package com.github.yutaplug.typingnotifications

import android.content.Context
import com.aliucord.api.SettingsAPI
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream

/** Comma-separated ID lists that decide where typing notifications are sent. */
internal object Filters {
    const val BLOCKED_USERS = "blackUsers"
    const val ALLOWED_USERS = "whiteUsers"
    const val BLOCKED_SERVERS = "blackServers"
    const val ALLOWED_SERVERS = "whiteServers"
    const val BLOCKED_CHANNELS = "blackChannels"
    const val ALLOWED_CHANNELS = "whiteChannels"
    const val BLOCKED_DMS = "blackDms"
    const val ALLOWED_DMS = "whiteDms"

    /** The block/allow pair for one kind of place, used by the long-press menus. */
    class Kind(val noun: String, val blockKey: String, val allowKey: String)

    val SERVER = Kind("server", BLOCKED_SERVERS, ALLOWED_SERVERS)
    val CHANNEL = Kind("channel", BLOCKED_CHANNELS, ALLOWED_CHANNELS)
    val DM = Kind("DM", BLOCKED_DMS, ALLOWED_DMS)

    fun read(settings: SettingsAPI, key: String): LinkedHashSet<Long> = settings
        .getString(key, "")
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull()?.takeIf { value -> value > 0 } }
        .toCollection(LinkedHashSet())

    fun contains(settings: SettingsAPI, key: String, id: Long) = id in read(settings, key)

    fun set(settings: SettingsAPI, key: String, id: Long, listed: Boolean) {
        val ids = read(settings, key)
        if (if (listed) ids.add(id) else ids.remove(id)) settings.setString(key, ids.joinToString(","))
    }

    /**
     * Whether a typing event passes the filters. Everything notifies by default; blacklists always win,
     * and once any server, channel or DM whitelist has entries only whitelisted places notify. The user
     * whitelist works the same way for people.
     */
    fun allows(settings: SettingsAPI, userId: Long, guildId: Long, channelId: Long, parentId: Long): Boolean {
        if (contains(settings, BLOCKED_USERS, userId)) return false
        val allowedUsers = read(settings, ALLOWED_USERS)
        if (allowedUsers.isNotEmpty() && userId !in allowedUsers) return false

        val isDm = guildId == 0L
        val channels = listOf(channelId, parentId).filter { it > 0 }
        if (isDm) {
            if (contains(settings, BLOCKED_DMS, channelId)) return false
        } else {
            if (contains(settings, BLOCKED_SERVERS, guildId)) return false
            val blocked = read(settings, BLOCKED_CHANNELS)
            if (channels.any { it in blocked }) return false
        }

        val allowedServers = read(settings, ALLOWED_SERVERS)
        val allowedChannels = read(settings, ALLOWED_CHANNELS)
        val allowedDms = read(settings, ALLOWED_DMS)
        if (allowedServers.isEmpty() && allowedChannels.isEmpty() && allowedDms.isEmpty()) return true
        return if (isDm) channelId in allowedDms else guildId in allowedServers || channels.any { it in allowedChannels }
    }

    /** A readable name for a list entry, or null when Discord has not loaded it. */
    fun describe(context: Context, key: String, id: Long): String? = runCatching {
        when (key) {
            BLOCKED_USERS, ALLOWED_USERS -> StoreStream.getUsers().users[id]?.username
            BLOCKED_SERVERS, ALLOWED_SERVERS -> StoreStream.getGuilds().getGuild(id)?.name
            else -> StoreStream.getChannels().getChannel(id)?.let { channel ->
                val name = ChannelUtils.d(channel, context, true)
                val guild = StoreStream.getGuilds().getGuild(channel.i())?.name
                if (guild == null) name else "$name · $guild"
            }
        }
    }.getOrNull()?.takeIf { name -> name.trim().isNotEmpty() }
}
