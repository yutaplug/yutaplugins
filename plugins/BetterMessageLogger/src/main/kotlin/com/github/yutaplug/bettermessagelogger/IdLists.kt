package com.github.yutaplug.bettermessagelogger

import android.content.Context
import com.aliucord.api.SettingsAPI
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream

/** Comma-separated ID lists used by the logging filters. */
internal object IdLists {
    const val IGNORED_USERS = "ignoredUsers"
    const val BLOCKED_CHANNELS = "blackChannels"
    const val ALLOWED_CHANNELS = "whiteChannels"
    const val BLOCKED_SERVERS = "blackServers"
    const val ALLOWED_SERVERS = "whiteServers"
    const val BLOCKED_DMS = "blackDms"
    const val ALLOWED_DMS = "whiteDms"

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

    /** A readable name for an entry, or null when Discord has not loaded it. */
    fun describe(context: Context, key: String, id: Long): String? = runCatching {
        when (key) {
            IGNORED_USERS -> StoreStream.getUsers().users[id]?.username
            BLOCKED_SERVERS, ALLOWED_SERVERS -> StoreStream.getGuilds().getGuild(id)?.name
            else -> StoreStream.getChannels().getChannel(id)?.let { channel ->
                val name = ChannelUtils.d(channel, context, true)
                val guild = StoreStream.getGuilds().getGuild(channel.i())?.name
                if (guild == null) name else "$name · $guild"
            }
        }
    }.getOrNull()?.takeIf { name -> name.any { !it.isWhitespace() } }
}
