package com.github.yutaplug.fixes.screenrefresh

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.discord.utilities.font.FontUtils
import com.github.yutaplug.fixes.Fix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every time a screen comes back to the front, AppActivity compares the language in its resources
 * with Discord's language setting, and if they differ it finishes and relaunches itself through
 * `b(locale, true)`. That's meant for when the user changes the language. On some Android 14+
 * builds and OEM skins (OneUI, OxygenOS, GrapheneOS), the system resets the resources' locale
 * when the screen resumes, or adds regional-preference extensions (`en-US-u-fw-mon`). The check
 * then fails every time you come back from the image viewer or settings, and the chat reloads at
 * the top (Aliucord issues #429 and #776).
 *
 * When the language setting hasn't changed since this screen applied it (`r`), apply it again
 * without relaunching. A real language change still relaunches the screen like before.
 *
 * Temporary: it also records every screen's lifecycle to [RefreshLog], which the user can save from
 * the Fixes settings, because the fix doesn't work for everyone yet.
 */
internal class ScreenRefreshFix :
    Fix(
        "ScreenRefreshFix",
        "Screen refresh",
        "Fixes the app reloading and jumping back to the start of the chat or settings after you close " +
            "an image, a video or a settings page. This only happens on some phones.",
        // Temporary, until a user's log shows why it doesn't work for everyone yet.
        enabledByDefault = false,
        warning = "Not working yet, so it's off by default. Only turn it on if you were asked to, " +
            "to save a screen refresh log.",
    ) {
    override fun start(context: Context) {
        RefreshLog.init(context)
        logProcessStart(context)

        patcher.patch(
            AppActivity::class.java,
            "b",
            arrayOf(String::class.java, java.lang.Boolean.TYPE),
            PreHook { call ->
                if (call.args[1] != true) return@PreHook
                val activity = call.thisObject as AppActivity
                val locale = call.args[0] as? String ?: return@PreHook
                // r is the language this screen applied when it last came to the front. A different
                // value means the user really changed it, so let Discord relaunch the screen.
                if (activity.r != locale) {
                    note("${name(activity)}: language setting changed from ${activity.r} to $locale, relaunching")
                    return@PreHook
                }
                note(
                    "Kept ${name(activity)} open: Android changed its language to " +
                        "${currentLocale(activity)}, putting $locale back",
                )
                call.args[1] = false
            },
        )
        // Logs the remaining reasons a screen relaunches itself (theme, font scale, language on resume).
        patcher.patch(
            AppActivity::class.java,
            "i",
            arrayOf(AppActivity::class.java, java.lang.Boolean.TYPE, Integer.TYPE, Any::class.java),
            Hook { call ->
                val activity = call.args[0] as? AppActivity ?: return@Hook
                try {
                    val settings = StoreStream.getUserSettingsSystem()
                    val system = FontUtils.INSTANCE.getSystemFontScaleInt(activity.contentResolver)
                    note(
                        "${name(activity)} relaunched by Discord: language ${currentLocale(activity)} " +
                            "(setting ${settings.locale}, applied ${activity.r}), font scale ${activity.q} " +
                            "(setting ${settings.fontScale}, system $system), theme setting ${settings.theme}, " +
                            "called from ${caller()}",
                    )
                } catch (error: Throwable) {
                    logger.error("Could not log relaunch", error)
                }
            },
        )
        patcher.patch(
            AppActivity::class.java,
            "onCreate",
            arrayOf(Bundle::class.java),
            Hook { call ->
                val activity = call.thisObject as AppActivity
                val restored = call.args[0] != null
                val recreate = try {
                    activity.intent?.getBooleanExtra("INTENT_RECREATE", false) == true
                } catch (_: Throwable) {
                    false
                }
                note(
                    "${name(activity)} created" +
                        (if (restored) " (restored from saved state)" else "") +
                        (if (recreate) " (recreate requested)" else "") +
                        ": ${describe(activity.resources.configuration)}",
                )
            },
        )
        patcher.patch(
            AppActivity::class.java,
            "onResume",
            emptyArray(),
            PreHook { call ->
                val activity = call.thisObject as AppActivity
                val setting = try {
                    StoreStream.getUserSettingsSystem().locale
                } catch (_: Throwable) {
                    "?"
                }
                note(
                    "${name(activity)} resumed: language ${currentLocale(activity)} (setting $setting, " +
                        "applied ${activity.r}), default ${Locale.getDefault().toLanguageTag()}",
                )
            },
        )
        patcher.patch(
            AppActivity::class.java,
            "onDestroy",
            emptyArray(),
            PreHook { call ->
                val activity = call.thisObject as AppActivity
                val changing = activity.isChangingConfigurations
                note(
                    "${name(activity)} destroyed: finishing ${activity.isFinishing}, " +
                        (if (changing) "recreated by Android for ${changes(activity.changingConfigurations)}" else "not a config change"),
                )
            },
        )
        patcher.patch(
            AppCompatActivity::class.java,
            "onConfigurationChanged",
            arrayOf(Configuration::class.java),
            PreHook { call ->
                val activity = call.thisObject as? AppActivity ?: return@PreHook
                val config = call.args[0] as? Configuration ?: return@PreHook
                note("${name(activity)} got a config change without relaunching: ${describe(config)}")
            },
        )
    }

    override fun stop(context: Context) {}

    private fun note(message: String) {
        logger.info(message)
        RefreshLog.add(message)
    }

    private fun logProcessStart(context: Context) {
        note("App started (process ${android.os.Process.myPid()}), Don't keep activities: ${dontKeepActivities(context)}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = settings.getLong(LAST_EXIT_KEY, 0L)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > last }
                .sortedBy { it.timestamp }
            for (exit in exits) {
                note(
                    "Earlier process ${exit.pid} ended at ${TIME.format(Date(exit.timestamp))}: " +
                        "${exitReason(exit.reason)}, importance ${exit.importance}" +
                        (exit.description?.let { " ($it)" } ?: ""),
                )
            }
            exits.lastOrNull()?.let { settings.setLong(LAST_EXIT_KEY, it.timestamp) }
        } catch (error: Throwable) {
            note("Could not read earlier process exits: $error")
        }
    }

    /** The text saved by "Save screen refresh log": device details, then the log. */
    fun exportText(context: Context): String {
        val out = StringBuilder()
        out.append("Screen refresh log (Fixes ").append(plugin.manifest.version).append(")\n")
        out.append("Saved: ").append(TIME.format(Date())).append('\n')
        out.append("Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT)
            .append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", build ").append(Build.DISPLAY).append('\n')
        try {
            val settings = StoreStream.getUserSettingsSystem()
            out.append("Discord settings: language ").append(settings.locale)
                .append(", font scale ").append(settings.fontScale)
                .append(", theme ").append(settings.theme).append('\n')
        } catch (_: Throwable) {
        }
        out.append("System: ").append(describe(Resources.getSystem().configuration)).append('\n')
        out.append("App: ").append(describe(context.resources.configuration)).append('\n')
        out.append("Don't keep activities: ").append(dontKeepActivities(context)).append('\n')
        out.append('\n')
        out.append(RefreshLog.read())
        return out.toString()
    }

    private fun name(activity: AppActivity): String = activity.javaClass.simpleName + "@" +
        Integer.toHexString(System.identityHashCode(activity))

    /** The Discord methods that called AppActivity.i, so the log shows which check relaunched the screen. */
    private fun caller(): String {
        val frames = Thread.currentThread().stackTrace
        val start = frames.indexOfFirst { it.className == AppActivity::class.java.name && it.methodName == "i" }
        if (start < 0) return "?"
        return frames.drop(start + 1)
            .filter { it.className.startsWith("com.discord") }
            .take(3)
            .joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
            .ifEmpty { "?" }
    }

    private fun dontKeepActivities(context: Context): String = try {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0) {
            "on"
        } else {
            "off"
        }
    } catch (_: Throwable) {
        "?"
    }

    @Suppress("DEPRECATION")
    private fun currentLocale(activity: AppActivity): String = localeOf(activity.resources.configuration)

    @Suppress("DEPRECATION")
    private fun localeOf(config: Configuration): String {
        val locale = if (Build.VERSION.SDK_INT >= 24) config.locales[0] else config.locale
        return locale?.toLanguageTag() ?: "none"
    }

    private fun describe(config: Configuration): String {
        val locales = if (Build.VERSION.SDK_INT >= 24) config.locales.toLanguageTags() else localeOf(config)
        val night = when (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> "dark"
            Configuration.UI_MODE_NIGHT_NO -> "light"
            else -> "?"
        }
        return "language $locales, font scale ${config.fontScale}, $night mode, density ${config.densityDpi}, " +
            "screen ${config.screenWidthDp}x${config.screenHeightDp}dp, " +
            (if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
    }

    private fun changes(mask: Int): String {
        val names = CONFIG_NAMES.filter { (bit, _) -> mask and bit != 0 }.map { it.second }
        return if (names.isEmpty()) "unknown (0x${Integer.toHexString(mask)})" else names.joinToString(", ")
    }

    private fun exitReason(reason: Int): String = when (reason) {
        1 -> "exited by itself"
        2 -> "killed by a signal"
        3 -> "killed for low memory"
        4 -> "crashed"
        5 -> "crashed in native code"
        6 -> "not responding"
        7 -> "failed to start"
        8 -> "dependency died"
        9 -> "too much resource use"
        10 -> "closed by the user"
        11 -> "killed by the user on request"
        12 -> "killed by the system"
        13 -> "killed for other reasons"
        14 -> "frozen"
        15 -> "package state changed"
        16 -> "package updated"
        else -> "unknown reason $reason"
    }

    private companion object {
        const val LAST_EXIT_KEY = "ScreenRefreshFix.lastExit"
        val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val CONFIG_NAMES = listOf(
            ActivityInfo.CONFIG_LOCALE to "language",
            ActivityInfo.CONFIG_FONT_SCALE to "font scale",
            ActivityInfo.CONFIG_UI_MODE to "dark mode/UI mode",
            ActivityInfo.CONFIG_DENSITY to "density",
            ActivityInfo.CONFIG_ORIENTATION to "orientation",
            ActivityInfo.CONFIG_SCREEN_SIZE to "screen size",
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE to "smallest screen size",
            ActivityInfo.CONFIG_SCREEN_LAYOUT to "screen layout",
            ActivityInfo.CONFIG_LAYOUT_DIRECTION to "layout direction",
            ActivityInfo.CONFIG_KEYBOARD to "keyboard",
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN to "keyboard hidden",
            ActivityInfo.CONFIG_NAVIGATION to "navigation",
            ActivityInfo.CONFIG_TOUCHSCREEN to "touchscreen",
            ActivityInfo.CONFIG_MCC to "mobile country code",
            ActivityInfo.CONFIG_MNC to "mobile network code",
            0x10000000 to "font weight",
            0x8000 to "grammatical gender",
            0x4000 to "color mode",
            0x80000000.toInt() to "app resources",
        )
    }
}
