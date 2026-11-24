package com.github.yutaplug.customrpc

import android.net.Uri
import com.aliucord.api.SettingsAPI
import com.discord.api.activity.ActivityType
import java.util.regex.Pattern

/** Setting keys. The old ones are kept so existing configurations carry over. */
internal object Keys {
    const val ENABLED = "enabled"
    const val TYPE = "activityType"
    const val NAME = "name"
    const val DETAILS = "details"
    const val STATE = "state"
    const val STREAM_URL = "streamUrl"
    const val APPLICATION_ID = "applicationId"
    const val LARGE_IMAGE = "largeImage"
    const val LARGE_TEXT = "largeImageText"
    const val SMALL_IMAGE = "smallImage"
    const val SMALL_TEXT = "smallImageText"
    const val BUTTON1_LABEL = "button1Label"
    const val BUTTON1_URL = "button1Url"
    const val BUTTON2_LABEL = "button2Label"
    const val BUTTON2_URL = "button2Url"
    const val TIMESTAMP_MODE = "timestampMode"
    const val TIMESTAMP_DURATION = "timestampDuration"
    const val TIMESTAMP_ANCHOR = "timestampAnchor"
    const val STARTED_AT = "startedAt"
    const val PARTY_SIZE = "partySize"
    const val PARTY_MAX = "partyMax"
    const val PARTY_ID = "partyId"

    // Replaced or removed; migrated or dropped on start.
    const val OLD_LARGE_IMAGE_URL = "largeImageUrl"
    const val OLD_SMALL_IMAGE_URL = "smallImageUrl"
    val REMOVED = listOf("activityFlags", "flags", "joinSecret")

}

internal enum class TimestampMode(val label: String, val description: String) {
    SINCE_ENABLED("Since turned on", "Counts up from when you turned the activity on"),
    ELAPSED("Custom elapsed time", "Counts up from a time you choose, like \"2h\" played"),
    REMAINING("Time remaining", "Counts down to zero, then hides"),
    OFF("Off", "No time is shown"),
    ;

    val needsDuration get() = this == ELAPSED || this == REMAINING

    companion object {
        fun of(name: String?) = values().firstOrNull { it.name == name } ?: SINCE_ENABLED
    }
}

internal object RpcTypes {
    val all =
        listOf(
            ActivityType.PLAYING,
            ActivityType.STREAMING,
            ActivityType.LISTENING,
            ActivityType.WATCHING,
            ActivityType.COMPETING,
        )

    fun label(type: ActivityType) = when (type) {
        ActivityType.STREAMING -> "Streaming"
        ActivityType.LISTENING -> "Listening to"
        ActivityType.WATCHING -> "Watching"
        ActivityType.COMPETING -> "Competing in"
        else -> "Playing"
    }

    fun of(name: String?) = all.firstOrNull { it.name == name } ?: ActivityType.PLAYING
}

internal class RpcButton(val label: String, val url: String)

/** A snapshot of the saved settings. */
internal class RpcConfig(private val settings: SettingsAPI) {
    private fun text(key: String) = settings.getString(key, "")?.trim().orEmpty()

    private fun optional(key: String) = text(key).takeIf { it.isNotEmpty() }

    val enabled = settings.getBool(Keys.ENABLED, false)
    val type = RpcTypes.of(settings.getString(Keys.TYPE, null))
    val name = optional(Keys.NAME) ?: "Custom RPC"
    val details = optional(Keys.DETAILS)
    val state = optional(Keys.STATE)
    val streamUrl = optional(Keys.STREAM_URL)?.takeIf { type == ActivityType.STREAMING && Validation.isStreamUrl(it) }
    val applicationId = Validation.applicationId(text(Keys.APPLICATION_ID))
    val largeImage = optional(Keys.LARGE_IMAGE)
    val largeText = optional(Keys.LARGE_TEXT)
    val smallImage = optional(Keys.SMALL_IMAGE)
    val smallText = optional(Keys.SMALL_TEXT)
    val buttons =
        listOf(Keys.BUTTON1_LABEL to Keys.BUTTON1_URL, Keys.BUTTON2_LABEL to Keys.BUTTON2_URL).mapNotNull { (label, url) ->
            val l = optional(label) ?: return@mapNotNull null
            val u = optional(url)?.takeIf { Validation.isWebUrl(it) } ?: return@mapNotNull null
            RpcButton(l, u)
        }
    val timestampMode = TimestampMode.of(settings.getString(Keys.TIMESTAMP_MODE, null))
    val timestampAnchor = settings.getLong(Keys.TIMESTAMP_ANCHOR, 0L)
    val startedAt = settings.getLong(Keys.STARTED_AT, 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    val partySize = text(Keys.PARTY_SIZE).toLongOrNull()
    val partyMax = text(Keys.PARTY_MAX).toLongOrNull()
    val partyId = optional(Keys.PARTY_ID)

    /** [current, max] when both are set and make sense. */
    val partySizes: List<Long>? get() {
        val size = partySize ?: return null
        val max = partyMax ?: return null
        return if (size in 0..max && max > 0) listOf(size, max) else null
    }

    /** Image values in use; images need an application ID. */
    val imageValues get() = if (applicationId == null) emptyList() else listOfNotNull(largeImage, smallImage)
}

internal object Validation {
    const val TEXT_MAX = 128
    const val BUTTON_LABEL_MAX = 32
    const val URL_MAX = 512

    val SNOWFLAKE: Pattern = Pattern.compile("^\\d{17,20}$")

    fun applicationId(text: String): Long? {
        if (!SNOWFLAKE.matcher(text).matches()) return null
        return text.toLongOrNull()?.takeIf { it > 0 }
    }

    fun isWebUrl(text: String): Boolean {
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null
    }

    fun looksLikeUrl(text: String) = text.contains("://") || text.startsWith("www.")

    /** Discord only shows Streaming for Twitch and YouTube links. */
    fun isStreamUrl(text: String): Boolean {
        if (!isWebUrl(text)) return false
        val host = Uri.parse(text).host.orEmpty().lowercase()
        return host == "twitch.tv" || host.endsWith(".twitch.tv") ||
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    private val UNIT_PART = Pattern.compile("(\\d{1,6})\\s*([dhms])")
    private val UNITS = Pattern.compile("^(\\d{1,6}\\s*[dhms]\\s*)+$")
    private val CLOCK = Pattern.compile("^(\\d{1,6}):(\\d{1,2})(?::(\\d{1,2}))?$")
    private val NUMBER = Pattern.compile("^\\d{1,7}$")
    private const val MAX_DURATION = 3650L * 24 * 60 * 60 * 1000

    /**
     * Parses "1h 30m", "2d", "90m", "45s", "1:30" (h:mm), "1:30:00" (h:mm:ss) or a plain number of minutes.
     * Returns milliseconds, or null if the text isn't a positive duration.
     */
    fun parseDuration(input: String): Long? {
        val text = input.trim().lowercase()
        if (text.isEmpty()) return null
        val total: Long = when {
            NUMBER.matcher(text).matches() -> text.toLongOrNull()?.times(60_000L) ?: return null
            text.contains(':') -> {
                val m = CLOCK.matcher(text)
                if (!m.matches()) return null
                val h = m.group(1)!!.toLongOrNull() ?: return null
                val min = m.group(2)!!.toLong()
                val sec = m.group(3)?.toLong() ?: 0L
                if (min > 59 || sec > 59) return null
                ((h * 60 + min) * 60 + sec) * 1000
            }
            else -> {
                if (!UNITS.matcher(text).matches()) return null
                val m = UNIT_PART.matcher(text)
                var sum = 0L
                while (m.find()) {
                    sum += m.group(1)!!.toLong() * when (m.group(2)) {
                        "d" -> 86_400_000L
                        "h" -> 3_600_000L
                        "m" -> 60_000L
                        else -> 1_000L
                    }
                }
                sum
            }
        }
        return total.takeIf { it in 1..MAX_DURATION }
    }
}
