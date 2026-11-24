package com.github.yutaplug.readall

import com.aliucord.Http
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject

/** Which channels a read all run covers. */
enum class Scope(val value: String, val label: String) {
    SERVERS("servers", "Mark Servers as Read"),
    DMS("dms", "Mark DMs as Read"),
    ALL("all", "Mark Everything as Read"),
    ;

    companion object {
        fun from(value: String?): Scope = values().firstOrNull { it.value == value } ?: SERVERS
    }
}

/** A channel to acknowledge up to [messageId]. */
class Unread(val channelId: Long, val messageId: Long)

object ReadStates {
    private const val DISCORD_EPOCH = 1_420_070_400_000L
    private const val BATCH_SIZE = 100
    private const val MAX_ATTEMPTS = 4
    private const val MAX_RETRY_WAIT_MS = 10_000L

    /**
     * Finds every channel whose newest message is newer than its read state, using the same rules as
     * Discord's own unread computation but without skipping muted channels, so their mention badges clear too.
     */
    fun collect(scope: Scope): List<Unread> {
        val recent = StoreStream.getMessagesMostRecent().mostRecentIds
        val acks = StoreStream.getMessageAck().all
        val channels = StoreStream.getChannels()
        val permissions = StoreStream.getPermissions().permissionsByChannel
        val joinedAt = StoreStream.getGuilds().guildsJoinedAt
        val result = ArrayList<Unread>()

        for (entry in recent.entries) {
            val channelId = entry.key ?: continue
            val latest = entry.value ?: continue
            if (latest <= 0L) continue
            val channel = channels.getChannel(channelId) ?: continue
            val isPrivate = ChannelUtils.B(channel)
            if (isPrivate && scope == Scope.SERVERS) continue
            if (!isPrivate && scope == Scope.DMS) continue

            val readUpTo = acks[channelId]?.messageId ?: when {
                // Discord only tracks threads the user joined or can see in forums; others are never unread.
                isPrivate || ChannelUtils.H(channel) -> continue
                // A channel never opened counts as read up to the moment the user joined its server.
                else -> joinedAt[channel.i()]?.let { (it - DISCORD_EPOCH) shl 22 } ?: continue
            }
            if (latest <= readUpTo) continue

            if (!isPrivate) {
                val permission = permissions[channelId] ?: permissions[channel.u()]
                if (!PermissionUtils.INSTANCE.hasAccess(channel, permission)) continue
            }
            result.add(Unread(channelId, latest))
        }
        return result
    }

    /** Acknowledges [unreads] in batches. The gateway's MESSAGE_ACK events then update the local read states. */
    fun acknowledge(unreads: List<Unread>, isCancelled: () -> Boolean) {
        var start = 0
        while (start < unreads.size) {
            if (isCancelled()) return
            val end = minOf(start + BATCH_SIZE, unreads.size)
            val states = JSONArray()
            for (i in start until end) {
                val unread = unreads[i]
                states.put(
                    JSONObject()
                        .put("channel_id", unread.channelId.toString())
                        .put("message_id", unread.messageId.toString()),
                )
            }
            post(JSONObject().put("read_states", states).toString(), isCancelled)
            start = end
        }
    }

    private fun post(body: String, isCancelled: () -> Boolean) {
        var attempt = 1
        while (true) {
            val retryAfter = Http.Request.newDiscordRNRequest("/read-states/ack-bulk", "POST").use { request ->
                request.setRequestTimeout(15_000)
                request.setHeader("Content-Type", "application/json")
                request.executeWithBody(body).use { response ->
                    if (response.ok()) return
                    if (response.statusCode != 429 || attempt >= MAX_ATTEMPTS) {
                        throw IllegalStateException("Discord returned HTTP ${response.statusCode}")
                    }
                    retryAfterMs(request.conn.getHeaderField("Retry-After"))
                }
            }
            Thread.sleep(retryAfter)
            if (isCancelled()) return
            attempt++
        }
    }

    private fun retryAfterMs(header: String?): Long {
        val seconds = try {
            header?.trim()?.toDouble() ?: 1.0
        } catch (_: NumberFormatException) {
            1.0
        }
        return maxOf(250L, minOf((seconds * 1000).toLong(), MAX_RETRY_WAIT_MS))
    }
}
