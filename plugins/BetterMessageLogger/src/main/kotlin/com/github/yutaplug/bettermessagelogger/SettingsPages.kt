package com.github.yutaplug.bettermessagelogger

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SettingsPage
import com.lytefast.flexinput.R
import java.util.Locale

private fun pluginSettings() = BetterMessageLogger.instance?.settings ?: SettingsAPI("BetterMessageLogger")

internal object Colors {
    fun hex(color: Int) = String.format(Locale.ROOT, "#%08X", color)

    /** "#RRGGBB" or "#AARRGGBB", with or without the "#"; null for anything else. */
    fun parse(text: String?): Int? {
        val digits = text.orEmpty().trim().removePrefix("#")
        if (digits.length != 6 && digits.length != 8) return null
        val value = digits.toLongOrNull(16) ?: return null
        return if (digits.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }
}

/** The entries of one filter list, named where Discord knows them, with adding by ID. */
class IdListPage(private val key: String?) : SettingsPage() {
    constructor() : this(null)

    private lateinit var ui: Ui
    private lateinit var entries: LinearLayout

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        ui = Ui(requireContext())
        linearLayout.setBackgroundColor(ui.pageColor)
        val list = IdList.byKey(key)
        setActionBarTitle(list?.title ?: "BetterMessageLogger")
        setActionBarSubtitle("BetterMessageLogger")
        if (list == null) {
            ui.note(linearLayout, "Open this list again from the plugin settings.")
            return
        }
        ui.action(linearLayout, "Add by ID", "ic_add_24dp") { add(list) }
        ui.divider(linearLayout)
        entries = ui.column(linearLayout)
        ui.note(
            linearLayout,
            if (list.kind == IdKind.USER) {
                "With Developer Mode on (Settings → Advanced), long-press someone and copy their ID."
            } else {
                "Long-press the server, channel or DM and pick Message Logger to add it without an ID."
            },
        )
        show(list)
    }

    private fun show(list: IdList) {
        entries.removeAllViews()
        val ids = list.read(pluginSettings())
        if (ids.isEmpty()) ui.note(entries, "Empty.")
        for (id in ids) {
            val row = LinearLayout(ui.context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(56)
                setPaddingRelative(ui.dp(16), 0, ui.dp(4), 0)
            }
            val texts = ui.column()
            val name = IdList.describe(ui.context, list.kind, id)
            texts.addView(ui.text(name ?: "Unknown", R.i.UiKit_TextView_Medium_SingleLine, ui.headerColor))
            texts.addView(ui.text(id.toString(), R.i.UiKit_TextView_Subtext_SingleLine).apply { setTextIsSelectable(true) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                ui.iconButton("ic_close_grey_24dp", "Remove") {
                    list.set(pluginSettings(), id, false)
                    BetterMessageLogger.instance?.log?.filtersChanged()
                    show(list)
                },
            )
            entries.addView(row, ui.matchWidth())
        }
    }

    private fun add(list: IdList) {
        val dialog = InputDialog()
        dialog.setTitle(list.title)
            .setDescription(if (list.kind == IdKind.USER) "A user ID" else "A server, channel or DM ID")
            .setPlaceholderText("ID")
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .setOnOkListener {
                val id = dialog.input.trim().toLongOrNull()?.takeIf { it > 0 }
                when {
                    id == null -> dialog.inputLayout.error = "That isn't an ID"
                    list.contains(pluginSettings(), id) -> dialog.inputLayout.error = "Already in the list"
                    else -> {
                        list.set(pluginSettings(), id, true)
                        dialog.dismiss()
                        BetterMessageLogger.instance?.log?.filtersChanged()
                        show(list)
                    }
                }
            }
        dialog.show(parentFragmentManager, "BetterMessageLoggerAddId")
    }
}


/** The ID lists behind "Servers, channels and people" (logging) or the pre-download choices. */
class FiltersPage(private val mode: Int?) : SettingsPage() {
    constructor() : this(null)

    private lateinit var ui: Ui
    private val summaries = HashMap<IdList, android.widget.TextView>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        ui = Ui(requireContext())
        linearLayout.setBackgroundColor(ui.pageColor)
        setActionBarSubtitle("BetterMessageLogger")
        val settings = pluginSettings()
        when (mode) {
            LOGGING -> {
                setActionBarTitle("Servers, channels and people")
                ui.note(
                    linearLayout,
                    "Blocked places and ignored people are never logged. When an allow list has entries, only what " +
                        "it lists is logged. Long-press a server, channel or DM, or use the three dots on a profile, and " +
                        "pick Message Logger to change these quickly.",
                )
                group("People", IdList.IGNORED_USERS, IdList.ALLOWED_USERS)
                group("Servers", IdList.BLOCKED_SERVERS, IdList.ALLOWED_SERVERS)
                group("Channels", IdList.BLOCKED_CHANNELS, IdList.ALLOWED_CHANNELS)
                group("Direct messages", IdList.BLOCKED_DMS, IdList.ALLOWED_DMS)
            }

            PREFETCH -> {
                setActionBarTitle("Pre-download")
                ui.note(
                    linearLayout,
                    "Pre-downloading saves media as messages arrive, because Discord deletes the files of a deleted " +
                        "message within seconds. \"Only chosen places and people\" pre-downloads from the chosen lists. " +
                        "Excluded ones are " +
                        "always skipped.",
                )
                ui.switch(linearLayout, "Include my messages", null, settings.getBool(Prefs.PREFETCH_OWN, true)) {
                    settings.setBool(Prefs.PREFETCH_OWN, it)
                    BetterMessageLogger.instance?.log?.filtersChanged()
                }
                group("Chosen", IdList.PREFETCH_SERVERS, IdList.PREFETCH_CHANNELS, IdList.PREFETCH_USERS)
                group("Excluded", IdList.SKIP_SERVERS, IdList.SKIP_CHANNELS, IdList.SKIP_USERS)
            }

            else -> ui.note(linearLayout, "Open this page again from the plugin settings.")
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) refresh()
    }

    private fun group(title: String, vararg lists: IdList) {
        ui.divider(linearLayout)
        ui.header(linearLayout, title)
        for (list in lists) {
            summaries[list] = ui.option(linearLayout, list.title, "") {
                Utils.openPageWithProxy(requireContext(), IdListPage(list.key))
            }
        }
    }

    private fun refresh() {
        val settings = pluginSettings()
        for ((list, summary) in summaries) {
            val ids = list.read(settings)
            summary.text = when (ids.size) {
                0 -> "Empty"
                1 -> IdList.describe(ui.context, list.kind, ids.first()) ?: "1 entry"
                else -> "${ids.size} entries"
            }
        }
    }

    companion object {
        const val LOGGING = 0
        const val PREFETCH = 1

        fun open(context: android.content.Context, mode: Int) = Utils.openPageWithProxy(context, FiltersPage(mode))
    }
}
