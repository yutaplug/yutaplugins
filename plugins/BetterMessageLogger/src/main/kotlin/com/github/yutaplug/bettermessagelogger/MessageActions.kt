package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.R
import java.io.File
import java.util.Locale

/** Real rows taken out of one of Discord's layouts, so new menu entries look exactly like the native ones. */
internal object NativeRows {
    fun take(context: Context, layout: String, ids: List<String>): List<TextView> {
        val root = LayoutInflater.from(context).inflate(Utils.getResId(layout, "layout"), null)
        return ids.mapNotNull { name ->
            root.findViewById<TextView>(Utils.getResId(name, "id"))?.also {
                (it.parent as? ViewGroup)?.removeView(it)
                it.id = View.generateViewId()
                it.visibility = View.VISIBLE
            }
        }
    }

    fun dress(row: TextView, tag: String, title: String, iconName: String, click: () -> Unit) {
        row.tag = tag
        row.text = title
        val ui = Ui(row.context)
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon(iconName), null, null, null)
        row.setOnClickListener { click() }
    }
}

/** Entries in the message long-press sheet: edit history, hiding until restart, and removing from the log. */
internal class MessageActions(private val log: MessageLog, private val reportError: (String, Throwable) -> Unit) {
    fun patch(patcher: PatcherAPI) {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { frame ->
                val sheet = frame.thisObject as WidgetChatListActions
                val message = (frame.args[0] as WidgetChatListActions.Model).message ?: return@Hook
                try {
                    addEntries(sheet, message.id)
                } catch (error: Exception) {
                    reportError("add logger entries to the message menu", error)
                }
            },
        )
    }

    private fun addEntries(sheet: WidgetChatListActions, id: Long) {
        val view = sheet.view ?: return
        val container = view.findViewById<ViewGroup>(Utils.getResId("dialog_chat_actions_container", "id")) ?: return
        // The sheet is reused for the next message: start clean.
        var index = container.childCount - 1
        while (index >= 0) {
            if ((container.getChildAt(index).tag as? String)?.startsWith(TAG) == true) container.removeViewAt(index)
            index--
        }
        val saved = log.record(id)
        val (history, hide, delete) = NativeRows.take(
            sheet.requireContext(),
            "widget_chat_list_actions",
            listOf("dialog_chat_actions_edit", "dialog_chat_actions_copy", "dialog_chat_actions_delete"),
        ).let { if (it.size == 3) Triple(it[0], it[1], it[2]) else return }
        val rows = ArrayList<View>()
        if (saved != null && saved.edits.isNotEmpty()) {
            NativeRows.dress(history, "${TAG}history", "View Edit History", "ic_history_white_24dp") {
                sheet.dismiss()
                HistoryPage.open(sheet.requireContext(), id)
            }
            rows.add(history)
        }
        NativeRows.dress(hide, "${TAG}hide", "Hide Message", "design_ic_visibility_off") {
            log.hide(id)
            sheet.dismiss()
            Utils.showToast("Hidden until Discord restarts")
        }
        rows.add(hide)
        if (saved != null) {
            NativeRows.dress(delete, "${TAG}delete", "Remove From Log", "ic_delete_24dp") {
                log.delete(id)
                sheet.dismiss()
                Utils.showToast("Removed from the log")
            }
            rows.add(delete)
        }
        val reactions = container.findViewById<View>(Utils.getResId("dialog_chat_actions_add_reaction_emojis_list", "id"))
        var position = reactions?.let { container.indexOfChild(it) + 1 }?.takeIf { it > 0 } ?: container.childCount
        for (row in rows) container.addView(row, position++)
    }

    companion object {
        private const val TAG = "BetterMessageLogger:"
    }
}

/** Every version of one logged message, newest first, each with copy and its removed attachments. */
class HistoryPage(private val messageId: Long?) : SettingsPage() {
    constructor() : this(null)

    private var alive = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        alive = true
        val ui = Ui(requireContext())
        linearLayout.setBackgroundColor(ui.pageColor)
        setActionBarTitle("Edit history")
        setActionBarSubtitle("BetterMessageLogger")
        val id = messageId
        val log = BetterMessageLogger.instance?.log
        if (id == null || log == null) {
            ui.note(linearLayout, "Open the history again from the message.")
            return
        }
        val known = log.record(id)
        if (known != null) {
            show(ui, known, log.media)
            return
        }
        log.database?.loadAsync(id) { saved ->
            Utils.mainThread.post {
                if (!alive) return@post
                if (saved == null) ui.note(linearLayout, "This message isn't in the log anymore.") else show(ui, saved, log.media)
            }
        }
    }

    override fun onDestroyView() {
        alive = false
        super.onDestroyView()
    }

    private fun show(ui: Ui, record: MessageRecord, media: MediaStore?) {
        setActionBarSubtitle("${record.authorName} · ${record.edits.size} ${if (record.edits.size == 1) "edit" else "edits"}")
        val context = ui.context
        version(
            ui,
            if (record.deleted) "Last version (deleted)" else "Current version",
            buildString {
                append("Edited ").append(Dates.format(context, record.editedTimestamp ?: record.timestamp))
                record.deletedTimestamp?.let { append(" · deleted ").append(Dates.format(context, it)) }
            },
            record.content,
            emptyList(),
        )
        var index = record.edits.size - 1
        while (index >= 0) {
            val edit = record.edits[index]
            val removed = edit.removed.map { attachment ->
                attachment.a() to MediaStore.attachmentItem(attachment)?.let { media?.localFile(record.id, it) }
            }
            version(
                ui,
                if (index == 0) "Original" else "Version ${index + 1}",
                "Replaced ${Dates.format(context, edit.timestamp)}",
                edit.content,
                removed,
            )
            index--
        }
    }

    private fun version(ui: Ui, title: String, detail: String, content: String, removed: List<Pair<String, File?>>) {
        ui.divider(linearLayout)
        val header = LinearLayout(ui.context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val labels = ui.column()
        labels.addView(TextView(ui.context, null, 0, R.i.UiKit_Settings_Item_Header).apply { text = title })
        labels.addView(ui.text(detail, R.i.UiKit_TextView_Subtext).apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) })
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(
            ui.iconButton("ic_copy_24dp", "Copy") {
                Utils.setClipboard("Message", content)
                Utils.showToast("Copied")
            },
        )
        linearLayout.addView(header, ui.matchWidth())
        linearLayout.addView(
            ui.text(if (content.isEmpty()) "No text" else content, R.i.UiKit_TextView, if (content.isEmpty()) ui.mutedColor else ui.textColor)
                .apply {
                    setTextIsSelectable(true)
                    setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
                },
            ui.matchWidth(),
        )
        for ((name, file) in removed) {
            if (file != null) {
                ui.action(linearLayout, "Removed attachment: $name", "ic_file_download_white_24dp") {
                    LocalFiles.open(ui.context, file)
                }
            } else {
                ui.note(linearLayout, "Removed attachment: $name (not saved)")
            }
        }
    }

    companion object {
        fun open(context: Context, messageId: Long) = Utils.openPageWithProxy(context, HistoryPage(messageId))
    }
}

/** Opening and exporting saved files, which Discord would otherwise treat as web links. */
internal object LocalFiles {
    fun open(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "com.discord.file-provider", file)
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.toLowerCase(Locale.ROOT)) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Utils.showToast("No app can open ${file.name}")
        }
    }

    /** What Discord's download button does for web files: a copy in Downloads. */
    fun download(file: File, name: String) {
        Utils.threadPool.execute {
            val message = try {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                check(downloads.isDirectory || downloads.mkdirs())
                val clean = StringBuilder()
                for (char in name) clean.append(if (char in "\\/:*?\"<>|") '_' else char)
                val base = clean.toString().ifEmpty { file.name }
                var target = File(downloads, base)
                var copy = 2
                while (target.exists()) {
                    val dot = base.lastIndexOf('.')
                    target = File(downloads, if (dot > 0) "${base.substring(0, dot)} ($copy)${base.substring(dot)}" else "$base ($copy)")
                    copy++
                }
                file.copyTo(target)
                MediaScannerConnection.scanFile(Utils.appContext, arrayOf(target.path), null, null)
                "Saved to Downloads"
            } catch (_: Exception) {
                "Couldn't save ${file.name}"
            }
            Utils.mainThread.post { Utils.showToast(message) }
        }
    }
}
