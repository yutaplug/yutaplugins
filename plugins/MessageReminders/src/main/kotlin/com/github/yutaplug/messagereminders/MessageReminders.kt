package com.github.yutaplug.messagereminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.channel.ChannelUtils
import com.discord.gateway.io.IncomingParser
import com.discord.models.domain.Model
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.intent.IntentUtils
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.user.WidgetUserMentions
import com.google.gson.Gson
import com.google.gson.JsonElement
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class MessageReminders : Plugin() {
    internal val store = ReminderStore(logger) { mainHandler.post { onRemindersChanged() } }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val rowId = View.generateViewId()
    private val actionRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val menuItemId = View.generateViewId()
    private val toolbars = Collections.newSetFromMap(WeakHashMap<Toolbar, Boolean>())
    private val pages = Collections.newSetFromMap(WeakHashMap<RemindersPage, Boolean>())
    private var receiver: BroadcastReceiver? = null
    private val tick = Runnable { onRemindersChanged() }

    @Volatile internal var active = false
        private set

    init {
        settingsTab = SettingsTab(RemindersPage::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        active = true
        patchGateway()
        patchMessageActions()
        patchMentionsToolbar()
        registerAlarmReceiver()
        refreshInBackground()
    }

    // region Data

    internal fun refreshInBackground(maxAgeMs: Long = 0L, done: (Boolean) -> Unit = {}) {
        Utils.threadPool.execute {
            val ok = store.refresh(maxAgeMs)
            mainHandler.post { if (active) done(ok) }
        }
    }

    /**
     * 126.21 doesn't know the saved message events, so its parser skips them. Read their
     * payload before that happens. Only fields after `op` are handled here (Discord sends
     * `t`, `s`, `op` and then `d`), so the parser's own handling of `t` and `op` is kept.
     */
    private fun patchGateway() {
        val typeField = IncomingParser::class.java.getDeclaredField("type").apply { isAccessible = true }
        val opcodeField = IncomingParser::class.java.getDeclaredField("opcode").apply { isAccessible = true }
        val seqField = IncomingParser::class.java.getDeclaredField("seq").apply { isAccessible = true }
        patcher.patch(
            IncomingParser::class.java.getDeclaredMethod("assignField", Model.JsonReader::class.java),
            PreHook { frame ->
                val parser = frame.thisObject
                val type = typeField.get(parser) as? String ?: return@PreHook
                if (type != EVENT_SAVED && type != EVENT_UNSAVED) return@PreHook
                if (opcodeField.get(parser) == null) return@PreHook
                val reader = frame.args[0] as Model.JsonReader
                frame.result = null
                when (reader.nextName()) {
                    "d" -> {
                        val json = gson.d<JsonElement>(reader.`in`, JsonElement::class.java)?.toString() ?: return@PreHook
                        try {
                            val payload = JSONObject(json)
                            if (type == EVENT_SAVED) store.onSaved(payload) else store.onDeleted(payload)
                        } catch (error: Throwable) {
                            logger.error("Failed to read $type", error)
                        }
                    }
                    "s" -> seqField.set(parser, reader.nextIntOrNull())
                    else -> reader.skipValue()
                }
            },
        )
    }

    private fun onRemindersChanged() {
        if (!active) return
        checkDue()
        scheduleNext()
        pages.toList().forEach { it.render() }
    }

    // endregion

    // region Message actions

    /** Adds "Create Reminder" (or "Edit Reminder") below Pin in the message actions sheet. */
    private fun patchMessageActions() {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { frame ->
                val actions = frame.thisObject as WidgetChatListActions
                val model = frame.args[0] as WidgetChatListActions.Model
                val channel = model.channel ?: return@Hook
                val message = model.message
                val container = actions.view?.findViewById<LinearLayout>(
                    Utils.getResId("dialog_chat_actions_container", "id"),
                ) ?: return@Hook
                val row = container.findViewById<TextView>(rowId) ?: createActionRow(container) ?: return@Hook
                val channelId = channel.k()
                val messageId = message.id
                row.visibility = if (message.isLocal || messageId <= 0L) View.GONE else View.VISIBLE
                updateActionRow(row, channelId, messageId)
                // Pick up reminders set on other clients since the last load.
                if (!store.loaded || row.visibility == View.VISIBLE) {
                    refreshInBackground(REFRESH_MAX_AGE_MS) {
                        if (row.isAttachedToWindow) updateActionRow(row, channelId, messageId)
                    }
                }
                row.setOnClickListener {
                    if (!active) return@setOnClickListener
                    val author = message.author?.let { CoreUser(it) }?.let { it.username }
                    val preview = message.content?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
                    actions.dismiss()
                    RemindMeSheet.show(this, Reminder(channelId, messageId, channel.i(), 0L, author, preview), jump = false)
                }
            },
        )
    }

    private fun createActionRow(container: LinearLayout): TextView? {
        val template = LayoutInflater.from(container.context)
            .inflate(Utils.getResId("widget_chat_list_actions", "layout"), null) as ViewGroup
        val row = template.findViewById<TextView>(Utils.getResId("dialog_chat_actions_reply", "id")) ?: return null
        (row.parent as ViewGroup).removeView(row)
        row.id = rowId
        val icon = drawable(container.context, "ic_clock_24dp")
        val arrow = drawable(container.context, "exo_ic_chevron_right")
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, arrow, null)
        val pin = container.findViewById<View>(Utils.getResId("dialog_chat_actions_pin", "id"))
        container.addView(row, if (pin == null) container.childCount else container.indexOfChild(pin) + 1)
        actionRows.add(row)
        return row
    }

    private fun updateActionRow(row: TextView, channelId: Long, messageId: Long) {
        row.text = if (store.get(channelId, messageId) != null) "Edit Reminder" else "Create Reminder"
    }

    // endregion

    // region Recent Mentions entry

    /**
     * A clock button next to the filter in the Recent Mentions toolbar, opening your reminders.
     * Discord rebuilds that menu in configureToolbar (also when the filter changes), so add it each time.
     */
    private fun patchMentionsToolbar() {
        patcher.patch(
            WidgetUserMentions::class.java.getDeclaredMethod("configureToolbar", String::class.java),
            Hook { frame ->
                val fragment = frame.thisObject as WidgetUserMentions
                val toolbar = fragment.appActivity?.u ?: return@Hook
                val menu = toolbar.menu ?: return@Hook
                if (menu.findItem(menuItemId) != null) return@Hook
                val context = toolbar.context
                val icon = drawable(context, "ic_clock_24dp")?.mutate()?.apply {
                    val tintAttr = Utils.getResId("colorInteractiveNormal", "attr")
                    if (tintAttr != 0) setTint(ColorCompat.getThemedColor(context, tintAttr))
                }
                menu.add(Menu.NONE, menuItemId, Menu.NONE, "Reminders").apply {
                    setIcon(icon)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    // The item's own listener runs before the toolbar's, which only knows Discord's items.
                    setOnMenuItemClickListener {
                        if (active && fragment.isAdded) Utils.openPageWithProxy(fragment.requireContext(), RemindersPage(this@MessageReminders))
                        true
                    }
                }
                toolbars.add(toolbar)
            },
        )
    }

    internal fun register(page: RemindersPage) {
        pages.add(page)
    }

    // endregion

    // region Due reminders

    private val notificationManager: NotificationManager
        get() = Utils.appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val alarmIntent: PendingIntent
        get() {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val intent = Intent(ACTION_DUE).setPackage(Utils.appContext.packageName)
            return PendingIntent.getBroadcast(Utils.appContext, 0, intent, flags)
        }

    /** The alarm wakes us in Doze while Discord's process is alive; reminders missed while it was dead fire on start. */
    private fun registerAlarmReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mainHandler.post { onRemindersChanged() }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Utils.appContext.registerReceiver(receiver, IntentFilter(ACTION_DUE), Context.RECEIVER_NOT_EXPORTED)
            } else {
                Utils.appContext.registerReceiver(receiver, IntentFilter(ACTION_DUE))
            }
            this.receiver = receiver
        } catch (error: Throwable) {
            logger.error("Failed to register the reminder alarm", error)
        }
    }

    private fun scheduleNext() {
        mainHandler.removeCallbacks(tick)
        val alarms = Utils.appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(alarmIntent)
        val now = System.currentTimeMillis()
        val next = store.all().firstOrNull { it.dueAt > now }?.dueAt ?: return
        mainHandler.postDelayed(tick, next - now + 250)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent)
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, next, alarmIntent)
            }
        } catch (error: Throwable) {
            logger.error("Failed to schedule the reminder alarm", error)
        }
    }

    /** Notifies once for each reminder that became due. Main thread only. */
    private fun checkDue() {
        if (!store.loaded) return
        val now = System.currentTimeMillis()
        val reminders = store.all()
        val due = reminders.filter { it.dueAt <= now }.map { "${it.key}@${it.dueAt}" to it }
        val firstRun = !settings.exists(KEY_NOTIFIED)
        val notified = settings.getString(KEY_NOTIFIED, "").split(',').filter { it.isNotEmpty() }.toMutableSet()
        // Forget reminders that are gone or were moved, so the list stays small.
        val dueIds = due.map { it.first }.toSet()
        var changed = notified.retainAll(dueIds)
        for ((id, reminder) in due) {
            if (!notified.add(id)) continue
            changed = true
            // On the first run, reminders that were already overdue were handled by other clients.
            if (!firstRun) notify(reminder)
        }
        // Withdraw notifications of reminders that were completed or moved.
        val live = reminders.map { notificationId(it) }.toSet()
        posted.toList().filter { it !in live }.forEach { id ->
            posted.remove(id)
            runCatching { notificationManager.cancel(NOTIFICATION_TAG, id) }
        }
        if (changed || firstRun) settings.setString(KEY_NOTIFIED, notified.joinToString(","))
    }

    private val posted = HashSet<Int>()

    private fun notificationId(reminder: Reminder) = reminder.key.hashCode()

    private fun notify(reminder: Reminder) {
        try {
            val context = Utils.appContext
            val manager = notificationManager
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return
            @Suppress("DEPRECATION")
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Message reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "A message reminder you set is due"
                    },
                )
                Notification.Builder(context, CHANNEL_ID)
            } else {
                Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
            }
            val id = notificationId(reminder)
            val intent = IntentUtils.RouteBuilders.selectChannel(reminder.channelId, reminder.guildId, reminder.messageId)
                .setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val icon = Utils.getResId("ic_notification_message_24dp", "drawable")
            val text = describe(reminder)
            val place = placeOf(reminder)
            builder
                .setSmallIcon(if (icon != 0) icon else android.R.drawable.stat_notify_chat)
                .setContentTitle(if (place != null) "Reminder from $place" else "Message reminder")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(reminder.dueAt)
                .setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
            manager.notify(NOTIFICATION_TAG, id, builder.build())
            posted.add(id)
        } catch (error: Throwable) {
            logger.error("Failed to show a reminder notification", error)
        }
    }

    // endregion

    // region Helpers

    /** "Author: message", or whatever part of it is known. */
    internal fun describe(reminder: Reminder): String = when {
        reminder.author != null && reminder.preview != null -> "${reminder.author}: ${reminder.preview}"
        reminder.preview != null -> reminder.preview
        reminder.author != null -> "Message from ${reminder.author}"
        else -> "A message you saved"
    }

    /** "#channel" or the DM's name, if the channel is known. */
    internal fun placeOf(reminder: Reminder): String? = try {
        StoreStream.getChannels().getChannel(reminder.channelId)?.let {
            ChannelUtils.d(it, Utils.appContext, true).toString().takeIf { name -> name.isNotEmpty() }
        }
    } catch (_: Throwable) {
        null
    }

    internal fun jumpTo(context: Context, reminder: Reminder) {
        val intent = IntentUtils.RouteBuilders.selectChannel(reminder.channelId, reminder.guildId, reminder.messageId)
            .setPackage(context.packageName)
        context.startActivity(intent)
    }

    private fun drawable(context: Context, name: String) =
        Utils.getResId(name, "drawable").takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }

    // endregion

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            (Utils.appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent)
        }
        receiver?.let { runCatching { Utils.appContext.unregisterReceiver(it) } }
        receiver = null
        posted.forEach { id -> runCatching { notificationManager.cancel(NOTIFICATION_TAG, id) } }
        posted.clear()
        actionRows.toList().forEach { (it.parent as? ViewGroup)?.removeView(it) }
        actionRows.clear()
        toolbars.forEach { runCatching { it.menu?.removeItem(menuItemId) } }
        toolbars.clear()
        pages.clear()
        store.clear()
    }

    private companion object {
        const val EVENT_SAVED = "SAVED_MESSAGE_CREATE"
        const val EVENT_UNSAVED = "SAVED_MESSAGE_DELETE"
        const val ACTION_DUE = "com.github.yutaplug.messagereminders.DUE"
        const val CHANNEL_ID = "messagereminders_due"
        const val NOTIFICATION_TAG = "MessageReminders"
        const val KEY_NOTIFIED = "notified"
        const val REFRESH_MAX_AGE_MS = 5 * 60_000L
    }
}
