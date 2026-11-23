package com.github.yutaplug.bettermessagelogger

import android.content.Context
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils

/**
 * Compatibility with Juby210's CustomTimestamps plugin, which hooks [TimeUtils.toReadableTimeString] to apply the
 * user's own date format. When it is installed and enabled, every timestamp this plugin shows goes through that
 * method, so it picks up the custom format. Otherwise the caller's own formatting is used.
 */
internal object Timestamps {
    private const val CUSTOM_TIMESTAMPS = "CustomTimestamps"

    private fun customTimestampsEnabled(): Boolean =
        try {
            PluginManager.plugins.containsKey(CUSTOM_TIMESTAMPS) && PluginManager.isPluginEnabled(CUSTOM_TIMESTAMPS)
        } catch (_: Throwable) {
            false
        }

    /** The timestamp in CustomTimestamps' format, or null when that plugin isn't active. */
    fun custom(time: Long, context: Context? = null): String? {
        if (!customTimestampsEnabled()) return null
        return try {
            TimeUtils.toReadableTimeString(context ?: Utils.appContext, time, ClockFactory.get()).toString()
        } catch (_: Throwable) {
            null
        }
    }
}
