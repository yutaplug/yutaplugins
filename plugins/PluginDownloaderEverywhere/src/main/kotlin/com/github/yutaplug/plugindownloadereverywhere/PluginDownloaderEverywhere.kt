package com.github.yutaplug.plugindownloadereverywhere

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.aliucord.Constants
import com.aliucord.Http
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.filename
import com.aliucord.wrappers.messages.AttachmentWrapper.Companion.url
import com.discord.app.AppBottomSheet
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.WidgetUrlActions
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipFile

/** One entry to add to a long-press menu. */
private class Entry(val text: String, val onClick: (AppBottomSheet) -> Unit)

/** A plugin zip found in a message, with the name it will most likely install as. */
private class Download(val name: String, val url: String)

@AliucordPlugin(requiresRestart = false)
class PluginDownloaderEverywhere : Plugin() {
    private val entryId = View.generateViewId()

    override fun start(context: Context) {
        patchMessageActions()
        patchUrlActions()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    // region Menus

    /** Message long-press menu: every .zip link and .zip attachment, and GitHub repo links. */
    private fun patchMessageActions() {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { frame ->
                val sheet = frame.thisObject as WidgetChatListActions
                val message = (frame.args[0] as WidgetChatListActions.Model).message ?: return@Hook
                val container = sheet.view?.findViewById<LinearLayout>(
                    Utils.getResId("dialog_chat_actions_container", "id"),
                ) ?: return@Hook
                if (container.findViewById<View>(entryId) != null) return@Hook

                val entries = mutableListOf<Entry>()
                val seen = mutableSetOf<String>()
                scanText(message.content.orEmpty(), entries, seen)
                for (attachment in message.attachments.orEmpty()) {
                    val filename = attachment.filename ?: continue
                    val url = attachment.url ?: continue
                    if (!filename.endsWith(".zip", true)) continue
                    addDownload(Download(filename.dropLast(4), url), entries, seen)
                }
                if (entries.isEmpty()) return@Hook

                // Run after Aliucord's own downloader (in its plugin channels) added its rows, to skip those.
                container.post {
                    if (container.findViewById<View>(entryId) != null) return@post
                    val edit = container.findViewById<View>(Utils.getResId("dialog_chat_actions_edit", "id"))
                    addEntries(sheet, container, edit, entries, "widget_chat_list_actions", "dialog_chat_actions_reply")
                }
            },
        )
    }

    /** Link long-press menu (any link in any message): the link itself, if it's a .zip or a GitHub repo. */
    private fun patchUrlActions() {
        patcher.patch(
            WidgetUrlActions::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java),
            Hook { frame ->
                val sheet = frame.thisObject as WidgetUrlActions
                val root = frame.args[0] as? ViewGroup ?: return@Hook
                val url = sheet.arguments?.getString("INTENT_URL") ?: return@Hook

                val entries = mutableListOf<Entry>()
                scanText(url, entries, mutableSetOf())
                if (entries.isEmpty()) return@Hook

                root.post {
                    if (root.findViewById<View>(entryId) != null) return@post
                    val copy = root.findViewById<View>(Utils.getResId("dialog_url_actions_copy", "id"))
                    addEntries(sheet, root, copy, entries, "widget_url_actions", "dialog_url_actions_copy")
                }
            },
        )
    }

    /** Adds the rows above [before] (or at the end), skipping any Aliucord's own downloader already shows. */
    private fun addEntries(
        sheet: AppBottomSheet,
        container: ViewGroup,
        before: View?,
        entries: List<Entry>,
        templateLayout: String,
        templateRow: String,
    ) {
        val existing = mutableSetOf<String>()
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? TextView)?.text?.toString()?.let { existing.add(it) }
        }
        var index = if (before == null || before.parent !== container) container.childCount else container.indexOfChild(before)
        for (entry in entries) {
            if (entry.text in existing) continue
            val row = makeRow(container, templateLayout, templateRow, entry.text) ?: return
            row.setOnClickListener { entry.onClick(sheet) }
            container.addView(row, index++)
        }
    }

    /**
     * A copy of one of the menu's own rows (inflated from Discord's layout, so it has the same padding, font and
     * icon spacing as its neighbours) with the download icon.
     */
    private fun makeRow(container: ViewGroup, layout: String, rowId: String, text: String): TextView? {
        val ctx = container.context
        val template = LayoutInflater.from(ctx).inflate(Utils.getResId(layout, "layout"), container, false) as ViewGroup
        val row = template.findViewById<TextView>(Utils.getResId(rowId, "id")) ?: return null
        (row.parent as ViewGroup).removeView(row)
        row.id = entryId
        row.text = text
        row.visibility = View.VISIBLE
        val icon = Utils.getResId("ic_file_download_white_24dp", "drawable")
        if (icon != 0) {
            ContextCompat.getDrawable(ctx, icon)?.mutate()?.let {
                it.setTint(ColorCompat.getThemedColor(ctx, Utils.getResId("colorInteractiveNormal", "attr")))
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
            }
        }
        return row
    }

    // endregion

    // region Scanning

    private fun scanText(text: String, entries: MutableList<Entry>, seen: MutableSet<String>) {
        if (text.indexOf("http", ignoreCase = true) < 0) return

        val repos = repoPattern.matcher(text)
        while (repos.find()) {
            val owner = repos.group(1) ?: continue
            val repo = repos.group(2) ?: continue
            if (owner.lowercase() in reservedGitHubPaths) continue
            if (!seen.add("repo:${owner.lowercase()}/${repo.lowercase()}")) continue
            entries += Entry("View $owner's Plugins") { sheet ->
                openRepo(sheet, owner, repo)
            }
        }

        val zips = zipPattern.matcher(text)
        while (zips.find()) {
            val url = zips.group(0) ?: continue
            val name = zips.group(1) ?: continue
            addDownload(Download(name, downloadUrl(url)), entries, seen)
        }
    }

    private fun addDownload(download: Download, entries: MutableList<Entry>, seen: MutableSet<String>) {
        val name = cleanName(download.name) ?: return
        if (name.equals("Aliucord", true)) return
        if (!seen.add(download.url)) return
        val installed = PluginManager.plugins.containsKey(name)
        entries += Entry("${if (installed) "Reinstall" else "Install"} $name") { sheet ->
            confirmInstall(sheet, name, download.url)
        }
    }

    /** GitHub "blob" pages are HTML; their "raw" twin is the file itself. */
    private fun downloadUrl(url: String): String {
        val m = githubBlobPattern.matcher(url)
        return if (m.find()) "https://github.com/${m.group(1)}/${m.group(2)}/raw/${m.group(3)}" else url
    }

    /** "Fixes (1)" → "Fixes": Discord and browsers rename re-downloaded files. Only safe file names pass. */
    private fun cleanName(raw: String): String? {
        val name = copySuffixPattern.matcher(raw.trim()).replaceAll("").trim()
        return if (name.isNotEmpty() && safeNamePattern.matcher(name).matches()) name else null
    }

    // endregion

    // region Actions

    private fun openRepo(sheet: AppBottomSheet, owner: String, repo: String) {
        val ctx = sheet.context ?: return
        try {
            // Aliucord's own repo page (internal class), which lists the repo's builds/updater.json.
            val page = Class.forName("com.aliucord.coreplugins.plugindownloader.PluginRepoModal")
                .getDeclaredConstructor(String::class.java, String::class.java)
                .apply { isAccessible = true }
                .newInstance(owner, repo) as Fragment
            sheet.dismiss()
            Utils.openPageWithProxy(ctx, page)
        } catch (e: Throwable) {
            logger.error("Couldn't open Aliucord's plugin repo page", e)
            Utils.showToast("Couldn't open $owner/$repo's plugins")
        }
    }

    private fun confirmInstall(sheet: AppBottomSheet, name: String, url: String) {
        val manager: FragmentManager = try {
            sheet.parentFragmentManager
        } catch (e: IllegalStateException) {
            return
        }
        sheet.dismiss()
        val installed = PluginManager.plugins.containsKey(name)
        val host = hostPattern.matcher(url).let { if (it.find()) it.group(1) else url }
        val dialog = ConfirmDialog()
            .setTitle("${if (installed) "Reinstall" else "Install"} $name?")
            .setDescription(
                "This downloads $name from $host and runs it as a plugin. Plugins can do anything " +
                    "with your account and app, so only install plugins from people you trust.",
            )
        dialog.setOnOkListener {
            dialog.dismiss()
            install(name, url)
        }
        dialog.show(manager, "PluginDownloaderEverywhere")
    }

    private fun install(fallbackName: String, url: String) {
        Utils.showToast("Downloading $fallbackName…")
        Utils.threadPool.execute {
            val temp = File(Utils.appContext.cacheDir, "plugindownloader-${System.nanoTime()}.zip")
            try {
                Http.simpleDownload(url, temp)
                val name = pluginName(temp) ?: run {
                    Utils.showToast("$fallbackName isn't an Aliucord plugin")
                    return@execute
                }
                if (name.equals("Aliucord", true) || isCorePlugin(name)) {
                    Utils.showToast("$name is built into Aliucord and can't be replaced")
                    return@execute
                }
                val target = File(Constants.PLUGINS_PATH, "$name.zip")
                target.parentFile?.mkdirs()
                Utils.mainThread.post { load(name, temp, target) }
            } catch (e: Throwable) {
                logger.error("Failed to download $url", e)
                Utils.showToast("Failed to download $fallbackName: ${e.message}")
                temp.delete()
            }
        }
    }

    /** Same steps as Aliucord's own downloader (PluginFile.install), on the main thread. */
    private fun load(name: String, temp: File, target: File) {
        try {
            val reinstall = PluginManager.plugins.containsKey(name)
            if (reinstall) {
                PluginManager.stopPlugin(name)
                PluginManager.unloadPlugin(name)
            }
            temp.copyTo(target, overwrite = true)
            if (PluginManager.isSafeModeEnabled()) {
                Utils.showToast("$name installed, but not loaded due to safe mode")
                return
            }
            PluginManager.loadPlugin(Utils.appContext, target)
            val plugin = PluginManager.plugins[name]
            if (plugin == null) {
                Utils.showToast("$name was installed but failed to load, see the debug log")
                return
            }
            if (PluginManager.isPluginEnabled(name)) PluginManager.startPlugin(name) else PluginManager.enablePlugin(name)
            Utils.showToast("$name v${plugin.manifest.version} ${if (reinstall) "reinstalled" else "installed"}!")
            if (plugin.requiresRestart()) Utils.promptRestart()
        } catch (e: Throwable) {
            logger.error("Failed to install $name", e)
            Utils.showToast("Failed to install $name: ${e.message}")
        } finally {
            temp.delete()
        }
    }

    /** Aliucord's built-in plugins (its CorePlugin class is internal, so check by name). */
    private fun isCorePlugin(name: String): Boolean {
        var cls: Class<*>? = PluginManager.plugins[name]?.javaClass ?: return false
        while (cls != null) {
            if (cls.name == "com.aliucord.entities.CorePlugin") return true
            cls = cls.superclass
        }
        return false
    }

    /** The plugin's own name from its manifest.json, or null if the zip isn't an Aliucord plugin. */
    private fun pluginName(zip: File): String? = try {
        ZipFile(zip).use { file ->
            if (file.getEntry("classes.dex") == null) return null
            val manifest = file.getEntry("manifest.json") ?: return null
            val json = file.getInputStream(manifest).bufferedReader().use { it.readText() }
            cleanName(JSONObject(json).optString("name"))
        }
    } catch (e: Throwable) {
        logger.error("Couldn't read ${zip.name}", e)
        null
    }

    // endregion

    private companion object {
        /** Any http(s) link whose path ends in `<name>.zip` (query and fragment allowed after it). */
        val zipPattern: Pattern = Pattern.compile(
            """https?://[^\s<>"'`|]+?/([^/\s<>"'`|?#]+)\.zip(?:[?#][^\s<>"'`|)\]]*)?(?=$|[\s<>"'`|)\]])""",
            Pattern.CASE_INSENSITIVE,
        )

        /** A bare GitHub repository link: `https://github.com/<owner>/<repo>`. */
        val repoPattern: Pattern = Pattern.compile(
            """https?://(?:www\.)?github\.com/([A-Za-z0-9\-_.]+)/([A-Za-z0-9\-_.]+?)(?:\.git)?/?(?=$|[\s<>"'`|)\]?#])""",
        )

        val githubBlobPattern: Pattern = Pattern.compile("""^https?://(?:www\.)?github\.com/([^/]+)/([^/]+)/blob/(.+)$""")
        val hostPattern: Pattern = Pattern.compile("""^https?://([^/?#]+)""")
        val copySuffixPattern: Pattern = Pattern.compile("""\s*\(\d+\)$""")
        val safeNamePattern: Pattern = Pattern.compile("""[A-Za-z0-9_\-.]+""")

        /** github.com/<these>/… aren't users. */
        val reservedGitHubPaths = setOf(
            "orgs", "settings", "features", "topics", "collections", "marketplace", "sponsors", "apps", "login",
            "notifications", "explore", "search", "about", "pricing", "enterprise", "security", "site",
        )
    }
}
