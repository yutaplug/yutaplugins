package com.github.yutaplug.fixes.messagelink

import java.net.URI
import java.util.Locale

internal data class MessageLink(val channelId: Long, val messageId: Long) {
    companion object {
        private val hosts = setOf(
            "discord.com",
            "discordapp.com",
            "www.discord.com",
            "www.discordapp.com",
            "canary.discord.com",
            "canary.discordapp.com",
            "ptb.discord.com",
            "ptb.discordapp.com",
        )
        private val path = Regex("^/channels/(@me|[1-9][0-9]{0,18})/([1-9][0-9]{0,18})/([1-9][0-9]{0,18})/?$")

        fun parse(url: String): MessageLink? {
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (uri.host?.lowercase(Locale.ROOT) !in hosts || uri.rawUserInfo != null) return null
            if (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80) return null
            val match = path.matchEntire(uri.rawPath ?: return null) ?: return null
            // Validate the guild ID too, even though the native jump only
            // needs the channel and message IDs.
            if (match.groupValues[1] != "@me" && parseId(match.groupValues[1]) == null) return null
            val channelId = parseId(match.groupValues[2]) ?: return null
            val messageId = parseId(match.groupValues[3]) ?: return null
            return MessageLink(channelId, messageId)
        }

        private fun parseId(value: String): Long? = value.toLongOrNull()?.takeIf { it > 1 }
    }
}
