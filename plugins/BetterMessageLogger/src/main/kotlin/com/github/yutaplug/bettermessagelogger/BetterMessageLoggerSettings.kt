package com.github.yutaplug.bettermessagelogger

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import java.io.File

/**
 * A short list of choices, each showing its current value and opening a pop-up, like CustomRPC's settings.
 * Long lists (filters) live on their own pages.
 */
class BetterMessageLoggerSettings(private val settings: SettingsAPI) : SettingsPage() {
    // Android may recreate the page without its constructor arguments.
    constructor() : this(SettingsAPI("BetterMessageLogger"))

    private lateinit var ui: Ui
    private var logs: TextView? = null
    private var hidden: TextView? = null
    private val summaries = HashMap<String, TextView>()

    private val log get() = BetterMessageLogger.instance?.log

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        ui = Ui(requireContext())
        setActionBarTitle("BetterMessageLogger")
        setActionBarSubtitle("Plugin settings")
        linearLayout.setBackgroundColor(ui.pageColor)

        logs = ui.option(linearLayout, "Dashboard", "") { LogViewer.openHome(requireContext()) }

        section("Hide Message")
        ui.switch(
            linearLayout,
            "Hide Message",
            "Adds Hide Message to the message menu, which hides a message from chat on this device only",
            Prefs.hideEnabled(settings),
        ) {
            setHiding(it)
            refresh()
        }
        option(Prefs.HIDE_PERSIST, "Keep hidden") {
            choose("Keep hidden", KEEP_CHOICES) {
                if (log != null) log?.setHidePersist(it == 1) else settings.setBool(Prefs.HIDE_PERSIST, it == 1)
            }
        }
        hidden = ui.option(linearLayout, "Hidden messages", "") {
            log?.unhideAll()
            refresh()
            Utils.showToast("Hidden messages are back")
        }

        section("Logging")
        option(KEEP, "Keep logs") { choose("Keep logs", KEEP_CHOICES) { setKeep(it == 1) } }
        option(IGNORE, "Ignore") { choose("Ignore", IGNORE_CHOICES, ::setIgnore) }
        option(FILTERS, "Servers, channels and people") { FiltersPage.open(requireContext(), FiltersPage.LOGGING) }

        section("Media")
        option(MEDIA, "Save deleted media") { choose("Save deleted media", MEDIA_CHOICES, ::setMedia) }
        option(SOURCES, "From") { choose("Save media from", SOURCE_CHOICES, ::setSources) }
        option(Prefs.MEDIA_MAX_MB, "Size limit") {
            choose("Size limit per file", Prefs.MAX_MB_CHOICES.map { "$it MB" }.toTypedArray()) {
                settings.setInt(Prefs.MEDIA_MAX_MB, Prefs.MAX_MB_CHOICES[it])
                mediaChanged()
            }
        }
        option(Prefs.PREFETCH_MODE, "Pre-download media") { choose("Pre-download media", PREFETCH_CHOICES, ::setPrefetch) }
        option(PREFETCH_FILTERS, "Pre-download places and people") {
            FiltersPage.open(requireContext(), FiltersPage.PREFETCH)
        }

        section("Appearance")
        option(Prefs.SHOW_DELETED_TAG, "Deleted tag") {
            choose("Deleted tag", TAG_CHOICES) {
                settings.setBool(Prefs.SHOW_DELETED_TAG, it == 0)
                restyle()
            }
        }
        option(Prefs.DELETED_LABEL_COLOR, "Tag color") { chooseColor(Prefs.DELETED_LABEL_COLOR, "Tag color") }
        option(Prefs.DELETED_MESSAGE_COLOR, "Text color") { chooseColor(Prefs.DELETED_MESSAGE_COLOR, "Text color") }
        ui.action(linearLayout, "Reset appearance", "ic_history_white_24dp") {
            settings.setBool(Prefs.SHOW_DELETED_TAG, true)
            settings.setString(Prefs.DELETED_LABEL_COLOR, Prefs.DEFAULT_DELETED_LABEL_COLOR)
            settings.setString(Prefs.DELETED_MESSAGE_COLOR, Prefs.DEFAULT_DELETED_MESSAGE_COLOR)
            restyle()
            refresh()
            Utils.showToast("Appearance reset")
        }

        section("Data")
        ui.action(linearLayout, "Export as text", "ic_file_download_white_24dp") { export() }
        ui.action(linearLayout, "Clear logs", "ic_delete_24dp", ui.dangerColor) { confirmClear() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) refresh()
    }

    override fun onDestroyView() {
        logs = null
        hidden = null
        summaries.clear()
        super.onDestroyView()
    }

    private fun section(title: String) {
        ui.divider(linearLayout)
        ui.header(linearLayout, title)
    }

    private fun option(key: String, title: String, click: () -> Unit) {
        summaries[key] = ui.option(linearLayout, title, "", click)
    }

    private fun choose(title: String, choices: Array<String>, picked: (Int) -> Unit) = SelectDialog().apply {
        this.title = title
        items = choices
        onResultListener = {
            picked(it)
            refresh()
        }
    }.show(parentFragmentManager, "BetterMessageLoggerChoice")

    // region Current values

    private fun refresh() {
        summaries[KEEP]?.text = KEEP_CHOICES[if (Prefs.persist(settings)) 1 else 0]
        summaries[IGNORE]?.text = IGNORE_CHOICES[ignore()]
        summaries[FILTERS]?.text = rules(LOGGING_LISTS, "Everything is logged")
        summaries[MEDIA]?.text = media()?.let { MEDIA_CHOICES[it] } ?: "Custom"
        summaries[SOURCES]?.text = sources()?.let { SOURCE_CHOICES[it] } ?: "Nowhere"
        summaries[Prefs.MEDIA_MAX_MB]?.text = "${settings.getInt(Prefs.MEDIA_MAX_MB, 25)} MB per file"
        summaries[Prefs.PREFETCH_MODE]?.text = "${PREFETCH_CHOICES[Prefs.prefetchMode(settings)]} · $PREFETCH_WHY"
        summaries[PREFETCH_FILTERS]?.text = rules(PREFETCH_LISTS, "No places or people chosen")
        summaries[Prefs.SHOW_DELETED_TAG]?.text = TAG_CHOICES[if (Prefs.showDeletedTag(settings)) 0 else 1]
        summaries[Prefs.DELETED_LABEL_COLOR]?.text = colorName(Prefs.labelColor(settings))
        summaries[Prefs.DELETED_MESSAGE_COLOR]?.text = colorName(Prefs.textColor(settings))
        val hiding = Prefs.hideEnabled(settings)
        val acrossRestarts = Prefs.hidePersist(settings)
        summaries[Prefs.HIDE_PERSIST]?.let {
            it.text = KEEP_CHOICES[if (acrossRestarts) 1 else 0]
            (it.parent as? View)?.visibility = if (hiding) View.VISIBLE else View.GONE
        }
        val count = log?.hiddenCount ?: 0
        hidden?.text = "$count hidden${if (acrossRestarts) "" else " until restart"}. Tap to show them again."
        (hidden?.parent as? View)?.visibility = if (hiding && count > 0) View.VISIBLE else View.GONE
        refreshLogs()
    }

    private fun refreshLogs() {
        val target = logs ?: return
        val log = log
        val db = log?.database
        if (db == null || !log.databaseReady) {
            target.text = "$DASHBOARD · opening the log…"
            return
        }
        db.statisticsAsync { result ->
            Utils.mainThread.post {
                // This page lives inside Aliucord's proxy fragment, so compare views instead of isAdded.
                if (logs !== target) return@post
                val stats = result.getOrNull()
                target.text = when {
                    stats == null -> "$DASHBOARD · the log couldn't be read"
                    stats.messages == 0L -> "$DASHBOARD · nothing logged yet"
                    else -> "$DASHBOARD · ${stats.deleted} deleted, ${stats.edited} edited" +
                        if (Prefs.persist(settings)) " · ${Formatter.formatShortFileSize(requireContext(), stats.bytes)}" else ""
                }
            }
        }
    }

    private fun rules(lists: List<IdList>, none: String): String {
        val count = lists.sumOf { it.read(settings).size }
        return if (count == 0) none else "$count ${if (count == 1) "entry" else "entries"}"
    }

    private fun ignore() = (if (settings.getBool(Prefs.IGNORE_OWN, false)) 1 else 0) +
        (if (settings.getBool(Prefs.IGNORE_BOTS, false)) 2 else 0)

    /** Which media preset is on, or null when the switches were set some other way before. */
    private fun media(): Int? {
        val kinds = listOf(Prefs.MEDIA_IMAGES to true, Prefs.MEDIA_VIDEOS to true, Prefs.MEDIA_AUDIO to false, Prefs.MEDIA_FILES to false)
            .map { (key, default) -> settings.getBool(key, default) }
        return MEDIA_PRESETS.indexOfFirst { it == kinds }.takeIf { it >= 0 }
    }

    private fun sources(): Int? {
        val uploads = settings.getBool(Prefs.MEDIA_ATTACHMENTS, true)
        val embeds = settings.getBool(Prefs.MEDIA_EMBEDS, true)
        return when {
            uploads && embeds -> 0
            uploads -> 1
            embeds -> 2
            else -> null
        }
    }

    private fun colorName(color: Int) = COLORS.firstOrNull { it.second == color }?.first ?: Colors.hex(color)

    // endregion

    // region Changes

    private fun setKeep(persist: Boolean) {
        settings.setBool(Prefs.PERSIST, persist)
        log?.setPersist(persist)
    }

    private fun setHiding(enabled: Boolean) {
        val log = log
        if (log != null) log.setHidingEnabled(enabled) else settings.setBool(Prefs.HIDE_ENABLED, enabled)
    }

    private fun setIgnore(choice: Int) {
        settings.setBool(Prefs.IGNORE_OWN, choice and 1 != 0)
        settings.setBool(Prefs.IGNORE_BOTS, choice and 2 != 0)
        log?.filtersChanged()
    }

    private fun setMedia(choice: Int) {
        listOf(Prefs.MEDIA_IMAGES, Prefs.MEDIA_VIDEOS, Prefs.MEDIA_AUDIO, Prefs.MEDIA_FILES)
            .zip(MEDIA_PRESETS[choice])
            .forEach { (key, on) -> settings.setBool(key, on) }
        mediaChanged()
    }

    private fun setSources(choice: Int) {
        settings.setBool(Prefs.MEDIA_ATTACHMENTS, choice != 2)
        settings.setBool(Prefs.MEDIA_EMBEDS, choice != 1)
        mediaChanged()
    }

    private fun setPrefetch(choice: Int) {
        settings.setInt(Prefs.PREFETCH_MODE, choice)
        log?.filtersChanged()
    }

    private fun mediaChanged() {
        log?.mediaOptionsChanged()
    }

    private fun chooseColor(key: String, title: String) {
        val names = COLORS.map { it.first } + "Pick a color…"
        choose(title, names.toTypedArray()) { picked ->
            if (picked < COLORS.size) {
                saveColor(key, COLORS[picked].second)
            } else {
                askHex(key, title)
            }
        }
    }

    /** Aliucord's text pop-up for a hex color, with a picker above the field; the two follow each other. */
    private fun askHex(key: String, title: String) {
        val current = if (key == Prefs.DELETED_LABEL_COLOR) Prefs.labelColor(settings) else Prefs.textColor(settings)
        val dialog = InputDialog()
        dialog.setTitle(title)
            .setDescription("Drag to pick, or type a hex color like #FF5555. Two more digits in front set opacity.")
            .setPlaceholderText("#RRGGBB")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            .setOnOkListener {
                val color = Colors.parse(dialog.input)
                if (color == null) {
                    dialog.inputLayout.error = "That isn't a hex color"
                } else {
                    dialog.dismiss()
                    saveColor(key, color)
                    refresh()
                }
            }
        dialog.setOnDialogShownListener {
            val field = dialog.inputLayout
            val input = field.editText ?: return@setOnDialogShownListener
            input.setText(Colors.hex(current))
            input.setSelection(input.text.length)
            val parent = field.parent as? ViewGroup ?: return@setOnDialogShownListener
            if (parent.findViewWithTag<View>(PICKER_TAG) != null) return@setOnDialogShownListener
            var typing = false
            val picker = ColorPicker(parent.context, current) { color ->
                typing = true
                input.setText(Colors.hex(color))
                input.setSelection(input.text.length)
                typing = false
            }
            picker.tag = PICKER_TAG
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    if (!typing) Colors.parse(s?.toString())?.let { picker.color = it }
                }
            })
            val margin = ui.dp(4)
            parent.addView(
                picker,
                parent.indexOfChild(field),
                ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, margin, 0, margin * 3) },
            )
        }
        dialog.show(parentFragmentManager, "BetterMessageLoggerColor")
    }

    private fun saveColor(key: String, color: Int) {
        settings.setString(key, Colors.hex(color))
        restyle()
    }

    private fun restyle() {
        log?.decorations?.refresh(appearance = true)
    }

    private fun export() {
        val log = log
        val db = log?.database
        if (db == null || !log.databaseReady) return Utils.showToast("The log is still opening")
        val file = File(Constants.BASE_PATH, BetterMessageLogger.EXPORT_NAME)
        Utils.showToast("Exporting…")
        db.exportTextAsync(file, { log.describePlace(it.channelId, it.guildId) }) { result ->
            Utils.mainThread.post {
                Utils.showToast(if (result.isSuccess) "Saved as Aliucord/${file.name}" else "Export failed", true)
            }
        }
    }

    private fun confirmClear() {
        val dialog = ConfirmDialog()
        dialog.setTitle("Clear logs?")
            .setDescription("Logged messages, edit history and saved media are deleted. Exported text files stay.")
            .setIsDangerous(true)
            .setOnOkListener {
                dialog.dismiss()
                log?.clear { done ->
                    Utils.showToast(if (done) "Logs cleared" else "Couldn't clear the logs", true)
                    refreshLogs()
                }
            }.show(parentFragmentManager, "BetterMessageLoggerClear")
    }

    // endregion

    companion object {
        private const val KEEP = "keep"
        private const val IGNORE = "ignore"
        private const val FILTERS = "filters"
        private const val MEDIA = "media"
        private const val SOURCES = "sources"
        private const val PICKER_TAG = "BetterMessageLogger:picker"
        private const val PREFETCH_WHY = "Discord deletes a message's files seconds after the message, usually before " +
            "they can be saved. This saves them as messages arrive, so deleted media still shows. Uses extra data."
        private const val DASHBOARD = "Browse, search and jump to logged messages"
        private const val PREFETCH_FILTERS = "prefetchFilters"

        private val KEEP_CHOICES = arrayOf("Until Discord restarts", "Across restarts")
        private val IGNORE_CHOICES = arrayOf("Nothing", "My messages", "Bots", "My messages and bots")
        private val MEDIA_CHOICES = arrayOf("Off", "Images and videos", "Images, videos and audio", "All files")
        private val MEDIA_PRESETS = listOf(
            listOf(false, false, false, false),
            listOf(true, true, false, false),
            listOf(true, true, true, false),
            listOf(true, true, true, true),
        )
        private val SOURCE_CHOICES = arrayOf("Uploads and link embeds", "Uploads only", "Link embeds only")
        private val PREFETCH_CHOICES = arrayOf("Off", "Everywhere", "Only chosen places and people")
        private val TAG_CHOICES = arrayOf("Shown", "Hidden")

        // Discord's own colors, plus the defaults.
        private val COLORS = listOf(
            "Red" to 0xFFFF0000.toInt(),
            "Discord red" to 0xFFED4245.toInt(),
            "Yellow" to 0xFFFEE75C.toInt(),
            "Green" to 0xFF57F287.toInt(),
            "Blurple" to 0xFF5865F2.toInt(),
            "Fuchsia" to 0xFFEB459E.toInt(),
            "Grey" to 0xFF96989D.toInt(),
            "White" to 0xFFFFFFFF.toInt(),
        )

        internal val LOGGING_LISTS = listOf(
            IdList.IGNORED_USERS, IdList.ALLOWED_USERS, IdList.BLOCKED_SERVERS, IdList.ALLOWED_SERVERS, IdList.BLOCKED_CHANNELS,
            IdList.ALLOWED_CHANNELS, IdList.BLOCKED_DMS, IdList.ALLOWED_DMS,
        )
        internal val PREFETCH_LISTS = listOf(
            IdList.PREFETCH_SERVERS, IdList.PREFETCH_CHANNELS, IdList.PREFETCH_USERS,
            IdList.SKIP_SERVERS, IdList.SKIP_CHANNELS, IdList.SKIP_USERS,
        )
    }
}
