package com.github.yutaplug.fixes.screenrefresh

import android.content.Context
import android.os.Build
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.discord.utilities.font.FontUtils
import com.github.yutaplug.fixes.Fix

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
 */
internal class ScreenRefreshFix :
    Fix(
        "ScreenRefreshFix",
        "Screen refresh",
        "Fixes the app reloading and jumping back to the start of the chat or settings after you close " +
            "an image, a video or a settings page. This only happens on some phones.",
    ) {
    override fun start(context: Context) {
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
                if (activity.r != locale) return@PreHook
                logger.info(
                    "Kept ${activity.javaClass.simpleName} open: Android changed its language to " +
                        "${currentLocale(activity)}, putting $locale back",
                )
                call.args[1] = false
            },
        )
        // Diagnostics: log the remaining reasons a screen relaunches itself, so reports from
        // affected phones show whether it was the theme or the font scale instead.
        patcher.patch(
            AppActivity::class.java,
            "i",
            arrayOf(AppActivity::class.java, java.lang.Boolean.TYPE, Integer.TYPE, Any::class.java),
            Hook { call ->
                val activity = call.args[0] as? AppActivity ?: return@Hook
                try {
                    val settings = StoreStream.getUserSettingsSystem()
                    val system = FontUtils.INSTANCE.getSystemFontScaleInt(activity.contentResolver)
                    logger.info(
                        "${activity.javaClass.simpleName} relaunched: language ${currentLocale(activity)} " +
                            "(setting ${settings.locale}, applied ${activity.r}), font scale ${activity.q} " +
                            "(setting ${settings.fontScale}, system $system), theme setting ${settings.theme}",
                    )
                } catch (error: Throwable) {
                    logger.error("Could not log relaunch", error)
                }
            },
        )
    }

    override fun stop(context: Context) {}

    @Suppress("DEPRECATION")
    private fun currentLocale(activity: AppActivity): String {
        val configuration = activity.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= 24) configuration.locales[0] else configuration.locale
        return locale?.toLanguageTag() ?: "none"
    }
}
