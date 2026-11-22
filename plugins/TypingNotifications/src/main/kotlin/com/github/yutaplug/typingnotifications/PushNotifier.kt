package com.github.yutaplug.typingnotifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.aliucord.Utils
import com.discord.utilities.intent.IntentUtils

/** Android notifications, one per person and channel. */
internal class PushNotifier(private val onError: (String, Throwable) -> Unit) {
    /** Notification ids this plugin posted, so they can be withdrawn. Main thread only. */
    private val posted = HashSet<Int>()

    private val manager: NotificationManager
        get() = Utils.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun show(typer: Typer) {
        try {
            val context = Utils.appContext
            val manager = manager
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Typing notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Someone started typing"
                    },
                )
                Notification.Builder(context, CHANNEL)
            } else {
                Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
            }
            val id = id(typer.channelId, typer.userId)
            val intent = IntentUtils.RouteBuilders.selectChannel(typer.channelId, typer.guildId, null)
                .setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val icon = Utils.getResId("ic_notification_message_24dp", "drawable")
            builder
                .setSmallIcon(if (icon != 0) icon else android.R.drawable.stat_notify_chat)
                .setContentTitle(typer.name)
                .setContentText(if (typer.place.isEmpty()) "is typing" else "is typing in ${typer.place}")
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setGroup(CHANNEL)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
            manager.notify(TAG, id, builder.build())
            posted.add(id)
        } catch (error: Throwable) {
            onError("show typing push notification", error)
        }
    }

    /** Withdraws the notification once the person's message arrives; Discord notifies for that itself. */
    fun cancel(channelId: Long, userId: Long) {
        val id = id(channelId, userId)
        if (!posted.remove(id)) return
        runCatching { manager.cancel(TAG, id) }
    }

    fun cancelAll() {
        posted.forEach { id -> runCatching { manager.cancel(TAG, id) } }
        posted.clear()
    }

    private fun id(channelId: Long, userId: Long) = (channelId * 31 + userId).hashCode()

    private companion object {
        const val CHANNEL = "typingnotifications_typing"
        const val TAG = "TypingNotifications"
    }
}
