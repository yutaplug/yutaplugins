package com.github.yutaplug.fixes.appactivity

import android.content.Context
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.discord.app.AppActivity
import com.github.yutaplug.fixes.Fix

/**
 * Aliucord only updates [Utils.appActivity] when a screen is created. Discord opens settings, search,
 * the image viewer and plugin pages as their own screens, so after closing one it keeps pointing at
 * the closed screen. Plugins that open menus through it then do nothing, because Discord's dialogs
 * swallow the error. Point it at whichever screen comes back to the front instead.
 */
internal class AppActivityFix : Fix(
    "AppActivityFix",
    "Plugin menus",
    "Fixes menus and popups from plugins doing nothing after you open and close another screen, " +
        "like settings, search or an image.",
) {
    override fun start(context: Context) {
        patcher.patch(
            AppActivity::class.java,
            "onResume",
            emptyArray(),
            Hook { call -> Utils.appActivity = call.thisObject as AppActivity },
        )
    }

    override fun stop(context: Context) {}
}
