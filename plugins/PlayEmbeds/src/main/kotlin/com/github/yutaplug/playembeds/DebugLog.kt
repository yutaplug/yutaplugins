package com.github.yutaplug.playembeds

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import java.util.Locale

/**
 * The last few hundred playback and fullscreen events, kept in memory so a
 * user can copy them from the plugin settings into a bug report.
 */
internal object DebugLog {
    private const val MAX_LINES = 300
    private val lines = java.util.ArrayDeque<String>()

    @Synchronized
    fun log(message: String) {
        val seconds = SystemClock.uptimeMillis() / 1000.0
        lines.addLast(String.format(Locale.US, "%.3f %s", seconds, message))
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String {
        val header = "PlayEmbeds log, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
        return if (lines.isEmpty()) "$header\n(empty)" else header + "\n" + lines.joinToString("\n")
    }

    /** [error] and each of its causes, with where the innermost one was thrown. */
    fun causes(error: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = error
        var innermost = error
        while (current != null && parts.size < MAX_CAUSES) {
            parts.add(current.toString())
            innermost = current
            current = current.cause?.takeIf { it !== current }
        }
        val frames = innermost.stackTrace.take(MAX_FRAMES).joinToString(" < ")
        return parts.joinToString(" | caused by: ") + " | at " + frames
    }

    private const val MAX_CAUSES = 8
    private const val MAX_FRAMES = 6

    fun name(activity: Activity?): String =
        if (activity == null) "none" else activity.javaClass.simpleName + "@" + Integer.toHexString(System.identityHashCode(activity))
}
