package com.github.yutaplug.logcat

import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class LogcatSettings(private val plugin: Logcat) : SettingsPage() {
    private var statusView: TextView? = null
    private var networkView: TextView? = null
    private var levelView: TextView? = null
    private var tagsView: TextView? = null
    private var previewView: TextView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Logcat")
        setActionBarSubtitle("Discord's own log output")

        section("Capture")
        statusView = note("")
        note(
            "Only this app's own logs are captured. The log is also saved to a file, so after a crash or " +
                "restart the previous session is shown first, along with the crash if the device still has it. " +
                "Lines from before the plugin started are only partly available, depending on how much your " +
                "device keeps. Some ROMs (MIUI/HyperOS, some Huawei and others) turn logging off, so the log may stay empty unless you enable it in the " +
                "developer or engineering settings.",
        )

        divider()
        section("Debug log")
        note(
            "Discord's in-app debug log, with search, level filters and export. Unlike the old Debug Log page " +
                "and the DebugLogEnhancer plugin, a big log can be shared or saved as a file. Its lines are also " +
                "in the logcat captured here, tagged Discord.",
        )
        action("Open debug log", "Show Discord's debug log") {
            Utils.openPageWithProxy(requireContext(), DebugLogPage())
        }
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Replace Open Debug Log",
                "Aliucord's Open Debug Log entry opens this page instead of Discord's old one",
            ).apply {
                isChecked = plugin.replaceDebugLog
                setOnCheckedListener { plugin.replaceDebugLog = it }
            },
        )

        divider()
        section("Network timings")
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Record network timings",
                "Add how long Discord's DNS lookups, connections, uploads and media downloads take to the log, " +
                    "as ${NetworkTimings.TAG} lines. Discord doesn't log these itself. Useful when media is slow.",
            ).apply {
                isChecked = plugin.networkTimings
                setOnCheckedListener {
                    plugin.networkTimings = it
                    refresh()
                }
            },
        )
        networkView = note("")
        note(
            "Only connections to Discord's servers are recorded. Links are reduced to their shape (like " +
                "/attachments/:id/:id/*.png), so no IDs, file names or tokens are included. For a clean " +
                "measurement, restart Discord, reproduce the problem, then share the log, or set Tags to " +
                "${NetworkTimings.TAG} to see only these lines.",
        )

        divider()
        section("Share")
        note(
            "Discord tokens, Authorization headers and other credentials, emails, URL query strings (signed " +
                "CDN links, upload IDs) and IP addresses other than loopback are redacted. Still check the log " +
                "before posting it publicly: messages and names in it are not removed.",
        )
        action("Copy log", "Copy the most recent part of the log to the clipboard. Use Share log for all of it.") { copy() }
        action("Save to Downloads", "Save the whole log as a text file") { save() }
        action("Share log", "Send the whole log as a text file to another app") { share() }
        action("Clear log", "Remove everything captured so far") { confirmClear() }

        divider()
        section("Filter")
        note("Filters apply to the copied, saved and shared log and the preview below.")
        levelView = action("Minimum level", "") { pickLevel() }
        tagsView = action("Tags", "") { editTags() }

        divider()
        section("Preview")
        action("Refresh", "Show the latest $PREVIEW_LINES lines") { refresh() }
        previewView = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            background = null
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(16))
            add(this)
        }

        refresh()
    }

    private fun refresh() {
        statusView?.text = "${plugin.status}\n${plugin.lineCount()} of up to ${Logcat.MAX_LINES} lines kept " +
            "(${plugin.totalLines} captured).\n" +
            "${plugin.previousLineCount()} lines from before the last restart or crash."
        networkView?.text = plugin.networkSummary()
        levelView?.text = Logcat.LEVELS[plugin.minLevel] + " and above"
        val tags = plugin.tagFilter
        tagsView?.text = if (tags.isEmpty()) "All tags" else tags
        val preview = previewView ?: return
        Utils.threadPool.execute {
            val lines = plugin.filteredLines(PREVIEW_LINES)
            val text = if (lines.isEmpty()) "Nothing captured yet." else lines.joinToString("\n")
            Utils.mainThread.post { if (previewView === preview) preview.text = text }
        }
    }

    // The exports run on a background thread, after which the page may be gone, so they get the app context.
    private fun copy() {
        val app = requireContext().applicationContext
        LogFiles.copy(app, "Discord logcat") { plugin.export(app, it) }
    }

    private fun share() {
        val app = requireContext().applicationContext
        LogFiles.share(this, "discord-logcat", "Discord logcat") { plugin.export(app) }
    }

    private fun save() {
        val app = requireContext().applicationContext
        LogFiles.save(app, "discord-logcat") { plugin.export(app) }
    }

    private fun confirmClear() {
        ConfirmDialog()
            .setTitle("Clear log")
            .setDescription(
                "Remove this session's ${plugin.lineCount()} lines and the ${plugin.previousLineCount()} lines " +
                    "from before the last restart? New lines keep being captured.",
            )
            .apply {
                setOnOkListener {
                    plugin.clear()
                    dismiss()
                    refresh()
                    Utils.showToast("Log cleared")
                }
            }
            .show(parentFragmentManager, "LogcatClear")
    }

    private fun pickLevel() {
        SelectDialog().apply {
            title = "Minimum level"
            items = Array(Logcat.LEVELS.size) { Logcat.LEVELS[it] + " and above" }
            onResultListener = { which ->
                plugin.minLevel = which
                refresh()
            }
        }.show(parentFragmentManager, "LogcatLevel")
    }

    private fun editTags() {
        val dialog = InputDialog()
        dialog.setTitle("Tags")
            .setDescription(
                "Only keep lines whose tag contains one of these, separated by commas " +
                    "(for example: Aliucord, OkHttp, AndroidRuntime). Leave empty for all tags.",
            )
            .setPlaceholderText("Tags")
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .setOnOkListener {
                plugin.tagFilter = dialog.input
                dialog.dismiss()
                refresh()
            }
        dialog.setOnDialogShownListener { dialog.inputLayout.editText?.setText(plugin.tagFilter) }
        dialog.show(parentFragmentManager, "LogcatTags")
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            this.text = text
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    /** Returns the subtitle view so it can be updated. */
    private fun action(title: String, subtitle: String, action: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val sub = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = subtitle
            background = null
            setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        row.addView(sub, LinearLayout.LayoutParams(-1, -2))
        add(row)
        return sub
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(1)),
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }

    override fun onDestroyView() {
        statusView = null
        networkView = null
        levelView = null
        tagsView = null
        previewView = null
        super.onDestroyView()
    }

    companion object {
        private const val PREVIEW_LINES = 100
    }
}
