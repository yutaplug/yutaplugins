package com.github.yutaplug.fixes.leaderboard

import org.json.JSONObject
import java.text.NumberFormat

/**
 * The `leaderboard` object of a guild space system message (message type 69, `guild_space_data`).
 * Validated and formatted like Discord's web app.
 */
internal class LeaderboardEvent(
    val event: Int,
    val stat: Int,
    val userId: Long,
    val secondaryUserId: Long?,
    val value: Long,
    /** Game time in milliseconds. */
    val secondaryValue: Long,
) {
    /** The message text, with [NAME] and [PREVIOUS_NAME] where the user names go. */
    fun template(): String? {
        val gameTime = gameTime()
        val count = when (stat) {
            STAT_DAYS_PLAYED -> plural(value, "game day", "game days")
            STAT_UNIQUE_GAMES_PLAYED -> plural(value, "unique game", "unique games")
            else -> null
        }
        val amount = if (count == null) gameTime else "$count and $gameTime"
        return when (event) {
            COMPETITION_ENDED -> "$NAME won this week’s competition with $amount of game time."
            COMPETITION_STARTED -> {
                val goal = when (stat) {
                    STAT_HOURS_PLAYED -> "game time"
                    STAT_DAYS_PLAYED -> "game days"
                    else -> "unique games played"
                }
                "A new leaderboard begins! Who will be this week’s winner with the most $goal? " +
                    "So far $NAME is in the lead."
            }
            LEADER_CHANGED -> "$NAME took 1st Place from $PREVIOUS_NAME on the leaderboard with $amount of game time."
            else -> null
        }
    }

    private fun gameTime(): String {
        val totalMinutes = maxOf(secondaryValue, 0L) / 60_000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours == 0L) "${minutes}m" else "${hours}h ${minutes}m"
    }

    private fun plural(count: Long, one: String, other: String) =
        "${NumberFormat.getInstance().format(count)} ${if (count == 1L) one else other}"

    companion object {
        const val NAME = "\u0000name\u0000"
        const val PREVIOUS_NAME = "\u0000previous\u0000"

        private const val COMPETITION_ENDED = 1
        private const val COMPETITION_STARTED = 2
        const val LEADER_CHANGED = 3

        private const val STAT_HOURS_PLAYED = 1
        private const val STAT_DAYS_PLAYED = 2
        private const val STAT_UNIQUE_GAMES_PLAYED = 3

        /** Parses `guild_space_data`; null unless it holds a leaderboard event this version knows. */
        fun parse(guildSpaceData: String): LeaderboardEvent? = try {
            val data = JSONObject(guildSpaceData).optJSONObject("leaderboard")
            if (data == null) {
                null
            } else {
                val event = data.optInt("event", 0)
                val stat = data.optInt("stat", 0)
                val userId = snowflake(data, "user_id")
                if (event < COMPETITION_ENDED ||
                    event > LEADER_CHANGED ||
                    stat < STAT_HOURS_PLAYED ||
                    stat > STAT_UNIQUE_GAMES_PLAYED ||
                    userId == null ||
                    !data.has("value") ||
                    data.isNull("value")
                ) {
                    null
                } else {
                    val value = data.optLong("value", 0L)
                    val secondary = data.optLong("secondary_value", 0L)
                    LeaderboardEvent(
                        event,
                        stat,
                        userId,
                        snowflake(data, "secondary_user_id"),
                        value,
                        // Like the web app: without a game time, the value is the game time.
                        if (secondary > 0L) secondary else value,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }

        private fun snowflake(data: JSONObject, key: String): Long? {
            if (!data.has(key) || data.isNull(key)) return null
            return try {
                java.lang.Long.parseLong(data.get(key).toString())
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
