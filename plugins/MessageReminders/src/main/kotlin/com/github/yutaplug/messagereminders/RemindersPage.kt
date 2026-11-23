package com.github.yutaplug.messagereminders

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.lytefast.flexinput.R

/** Your reminders, soonest first. Tapping one opens its Remind Me sheet. */
class RemindersPage(private val plugin: MessageReminders) : SettingsPage() {
    // Aliucord attaches the proxy fragment, so this page's isAdded stays false; track the view ourselves.
    private var alive = false
    private var loading = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Reminders")
        setActionBarSubtitle("MessageReminders")
        alive = true
        plugin.register(this)
        loading = !plugin.store.loaded
        render()
        plugin.refreshInBackground { ok ->
            loading = false
            if (!ok && alive) com.aliucord.Utils.showToast("Couldn't load your reminders")
            render()
        }
    }

    override fun onDestroyView() {
        alive = false
        super.onDestroyView()
    }

    internal fun render() {
        if (!alive) return
        val context = context ?: return
        linearLayout.removeAllViews()
        val reminders = plugin.store.all()
        val now = System.currentTimeMillis()
        if (reminders.isEmpty()) {
            linearLayout.addView(
                note(
                    if (loading) {
                        "Loading your reminders…"
                    } else {
                        "No reminders. Long-press a message and pick Create Reminder to get reminded about it later."
                    },
                ),
            )
            return
        }
        val overdue = reminders.filter { it.dueAt <= now }
        val upcoming = reminders.filter { it.dueAt > now }
        if (overdue.isNotEmpty()) section("Overdue", overdue)
        if (upcoming.isNotEmpty()) section("Upcoming", upcoming)
        linearLayout.addView(note("Tap a reminder to jump to its message, move it or mark it complete."))
    }

    private fun section(title: String, reminders: List<Reminder>) {
        linearLayout.addView(TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply { text = title })
        reminders.forEach(::row)
    }

    /** Message on top, then when it's due and where, like Discord's reminder list. */
    private fun row(reminder: Reminder) {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            val value = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                setBackgroundResource(value.resourceId)
            }
            setOnClickListener { RemindMeSheet.show(plugin, reminder, jump = true, manager = parentFragmentManager) }
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = plugin.describe(reminder)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(10), paddingEnd, 0)
                minHeight = 0
                minimumHeight = 0
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val place = plugin.placeOf(reminder)
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = if (place != null) "${RemindMeSheet.dueText(reminder.dueAt)} · $place" else RemindMeSheet.dueText(reminder.dueAt)
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(2), paddingEnd, DimenUtils.dpToPx(10))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        linearLayout.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun note(value: CharSequence) = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
        text = value
        background = null
        setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
    }
}
