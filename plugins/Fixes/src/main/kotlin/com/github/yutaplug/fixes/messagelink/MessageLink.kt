package com.github.yutaplug.fixes.messagelink

import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/** A discord.com link to a channel, thread or forum post, or to a message in one ([messageId] is set). */
internal data class MessageLink(val channelId: Long, val messageId: Long?) {
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
        private val path =
            Pattern.compile("^/channels/(@me|[1-9][0-9]{0,18})/([1-9][0-9]{0,18})(?:/([1-9][0-9]{0,18}))?/?$")

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
            val match = path.matcher(uri.rawPath ?: return null)
            if (!match.matches()) return null
            // Validate the guild ID too, even though opening the link only
            // needs the channel and message IDs.
            val guild = match.group(1)
            if (guild != "@me" && parseId(guild) == null) return null
            val channelId = parseId(match.group(2)) ?: return null
            val message = match.group(3) ?: return MessageLink(channelId, null)
            return MessageLink(channelId, parseId(message) ?: return null)
        }

        private fun parseId(value: String): Long? = value.toLongOrNull()?.takeIf { it > 1 }
    }
}
