package com.github.yutaplug.fixes.gifdownload

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/** Notification failures must never prevent downloading or publishing a GIF. */
internal class KlipyDownloadNotification(
    private val context: Context,
    private val fileName: String,
    private val onFailure: (Exception) -> Unit,
) {
    private val id = nextId.incrementAndGet()
    private var disabled = false

    fun progress(stage: String, current: Int = 0, total: Int = 0) {
        post("Downloading Klipy GIF", "$fileName — $stage", true, current, total)
    }

    fun complete(savedName: String) {
        post("GIF downloaded", "Downloads/$savedName", false)
    }

    fun failed() {
        post("Klipy GIF download failed", "See Aliucord Debug Logs for details", false)
    }

    @Synchronized
    fun cancel() {
        disabled = true
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(TAG, id)
        } catch (error: Exception) {
            onFailure(error)
        }
    }

    @Synchronized
    private fun post(title: String, text: String, ongoing: Boolean, current: Int = 0, total: Int = 0) {
        if (disabled) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
                disabled = true
                return
            }
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Klipy GIF downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context).setPriority(Notification.PRIORITY_LOW)
            }
            builder.setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setProgress(if (ongoing) total else 0, if (ongoing) current else 0, ongoing && total == 0)
            if (!ongoing) {
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                builder.setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
            }
            manager.notify(TAG, id, builder.build())
        } catch (error: Exception) {
            disabled = true
            onFailure(error)
        }
    }

    private companion object {
        const val CHANNEL = "gifdownloadfix_klipy_downloads"
        const val TAG = "GifDownloadFix.Klipy"
        val nextId = AtomicInteger()
    }
}
