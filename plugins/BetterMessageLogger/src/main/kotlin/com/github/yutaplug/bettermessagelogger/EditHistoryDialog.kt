package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.aliucord.Utils
import java.text.DateFormat
import java.util.Date

internal object EditHistoryDialog {
    fun show(context: Context, record: MessageRecord) {
        val ui = LoggerUi(context)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val content = ui.dialogContent()
        content.addView(
            ui
                .text(
                    "${record.authorName} · ${record.edits.size} ${if (record.edits.size == 1) "edit" else "edits"}",
                    13f,
                    ui.muted,
                ).apply { setPadding(0, 0, 0, ui.dp(8)) },
        )
        val list = ui.column()
        // WRAP_CONTENT with a maximum keeps small histories compact and long ones scrollable.
        content.addView(ui.scroll(list, 0.55f), LinearLayout.LayoutParams(-1, -2))

        fun version(label: String, date: String, body: String, current: Boolean = false) {
            val card = ui.card().apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(10)) }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = ui.column()
            labels.addView(ui.text(label, 14f, if (current) ui.brand else ui.primary))
            labels.addView(ui.text(date, 12f, ui.muted))
            header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(
                ui.iconButton("ic_copy_24dp", "Copy") {
                    Utils.setClipboard("Message version", body)
                    Utils.showToast("Copied")
                },
            )
            card.addView(header)
            card.addView(
                ui.text(body.ifEmpty { "No text content" }, 15f, if (body.isEmpty()) ui.muted else ui.normal).apply {
                    setPadding(0, ui.dp(4), ui.dp(8), 0)
                    setLineSpacing(ui.dp(2).toFloat(), 1f)
                    setTextIsSelectable(true)
                },
            )
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }

        version(
            if (record.deleted) "Final version · deleted" else "Current",
            format.format(Date(record.editedTimestamp ?: record.timestamp)),
            record.content,
            true,
        )
        var index = record.edits.size - 1
        while (index >= 0) {
            val edit = record.edits[index]
            version(
                if (index == 0) "Original" else "Version ${index + 1}",
                "Replaced ${format.format(Date(edit.timestamp))}",
                edit.content,
            )
            index--
        }
        val dialog = ui.dialog("Edit history", content).setPositiveButton("Close", null).create()
        ui.style(dialog)
        dialog.show()
    }
}
