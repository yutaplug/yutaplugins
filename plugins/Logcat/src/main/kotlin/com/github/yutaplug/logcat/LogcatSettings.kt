package com.github.yutaplug.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * The clipboard goes through a binder transaction with a size limit that differs per device, and
     * a too-big clip throws, so try smaller and smaller parts of the log until one fits.
     */
    private fun copy() {
        val context = requireContext().applicationContext
        Utils.threadPool.execute {
            val attempts = CLIPBOARD_SIZES.map { plugin.export(context, it) }
            Utils.mainThread.post {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                var error: Throwable? = null
                for ((i, text) in attempts.withIndex()) {
                    try {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Discord logcat", text))
                        Utils.showToast(if (i == 0) "Log copied" else "Copied the latest part of the log (too big for the clipboard)")
                        return@post
                    } catch (e: Throwable) {
                        error = e
                    }
                }
                Utils.showToast(
                    "Could not copy the log: ${error?.message ?: error?.javaClass?.simpleName}. Try Share log instead.",
                    true,
                )
            }
        }
    }

    /**
     * Shares the log as a file through Discord's own FileProvider. Putting the text in EXTRA_TEXT
     * goes over the binder size limit for big logs, and the share sheet then never opens.
     */
    private fun share() {
        val context = requireContext().applicationContext
        Utils.threadPool.execute {
            val result = try {
                val dir = File(context.filesDir, "logcat-share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, fileName())
                file.writeText(plugin.export(context), Charsets.UTF_8)
                FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            } catch (e: Throwable) {
                e
            }
            Utils.mainThread.post {
                if (result is Throwable) {
                    Utils.showToast("Could not share the log: ${result.message ?: result.javaClass.simpleName}", true)
                    return@post
                }
                val uri = result as Uri
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Discord logcat")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Discord logcat", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val activity = activity ?: Utils.appActivity
                try {
                    activity.startActivity(
                        Intent.createChooser(intent, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (e: Throwable) {
                    Utils.showToast("Could not share the log: ${e.message ?: e.javaClass.simpleName}", true)
                }
            }
        }
    }

    /** Aliucord installs can rename the authority, so read it from the installed manifest. */
    private fun fileProviderAuthority(context: Context): String {
        @Suppress("DEPRECATION")
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers
        providers?.forEach { if (it.name == "androidx.core.content.FileProvider") return it.authority }
        return "com.discord.file-provider"
    }

    private fun fileName() =
        "discord-logcat-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    private fun save() {
        val context = requireContext().applicationContext
        Utils.threadPool.execute {
            val result = try {
                "Saved to Downloads/" + writeToDownloads(context, plugin.export(context))
            } catch (e: Throwable) {
                "Could not save the log: ${e.message ?: e.javaClass.simpleName}. Try Share log instead."
            }
            Utils.mainThread.post { Utils.showToast(result, true) }
        }
    }

    private fun writeToDownloads(context: Context, text: String): String {
        val name = fileName()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("could not create the file")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("could not open the file")).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else name
                } ?: name
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not open Downloads")
        val file = File(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        return file.name
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

        /** Sizes to try, largest first; the clipboard's binder buffer is ~1 MB shared with other calls. */
        private val CLIPBOARD_SIZES = intArrayOf(100_000, 40_000, 15_000)
    }
}
