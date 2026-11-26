package com.github.yutaplug.fixes.updater

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.aliucord.Utils

/**
 * The updater's notices, as Android notifications posted by the app itself (not push notifications): they
 * show reliably, never cover the chat, and can be swiped away. Tapping one opens Discord, where
 * [handle] picks up what it was for. Off by default; the in-app [UpdatePopups] are the default.
 */
internal object UpdateNotifications {
    private const val CHANNEL = "fixes_updater"
    private const val ID_FOUND = 0x5F1D
    private const val ID_UPDATED = 0x5F1E
    private const val EXTRA = "com.github.yutaplug.fixes.UPDATER_ACTION"
    private const val OPEN = "open"
    private const val INSTALL = "install"
    private const val CHANGELOG = "changelog"

    /** What the last "updates found" notification offers to install, while this process lives. */
    @Volatile private var toInstall: Pair<List<PluginUpdate>, CoreUpdate?>? = null

    /** What the last "updated" notification's changelog shows, while this process lives. */
    @Volatile private var updated: List<Installed>? = null

    /** Updates found on startup. Tapping opens the Updater; Update installs [plugins] and [core] right away. */
    fun updatesFound(title: String, text: String, plugins: List<PluginUpdate>, core: CoreUpdate?) {
        val canInstall = plugins.isNotEmpty() || core != null
        toInstall = if (canInstall) plugins to core else null
        post(ID_FOUND, title, text, OPEN, if (canInstall) "Update" to INSTALL else null)
    }

    /** Updates installed before this restart. Tapping shows their changelog. */
    fun updated(title: String, done: List<Installed>) {
        updated = done
        post(ID_UPDATED, title, "Tap to see what's new.", CHANGELOG, null)
    }

    private fun post(id: Int, title: String, text: String, tap: String, button: Pair<String, String>?) {
        if (!Updater.setting(Updater.SHOW_NOTIFICATIONS, false)) {
            Updater.logger.info("Notification \"$title\" not shown: phone notifications are turned off")
            return
        }
        val context = Utils.appContext
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
                // Notifications are blocked for Discord (e.g. Android 13+ without the permission).
                Updater.logger.info("Notification \"$title\" blocked, showing a toast instead")
                Utils.showToast("$title: $text", true)
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Aliucord updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Updates for Aliucord and its plugins, from the Fixes plugin's updater."
                        setSound(null, null)
                    },
                )
            }
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
            builder
                .setSmallIcon(Utils.getResId("ic_notification_24dp", "drawable").takeIf { it != 0 } ?: context.applicationInfo.icon)
                .setColor(UpdaterUi.BRAND.takeIf { it != 0 } ?: Color.rgb(88, 101, 242))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(intent(context, tap, id * 4))
            if (button != null) {
                @Suppress("DEPRECATION")
                builder.addAction(0, button.first, intent(context, button.second, id * 4 + 1))
            }
            manager.notify(id, builder.build())
            Updater.logger.info("Notification \"$title\" posted")
        } catch (e: Throwable) {
            Updater.logger.error("Could not post a notification", e)
            Utils.showToast("$title: $text", true)
        }
    }

    /** Opens Discord's main screen with [action], which [handle] runs once it's in front. */
    private fun intent(context: Context, action: String, requestCode: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        launch.putExtra(EXTRA, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, requestCode, launch, flags)
    }

    /** Runs what a tapped notification asked for, if [intent] came from one. */
    fun handle(activity: Activity, intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA) ?: return
        intent.removeExtra(EXTRA)
        val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        Updater.logger.info("Notification action: $action")
        // After Discord has finished opening the screen.
        Utils.mainThread.postDelayed({
            when (action) {
                INSTALL -> {
                    manager?.cancel(ID_FOUND)
                    val (plugins, core) = toInstall ?: run {
                        // The app was closed since; the Updater checks again when it opens.
                        UpdaterFix.openUpdater(activity)
                        return@postDelayed
                    }
                    toInstall = null
                    Updater.installNow(activity, plugins, core)
                }
                CHANGELOG -> {
                    val list = updated
                    if (list != null) Updater.showChangelog(activity, list) else UpdaterFix.openUpdater(activity)
                }
                else -> UpdaterFix.openUpdater(activity)
            }
        }, 500L)
    }
}
