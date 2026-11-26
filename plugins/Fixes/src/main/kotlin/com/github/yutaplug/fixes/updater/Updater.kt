package com.github.yutaplug.fixes.updater

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.aliucord.Logger
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.utils.ChangelogUtils
import com.github.yutaplug.fixes.OpenActivities
import com.lytefast.flexinput.R
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/** The updater's shared state: the last check, and what is being or has been installed this session. */
internal object Updater {
    val logger = Logger("Fixes/Updater")

    const val CHECK_ON_STARTUP = "updater_checkOnStartup"
    const val AUTO_PLUGINS = "updater_autoPlugins"
    const val AUTO_CORE = "updater_autoCore"
    /** Whether to show the in-app update popups (default on). */
    const val SHOW_POPUPS = "updater_showPopups"

    /** Whether to also post Android notifications for updates (default off). */
    const val SHOW_NOTIFICATIONS = "updater_showNotifications"
    private const val LAST_CHECK = "updater_lastCheck"
    private const val IGNORED = "updater_ignored"

    @Volatile var settings: SettingsAPI? = null

    @Volatile var result: CheckResult? = null
        private set

    @Volatile var checking = false
        private set

    @Volatile var checkError: String? = null
        private set

    @Volatile private var files: GitHubFiles? = null

    /** Names being installed right now. Aliucord itself is [Installed.CORE_NAME]. */
    val installing: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Installed this session, waiting for a restart. */
    val installed: MutableMap<String, Installed> = Collections.synchronizedMap(LinkedHashMap())

    /** Why an install failed this session, by name. */
    val failures: MutableMap<String, String> = Collections.synchronizedMap(HashMap())

    private val listeners = CopyOnWriteArrayList<Runnable>()

    val lastCheck: Long get() = result?.time ?: settings?.getLong(LAST_CHECK, 0L) ?: 0L

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun changed() = Utils.mainThread.post { listeners.forEach { it.run() } }

    fun setting(key: String, default: Boolean): Boolean = settings?.getBool(key, default) ?: default

    /** Plugins the user chose never to check for updates, by name. */
    val ignored: List<String>
        get() = try {
            val array = org.json.JSONArray(settings?.getString(IGNORED, "[]") ?: "[]")
            val list = ArrayList<String>(array.length())
            for (i in 0 until array.length()) list.add(array.getString(i))
            list
        } catch (_: Throwable) {
            emptyList()
        }

    fun isIgnored(name: String): Boolean = name in ignored

    /** Adds or removes [name] from the ignored plugins, then checks again so the lists match. */
    fun setIgnored(name: String, ignore: Boolean) {
        val list = ignored.filter { it != name }.toMutableList()
        if (ignore) list.add(name)
        list.sortBy { it.lowercase() }
        val array = org.json.JSONArray()
        list.forEach { array.put(it) }
        settings?.setString(IGNORED, array.toString())
        // Hide a newly ignored plugin right away; un-ignoring needs a check to know its update.
        result = result?.withIgnored(list)
        changed()
        if (checking) recheck = true else check()
    }

    /** Set when the ignored plugins change during a check, so another check runs after it. */
    @Volatile private var recheck = false

    /** Aliucord's own auto-update settings, used as this updater's defaults. */
    fun aliucordSetting(key: String): Boolean = try {
        com.aliucord.Main.settings.getBool(key, false)
    } catch (_: Throwable) {
        false
    }

    val autoPlugins: Boolean get() = setting(AUTO_PLUGINS, aliucordSetting("AC_plugins_auto_update_enabled"))
    val autoCore: Boolean get() = setting(AUTO_CORE, aliucordSetting("AC_aliucord_auto_update_enabled"))

    /** Callbacks of checks asked for while one was already running; they get that check's result. */
    private val waiting = ArrayList<(CheckResult?) -> Unit>()

    /**
     * Checks for updates, then calls [then] on the main thread. If a check is already running, [then] gets
     * that one's result instead of being dropped (the startup notice was lost that way).
     */
    fun check(then: ((CheckResult?) -> Unit)? = null) {
        synchronized(waiting) {
            if (then != null) waiting.add(then)
            if (checking) return
            checking = true
        }
        checkError = null
        changed()
        Utils.threadPool.execute {
            val checker = UpdateChecker(logger)
            val found = try {
                checker.check()
            } catch (e: Throwable) {
                logger.error("Update check failed", e)
                checkError = UpdateChecker.describe(e)
                null
            }
            if (found != null) {
                result = found
                files = checker.files()
                settings?.setLong(LAST_CHECK, found.time)
            }
            val callbacks = synchronized(waiting) {
                checking = false
                ArrayList(waiting).also { waiting.clear() }
            }
            changed()
            if (callbacks.isNotEmpty()) Utils.mainThread.post { callbacks.forEach { it(found) } }
            if (recheck) {
                recheck = false
                Utils.mainThread.post { check() }
            }
        }
    }

    /** Installs [plugins] and [core] right away, from a popup or notification, then asks to restart. */
    fun installNow(context: Context, plugins: List<PluginUpdate>, core: CoreUpdate?) {
        val count = plugins.size + (if (core != null) 1 else 0)
        Utils.showToast("Downloading ${Utils.pluralise(count, "update")}…")
        install(plugins, core) { done ->
            val failed = (plugins.map { it.name } + listOfNotNull(core?.let { Installed.CORE_NAME }))
                .filter { name -> done.none { it.name == name } }
            if (failed.isNotEmpty()) Utils.showToast("Couldn't update ${names(failed)}", true)
            promptRestart(context, done)
        }
    }

    /** Installs [plugins] and [core] one at a time, then calls [then] on the main thread with what succeeded. */
    fun install(plugins: List<PluginUpdate>, core: CoreUpdate?, then: (List<Installed>) -> Unit) {
        val pluginTargets = plugins.filter { it.canInstall && it.name !in installing && it.name !in installed }
        val coreTarget = core?.takeIf {
            it.reinstallReason == null && Installed.CORE_NAME !in installing && Installed.CORE_NAME !in installed
        }
        if (pluginTargets.isEmpty() && coreTarget == null) {
            then(emptyList())
            return
        }
        pluginTargets.forEach { installing.add(it.name); failures.remove(it.name) }
        if (coreTarget != null) {
            installing.add(Installed.CORE_NAME)
            failures.remove(Installed.CORE_NAME)
        }
        changed()
        Utils.threadPool.execute {
            val files = files ?: GitHubFiles(logger)
            val done = ArrayList<Installed>()
            for (update in pluginTargets) {
                attempt(update.name) { UpdateInstaller.installPlugin(update, files) }?.let { done.add(it) }
            }
            if (coreTarget != null) {
                attempt(Installed.CORE_NAME) { UpdateInstaller.installCore(coreTarget, files) }?.let { done.add(it) }
            }
            changed()
            Utils.mainThread.post { then(done) }
        }
    }

    private inline fun attempt(name: String, install: () -> Installed): Installed? = try {
        val result = install()
        settings?.let { PendingUpdates.add(it, result) }
        installed[name] = result
        logger.info("Installed $name ${result.to}")
        result
    } catch (e: Throwable) {
        logger.error("Could not update $name", e)
        failures[name] = UpdateChecker.describe(e)
        null
    } finally {
        installing.remove(name)
    }

    /** Asks to restart once updates are installed. */
    fun promptRestart(context: Context?, done: List<Installed>) {
        if (done.isEmpty()) return
        val all = installed.values.toList().ifEmpty { done }
        val description = if (all.size == 1) {
            val only = all[0]
            "${only.name} ${only.to} is installed. Restart Aliucord to start using it."
        } else {
            "Updates for ${names(all.map { it.name })} are installed. Restart Aliucord to start using them."
        }
        val manager = fragmentManager(context)
        if (manager == null) {
            Utils.promptRestart("Updates installed. Restart now?")
            return
        }
        val dialog = ConfirmDialog()
        dialog.setTitle("Restart to finish updating")
            .setDescription(description)
            .setOnOkListener { Utils.restartAliucord(it.context) }
            .show(manager, "UpdaterRestart")
        relabel(dialog, 0)
    }

    /** ConfirmDialog always says "OK"; its buttons only exist once it has been shown. */
    private fun relabel(dialog: ConfirmDialog, attempt: Int) {
        Utils.mainThread.postDelayed({
            try {
                dialog.okButton.setText("Restart")
                dialog.cancelButton.text = "Later"
            } catch (_: Throwable) {
                if (attempt < 20 && !dialog.isRemoving) relabel(dialog, attempt + 1)
            }
        }, if (attempt == 0) 0L else 50L)
    }

    fun fragmentManager(context: Context?): FragmentManager? {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = (c as? FragmentActivity)?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: OpenActivities.front() as? FragmentActivity
        return activity?.supportFragmentManager?.takeIf { !it.isStateSaved }
    }

    fun names(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** Opens the changelog for the given updates: one plugin's own, or all of them together. */
    fun showChangelog(context: Context, updates: List<Installed>) {
        if (updates.isEmpty()) return
        Utils.threadPool.execute {
            val single = updates.size == 1
            val body = StringBuilder()
            var media: String? = null
            for (update in updates) {
                val text = if (update.isCore) {
                    CoreBuilds.changelog(update.from.takeIf { it.isNotEmpty() }, update.to, logger)
                } else {
                    update.changelog ?: "* No changelog was published for this version."
                }
                if (single) {
                    body.append(text)
                    media = update.media
                } else {
                    body.append("# ").append(update.name).append(' ').append(update.to).append('\n')
                    body.append(bulletsOnly(text)).append("\n\n")
                }
            }
            val title = if (single) "${updates[0].name} ${updates[0].to}" else "What's new"
            val only = updates.singleOrNull()
            val footer = when {
                only == null -> emptyArray()
                // Aliucord's changelog lives on the documentation site, not on GitHub.
                only.isCore -> arrayOf(ChangelogUtils.FooterAction(R.e.ic_open_in_new_white_24dp, CoreBuilds.CHANGELOG_URL))
                else -> repoUrl(only.repo)?.let { arrayOf(ChangelogUtils.FooterAction(R.e.ic_account_github_white_24dp, it)) }
                    ?: emptyArray()
            }
            Utils.mainThread.post {
                try {
                    ChangelogUtils.show(context, title, media, body.toString().trim(), *footer)
                } catch (e: Throwable) {
                    logger.error("Could not show the changelog", e)
                }
            }
        }
    }

    fun repoUrl(repo: String?): String? = if (repo != null && repo.contains('/') && !repo.contains(' ')) {
        "https://github.com/$repo"
    } else {
        null
    }

    private fun bulletsOnly(text: String): String =
        java.util.regex.Pattern.compile("\\r?\\n").split(text).filter { line -> line.trim().let { it.isNotEmpty() && !it.startsWith("#") } }.joinToString("\n")

    /**
     * After a restart: says which updates were installed, with a button for their changelogs.
     * The pending list is cleared either way, so this only shows once.
     */
    fun showUpdatedNotice() {
        val settings = settings ?: return
        val pending = PendingUpdates.load(settings)
        if (pending.isEmpty()) return
        PendingUpdates.clear(settings)
        val updated = pending.filter { update ->
            val running = if (update.isCore) {
                Versions.core
            } else {
                PluginManager.plugins[update.name]?.manifest?.version
            }
            running != null && !Versions.newer(update.to, running)
        }
        if (updated.isEmpty()) return
        val plugins = updated.filter { !it.isCore }
        val core = updated.firstOrNull { it.isCore }
        val message = when {
            updated.size == 1 -> "Successfully updated ${updated[0].name} to ${updated[0].to}"
            core != null -> "Successfully updated Aliucord to ${core.to} and ${Utils.pluralise(plugins.size, "plugin")}"
            plugins.size <= 3 -> "Successfully updated ${names(plugins.map { "${it.name} ${it.to}" })}"
            else -> "Successfully updated ${plugins.size} plugins"
        }
        UpdatePopups.show(message, "Tap to see what's new.") { showChangelog(it.context, updated) }
        UpdateNotifications.updated(message, updated)
    }
}
