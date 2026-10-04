package com.github.yutaplug.bettermessagelogger

import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.Utils
import com.discord.views.CheckedSetting
import java.util.Locale
import com.github.yutaplug.bettermessagelogger.BetterMessageLogger.Companion as Keys

class BetterMessageLoggerSettings(private val settings: SettingsAPI) : SettingsPage() {
    // Android can recreate the page without the original constructor arguments.
    constructor() : this(SettingsAPI("BetterMessageLogger"))

    private lateinit var ui: LoggerUi
    private var storageCaption: TextView? = null
    private var databaseToggle: CheckedSetting? = null
    private val values = HashMap<String, TextView>()
    private var dialog: AlertDialog? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("BetterMessageLogger")
        setActionBarSubtitle("Plugin settings")
        ui = LoggerUi(requireContext())
        linearLayout.setPadding(0, 0, 0, ui.dp(16))
        linearLayout.setBackgroundColor(ui.background)

        val storage = section("Storage", first = true)
        databaseToggle = toggle(storage, "database", "Save logs across restarts", null) {
            BetterMessageLogger.instance?.setDatabaseEnabled(it)
            updateStorage()
        }
        storageCaption = ui.caption("").also(storage::addView)
        toggle(
            storage,
            Keys.PREFETCH_MEDIA,
            "Pre-download media",
            "Save images and videos as they arrive so deleted media still shows. Uses extra data.",
            true,
        ) {}
        ui.row(storage, "Export to TXT", null, "ic_file_download_white_24dp") {
            BetterMessageLogger.instance?.exportDatabaseToText()
        }
        ui.row(storage, "Clear saved logs", null, "ic_delete_24dp", ui.danger) { confirmClear() }

        val appearance = section("Appearance")
        toggle(appearance, Keys.SHOW_DELETED_TAG, "Show deleted tag", null, true) {
            BetterMessageLogger.instance?.refreshAppearance()
        }
        values[Keys.DELETED_LABEL_COLOR] = ui.row(appearance, "Deleted tag color", colorValue(Keys.DELETED_LABEL_COLOR)) {
            colorDialog(Keys.DELETED_LABEL_COLOR, "Deleted tag color")
        }
        values[Keys.DELETED_MESSAGE_COLOR] =
            ui.row(appearance, "Deleted text color", colorValue(Keys.DELETED_MESSAGE_COLOR)) {
                colorDialog(Keys.DELETED_MESSAGE_COLOR, "Deleted text color")
            }
        ui.row(appearance, "Reset colors", null) {
            settings.setString(Keys.DELETED_LABEL_COLOR, Keys.DEFAULT_DELETED_LABEL_COLOR)
            settings.setString(Keys.DELETED_MESSAGE_COLOR, Keys.DEFAULT_DELETED_MESSAGE_COLOR)
            BetterMessageLogger.instance?.refreshAppearance()
            updateColors()
        }

        val history = section("Edit history")
        toggle(history, Keys.LOG_EDIT_HISTORY, "Log edit history", "Turning this off clears saved history.", true) {
            BetterMessageLogger.instance?.setEditLoggingEnabled(it)
            updateStorage()
        }
        toggle(history, Keys.INLINE_EDIT_HISTORY, "Show history in chat", "Previous versions appear above messages.") {
            BetterMessageLogger.instance?.refreshAppearance()
        }

        val filters = section("Filters")
        toggle(filters, "ignoreOwn", "Ignore my messages", null) { filtersChanged() }
        toggle(filters, "ignoreBots", "Ignore bot messages", null) { filtersChanged() }
        listRow(filters, IdLists.IGNORED_USERS, "Ignored users")
        ui.divider(filters)
        listRow(filters, IdLists.BLOCKED_SERVERS, "Blocked servers")
        listRow(filters, IdLists.ALLOWED_SERVERS, "Allowed servers")
        listRow(filters, IdLists.BLOCKED_CHANNELS, "Blocked channels")
        listRow(filters, IdLists.ALLOWED_CHANNELS, "Allowed channels")
        listRow(filters, IdLists.BLOCKED_DMS, "Blocked DMs")
        listRow(filters, IdLists.ALLOWED_DMS, "Allowed DMs")
        filters.addView(
            ui.caption(
                "Long-press a server, channel or DM to add it. Non-empty allow lists limit logging to their " +
                    "entries; block lists take priority.",
            ).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) },
        )
        updateColors()
        updateStorage()
    }

    private fun section(title: String, first: Boolean = false): LinearLayout {
        if (!first) ui.divider(linearLayout)
        ui.header(linearLayout, title)
        return ui.column().also { linearLayout.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun toggle(
        parent: LinearLayout,
        key: String,
        title: String,
        subtitle: String?,
        default: Boolean = false,
        changed: (Boolean) -> Unit,
    ): CheckedSetting = ui.switch(title, subtitle, settings.getBool(key, default)) {
        settings.setBool(key, it)
        changed(it)
    }.also(parent::addView)

    private fun listRow(parent: LinearLayout, key: String, title: String) {
        values[key] = ui.row(parent, title, count(key)) { idDialog(key, title) }
    }

    private fun count(key: String) = IdLists.read(settings, key).size.let { if (it == 0) "None" else it.toString() }

    private fun updateStorage() {
        val target = storageCaption ?: return
        val enabled = settings.getBool("database", false)
        target.text = if (enabled) "Opening database…" else "Off · logs last until the app restarts"
        BetterMessageLogger.instance?.storageStatistics { stats ->
            // Aliucord attaches the proxy fragment, so this page's isAdded remains false.
            // The caption is cleared on destruction and replaced when the view is rebuilt.
            if (storageCaption !== target) return@storageStatistics
            val on = settings.getBool("database", false)
            databaseToggle?.isChecked = on
            target.text = when {
                stats != null -> {
                    val size = android.text.format.Formatter.formatShortFileSize(requireContext(), stats.bytes)
                    "${stats.messages} messages · ${stats.edits} edits · $size${if (on) "" else " · paused"}"
                }

                on -> "Could not read database statistics"
                else -> "Off · logs last until the app restarts"
            }
        }
    }

    private fun confirmClear() {
        val content = ui.dialogContent(20).apply {
            addView(ui.text("Saved messages, edit history and media will be deleted. Exported TXT files are kept.", 15f, ui.normal))
        }
        showDialog(
            ui
                .dialog("Clear saved logs?", content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    BetterMessageLogger.instance?.clearDatabase { if (storageCaption != null) updateStorage() }
                }.create(),
        ) {
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(ui.danger)
        }
    }

    private fun idDialog(key: String, title: String) {
        val content = ui.dialogContent(0)
        val list = ui.column()
        content.addView(ui.scroll(list, 0.4f), LinearLayout.LayoutParams(-1, -2))
        val inputRow = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(8), 0)
        }
        val input = ui.input(if (key == IdLists.IGNORED_USERS) "Add a user ID" else "Add an ID").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        fun updateList() {
            val ids = IdLists.read(settings, key)
            values[key]?.text = count(key)
            list.removeAllViews()
            if (ids.isEmpty()) {
                list.addView(ui.caption("Nothing added yet.").apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12)) })
            }
            ids.forEach { id ->
                val row = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(48)
                    setPadding(ui.dp(16), 0, ui.dp(8), 0)
                }
                val labels = ui.column()
                val name = IdLists.describe(requireContext(), key, id)
                labels.addView(ui.text(name ?: id.toString(), 15f, ui.normal).apply { setSingleLine(true) })
                if (name != null) labels.addView(ui.text(id.toString(), 12f, ui.muted).apply { setTextIsSelectable(true) })
                row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(
                    ui.iconButton("ic_close_grey_24dp", "Remove") {
                        IdLists.set(settings, key, id, false)
                        filtersChanged()
                        updateList()
                    },
                )
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }

        fun add() {
            val id = input.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }
            when {
                id == null -> input.error = "Enter a numeric ID"
                IdLists.contains(settings, key, id) -> input.error = "Already added"
                else -> {
                    IdLists.set(settings, key, id, true)
                    input.error = null
                    input.text?.clear()
                    filtersChanged()
                    updateList()
                }
            }
        }
        inputRow.addView(ui.iconButton("ic_add_24dp", "Add", ui.brand) { add() })
        ui.divider(content)
        content.addView(inputRow, LinearLayout.LayoutParams(-1, -2))
        updateList()
        showDialog(ui.dialog(title, content).setPositiveButton("Done", null).create())
    }

    private fun filtersChanged() {
        BetterMessageLogger.instance?.settingsChanged()
        updateStorage()
    }

    private fun colorValue(key: String): String {
        val fallback =
            if (key == Keys.DELETED_LABEL_COLOR) Keys.DEFAULT_DELETED_LABEL_COLOR else Keys.DEFAULT_DELETED_MESSAGE_COLOR
        return parseColor(settings.getString(key, fallback))?.let(::hex) ?: fallback
    }

    private fun updateColors() {
        listOf(Keys.DELETED_LABEL_COLOR, Keys.DELETED_MESSAGE_COLOR).forEach { key ->
            values[key]?.apply {
                text = colorValue(key)
                val swatch = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(colorValue(key)))
                    setStroke(ui.dp(1), ui.muted)
                    setSize(ui.dp(14), ui.dp(14))
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(swatch, null, null, null)
                compoundDrawablePadding = ui.dp(8)
            }
        }
    }

    private fun colorDialog(key: String, title: String) {
        val content = ui.dialogContent(20)
        val picker = ColorPickerView(requireContext(), Color.parseColor(colorValue(key)))
        content.addView(
            ui.card().apply { addView(picker, LinearLayout.LayoutParams(-1, ui.dp(160))) },
            LinearLayout.LayoutParams(-1, -2),
        )
        val input = ui.input("#RRGGBB or #AARRGGBB").apply {
            setText(colorValue(key))
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            contentDescription = "Hex color"
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        content.addView(ui.text("Use 8 digits to set opacity.", 12f, ui.muted))
        var updating = false
        picker.onColorChanged = { color ->
            updating = true
            input.setText(hex(color))
            input.setSelection(input.length())
            updating = false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                parseColor(s.toString())?.let {
                    input.error = null
                    picker.color = it
                }
            }
        })
        val dialog = ui
            .dialog(title, ui.scroll(content, 0.6f))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        showDialog(dialog) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val color = parseColor(input.text.toString())
                if (color == null) {
                    input.error = "Use #RRGGBB or #AARRGGBB"
                } else {
                    settings.setString(key, hex(color))
                    updateColors()
                    BetterMessageLogger.instance?.refreshAppearance()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun parseColor(raw: String?): Int? {
        val value = raw.orEmpty().trim().removePrefix("#")
        if ((value.length != 6 && value.length != 8) || value.any { it !in "0123456789abcdefABCDEF" }) return null
        return runCatching { Color.parseColor("#$value") }.getOrNull()
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%08X", color)

    private fun showDialog(value: AlertDialog, configure: (() -> Unit)? = null) {
        dialog?.dismiss()
        dialog = value
        ui.style(value)
        configure?.invoke()
        value.show()
    }

    override fun onResume() {
        super.onResume()
        // Context-menu filter changes happen while this page is in the back stack.
        if (::ui.isInitialized) {
            listOf(
                IdLists.IGNORED_USERS,
                IdLists.BLOCKED_SERVERS,
                IdLists.ALLOWED_SERVERS,
                IdLists.BLOCKED_CHANNELS,
                IdLists.ALLOWED_CHANNELS,
                IdLists.BLOCKED_DMS,
                IdLists.ALLOWED_DMS,
            ).forEach { key -> values[key]?.text = count(key) }
            updateColors()
        }
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        storageCaption = null
        databaseToggle = null
        values.clear()
        super.onDestroyView()
    }
}
