package com.github.yutaplug.messagereminders

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.lytefast.flexinput.R
import java.util.Calendar

/**
 * Discord's "Remind Me" sheet: quick times, a custom date and time, and Mark Complete for an
 * existing reminder. Rows are Discord's own message action rows, so they match the actions sheet.
 */
internal class RemindMeSheet(
    private val plugin: MessageReminders,
    private val target: Reminder,
    private val jump: Boolean,
) : BottomSheet() {
    private var busy = false

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        val existing = plugin.store.get(target.channelId, target.messageId)

        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Remind Me"
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
        val subtitle = when {
            existing != null -> dueText(existing.dueAt)
            else -> plugin.describe(target).takeIf { target.preview != null || target.author != null }
        }
        if (subtitle != null) {
            addView(
                TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    text = subtitle
                    maxLines = 2
                    background = null
                },
            )
        }

        if (jump) {
            row(context, "Jump to Message", "ic_reply_24dp") {
                dismissAllowingStateLoss()
                plugin.jumpTo(context, existing ?: target)
            }
            divider(context)
        }

        for ((label, time) in presets(context)) {
            row(context, label) { setReminder(time) }
        }
        row(context, "Custom…", trailing = "exo_ic_chevron_right") { pickCustom(context) }

        if (existing != null) {
            divider(context)
            row(context, "Mark Complete", "ic_check_white_24dp") { complete() }
        }
    }

    /** Discord's quick choices, labelled the same way. */
    private fun presets(context: Context): List<Pair<String, Long>> {
        val now = System.currentTimeMillis()
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            atNine()
        }
        val monday = Calendar.getInstance().apply {
            // Always the coming Monday, a week away when it's Monday today.
            do add(Calendar.DAY_OF_YEAR, 1) while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
            atNine()
        }
        val nine = DateFormat.getTimeFormat(context).format(tomorrow.time)
        return listOf(
            "in 30m" to now + 30 * MINUTE,
            "in 1h" to now + 60 * MINUTE,
            "in 4h" to now + 4 * 60 * MINUTE,
            "Tomorrow at $nine" to tomorrow.timeInMillis,
            "Monday at $nine" to monday.timeInMillis,
        )
    }

    private fun Calendar.atNine() {
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Discord 126.21's own date and time dialogs, as used for scheduled events. */
    private fun pickCustom(context: Context) {
        val start = Calendar.getInstance().apply {
            timeInMillis = plugin.store.get(target.channelId, target.messageId)?.dueAt
                ?: (System.currentTimeMillis() + 60 * MINUTE)
        }
        val dates = DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (picked <= System.currentTimeMillis()) {
                            Utils.showToast("Pick a time in the future")
                        } else {
                            setReminder(picked)
                        }
                    },
                    start.get(Calendar.HOUR_OF_DAY),
                    start.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(context),
                ).show()
            },
            start.get(Calendar.YEAR),
            start.get(Calendar.MONTH),
            start.get(Calendar.DAY_OF_MONTH),
        )
        dates.datePicker.minDate = System.currentTimeMillis() - 1000
        dates.show()
    }

    private fun setReminder(dueAt: Long) {
        if (busy) return
        busy = true
        Utils.threadPool.execute {
            val error = try {
                plugin.store.set(target.channelId, target.messageId, target.guildId, dueAt, target)
                null
            } catch (e: Throwable) {
                e.message ?: "Couldn't set the reminder"
            }
            Utils.mainThread.post {
                busy = false
                if (error == null) {
                    val context = Utils.appContext
                    val time = DateUtils.formatDateTime(
                        context,
                        dueAt,
                        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                    )
                    Utils.showToast("Reminder set for $time")
                    if (isAdded) dismissAllowingStateLoss()
                } else {
                    Utils.showToast("Couldn't set the reminder: $error")
                }
            }
        }
    }

    private fun complete() {
        if (busy) return
        busy = true
        Utils.threadPool.execute {
            val error = try {
                plugin.store.complete(target.channelId, target.messageId)
                null
            } catch (e: Throwable) {
                e.message ?: "Couldn't complete the reminder"
            }
            Utils.mainThread.post {
                busy = false
                if (error == null) {
                    Utils.showToast("Reminder completed")
                    if (isAdded) dismissAllowingStateLoss()
                } else {
                    Utils.showToast("Couldn't complete the reminder: $error")
                }
            }
        }
    }

    /** A message action row cloned from Discord's actions sheet. */
    private fun row(context: Context, label: String, icon: String? = null, trailing: String? = null, click: () -> Unit) {
        val template = LayoutInflater.from(context)
            .inflate(Utils.getResId("widget_chat_list_actions", "layout"), null) as ViewGroup
        val row = template.findViewById<TextView>(Utils.getResId("dialog_chat_actions_reply", "id")) ?: return
        (row.parent as ViewGroup).removeView(row)
        row.id = View.generateViewId()
        row.text = label
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon?.let { drawable(context, it) },
            null,
            trailing?.let { drawable(context, it) },
            null,
        )
        row.setOnClickListener { click() }
        addView(row)
    }

    private fun divider(context: Context) {
        addView(View(context, null, 0, R.i.UiKit_Settings_Divider).apply { layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }

    private fun drawable(context: Context, name: String) =
        Utils.getResId(name, "drawable").takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }

    companion object {
        private const val MINUTE = 60_000L
        private const val TAG = "MessageRemindersSheet"

        /** "Due in 25 minutes", or "Was due 5 minutes ago" once it passed. */
        fun dueText(dueAt: Long): String {
            val now = System.currentTimeMillis()
            val relative = DateUtils.getRelativeTimeSpanString(dueAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
            return if (dueAt > now) "Due $relative" else "Was due $relative"
        }

        fun show(plugin: MessageReminders, target: Reminder, jump: Boolean) {
            val manager = Utils.appActivity.supportFragmentManager
            if (manager.findFragmentByTag(TAG) != null) return
            RemindMeSheet(plugin, target, jump).show(manager, TAG)
        }
    }
}
