package com.github.yutaplug.fixes.updater

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.aliucord.patcher.InsteadHook
import com.aliucord.patcher.PreHook
import com.aliucord.settings.AliucordPage
import com.discord.app.AppActivity
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.WidgetSettings
import com.github.yutaplug.fixes.Fix
import com.github.yutaplug.fixes.OpenActivities
import com.lytefast.flexinput.R
import java.lang.ref.WeakReference

internal class UpdaterFix :
    Fix(
        "GitHubUpdater",
        "GitHub updater",
        "Replaces Aliucord's updater, for both Aliucord itself and plugins. Updates are downloaded straight " +
            "from GitHub instead of jsDelivr, whose cache can hold back new versions for hours, and each download " +
            "is checked before it replaces the old version. Asks to restart once updates are installed, and says " +
            "what was updated afterwards, with its changelog.",
    ) {
    private var entry: WeakReference<TextView>? = null
    private val entryListener = Runnable { entry?.get()?.let { label(it) } }

    override fun start(context: Context) {
        Updater.settings = settings
        blockAliucordUpdater()
        replaceSettingsEntry()
        replaceAliucordPageSwitches()
        redirectUpdaterScreen()
        handleNotificationTaps()
        Updater.addListener(entryListener)

        if (!startedThisProcess) {
            startedThisProcess = true
            Updater.showUpdatedNotice()
            if (Updater.setting(Updater.CHECK_ON_STARTUP, true)) startupCheck(0)
        }
    }

    override fun stop(context: Context) {
        Updater.removeListener(entryListener)
        patcher.unpatchAll()
        entry = null
    }

    /**
     * A tapped update notification opens Discord's main screen with an action in its intent: in onCreate
     * when Discord wasn't open, in onNewIntent when it was (the main screen is singleTask).
     */
    private fun handleNotificationTaps() {
        patcher.patch(
            AppActivity::class.java,
            "onCreate",
            arrayOf(Bundle::class.java),
            Hook { param ->
                val activity = param.thisObject as AppActivity
                UpdateNotifications.handle(activity, activity.intent)
            },
        )
        patcher.patch(
            AppActivity::class.java,
            "onNewIntent",
            arrayOf(Intent::class.java),
            Hook { param -> UpdateNotifications.handle(param.thisObject as AppActivity, param.args[0] as? Intent) },
        )
        // When a tap started Discord, plugins may start during that screen's onCreate, too late for the hook.
        Utils.mainThread.post {
            (OpenActivities.front() as? AppActivity)?.let { UpdateNotifications.handle(it, it.intent) }
        }
    }

    /** Aliucord's own startup check, which would also download and notify on its own. */
    private fun blockAliucordUpdater() {
        val hooks = listOf(
            Triple("com.aliucord.updater.CoreUpdater", "checkForUpdates", null),
            Triple("com.aliucord.updater.PluginUpdater", "startupCheck", null),
            // Aliucord skips both checks when this is true; it's small, so the hooks above are kept too.
            Triple("com.aliucord.updater.CoreUpdater", "isUpdaterDisabled", true),
        )
        for ((className, method, result) in hooks) {
            try {
                val m = Class.forName(className).getDeclaredMethod(method)
                patcher.patch(m, InsteadHook { result })
            } catch (e: Throwable) {
                logger.warn("Could not replace $className.$method: ${e.message}")
            }
        }
    }

    /** Points the "Updater" row in the Aliucord section of Discord's settings at the new screen. */
    private fun replaceSettingsEntry() {
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { param ->
                val root = param.args[0] as? View ?: return@Hook
                // After Aliucord's own hook has added the row.
                Utils.mainThread.post {
                    val row = findText(root, "Updater") ?: return@post
                    row.setOnClickListener { openUpdater(it.context) }
                    entry = WeakReference(row)
                    label(row)
                }
            },
        )
    }

    private fun label(row: TextView) {
        val count = Updater.result?.count ?: 0
        val pending = Updater.installed.size
        val text = SpannableStringBuilder("Updater")
        val extra = when {
            pending > 0 -> "Restart to finish"
            count > 0 -> Utils.pluralise(count, "update")
            else -> null
        }
        if (extra != null) {
            val start = text.length
            text.append("  ").append(extra)
            text.setSpan(ForegroundColorSpan(if (pending > 0) UpdaterUi.GREEN else UpdaterUi.BRAND), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.text = text
    }

    /** Hides Aliucord's own auto-update switches, which this updater replaces, and links to its settings. */
    private fun replaceAliucordPageSwitches() {
        patcher.patch(
            AliucordPage::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { param ->
                val page = param.thisObject as? SettingsPage ?: return@Hook
                Utils.mainThread.post {
                    try {
                        replaceSwitches(page.linearLayout)
                    } catch (e: Throwable) {
                        logger.error("Could not replace Aliucord's updater settings", e)
                    }
                }
            },
        )
    }

    private fun replaceSwitches(layout: LinearLayout) {
        if (layout.findViewWithTag<View>(ROW_TAG) != null) return
        var insertAt = -1
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child !is CheckedSetting) continue
            if (findAnyText(child, HIDDEN_SWITCHES) == null) continue
            child.visibility = View.GONE
            if (insertAt < 0) insertAt = i
        }
        if (insertAt < 0) return
        val context = layout.context
        val row = TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            tag = ROW_TAG
            text = "Updater settings"
            val attribute = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                attribute.resourceId != 0
            ) {
                background = context.getDrawable(attribute.resourceId)
            }
            val tint = UpdaterUi.color(context, "colorInteractiveNormal", Color.LTGRAY)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                UpdaterUi.drawable(context, "ic_file_download_white_24dp", tint),
                null,
                null,
                null,
            )
            isClickable = true
            setOnClickListener { openUpdater(it.context) }
        }
        layout.addView(row, insertAt, LinearLayout.LayoutParams(-1, -2))
    }

    /** Anything else that opens Aliucord's Updater screen opens this one instead. */
    private fun redirectUpdaterScreen() {
        try {
            val screen = Class.forName("com.aliucord.screens.UpdaterScreen")
            patcher.patch(
                Utils::class.java.getDeclaredMethod("openPage", Context::class.java, Class::class.java, Intent::class.java),
                PreHook { param ->
                    if (param.args[1] != screen) return@PreHook
                    val context = param.args[0] as? Context ?: return@PreHook
                    param.result = null
                    openUpdater(context)
                },
            )
        } catch (e: Throwable) {
            logger.warn("Could not redirect Aliucord's Updater screen: ${e.message}")
        }
    }

    /**
     * The startup check, a few seconds after launch. Discord is still connecting then, so if GitHub couldn't
     * be reached (the whole check failed, or a repository couldn't be read), it tries again a bit later.
     */
    private fun startupCheck(attempt: Int) {
        Utils.mainThread.postDelayed({
            if (!started) return@postDelayed
            Updater.logger.info("Startup update check, attempt ${attempt + 1}")
            Updater.check { result ->
                // Only network trouble is worth retrying, not e.g. a plugin missing from its repo's updater.
                val failed = result == null || result.coreError != null && result.latestCore == null ||
                    result.errors.values.any { it in RETRYABLE }
                Updater.logger.info(
                    if (result == null) {
                        "Startup check failed: ${Updater.checkError}"
                    } else {
                        "Startup check found ${result.count} updates, couldn't check ${result.errors.keys}" +
                            (result.coreError?.let { ", Aliucord: $it" } ?: "")
                    },
                )
                if (failed && attempt < 3) {
                    // Show what was found now; the retry only adds what it couldn't check.
                    if (result != null) onStartupCheck(result)
                    startupCheck(attempt + 1)
                } else {
                    onStartupCheck(result)
                }
            }
        }, if (attempt == 0) 3000L else 20_000L)
    }

    /** Updates already announced in a notification this session, so a retried startup check doesn't repeat them. */
    private val announced = HashSet<String>()

    private fun onStartupCheck(result: CheckResult?) {
        if (result == null || !started) return
        // Skip what an earlier attempt already installed or announced.
        val fresh = result.plugins.filter {
            it.name !in Updater.installed && it.name !in Updater.installing && "${it.name} ${it.build.version}" !in announced
        }
        val freshCore = result.core?.takeIf {
            Installed.CORE_NAME !in Updater.installed && Installed.CORE_NAME !in Updater.installing &&
                "${Installed.CORE_NAME} ${it.latestVersion}" !in announced
        }
        val plugins = if (Updater.autoPlugins) fresh.filter { it.canInstall } else emptyList()
        val core = freshCore?.takeIf { Updater.autoCore && it.reinstallReason == null }
        if (plugins.isNotEmpty() || core != null) {
            Updater.install(plugins, core) { done -> Updater.promptRestart(null, done) }
        }
        val manualPlugins = fresh.filter { it !in plugins }
        val manualCore = freshCore?.takeIf { core == null }
        if (manualPlugins.isEmpty() && manualCore == null) return
        manualPlugins.forEach { announced.add("${it.name} ${it.build.version}") }
        manualCore?.let { announced.add("${Installed.CORE_NAME} ${it.latestVersion}") }
        Updater.logger.info("Notifying about ${manualPlugins.map { it.name } + listOfNotNull(manualCore?.let { "Aliucord" })}")
        showUpdatesFound(manualPlugins, manualCore)
    }

    /** A notification listing the updates found: tapping it opens the Updater, its Update button installs them. */
    private fun showUpdatesFound(plugins: List<PluginUpdate>, core: CoreUpdate?) {
        val versions = listOfNotNull(core?.let { "Aliucord ${it.latestVersion}" }) +
            plugins.map { "${it.name} ${it.build.version}" }
        val title = if (versions.size == 1) "Update available" else "${versions.size} updates available"
        val list = if (versions.size <= 4) {
            Updater.names(versions)
        } else {
            "${versions.take(3).joinToString(", ")} and ${versions.size - 3} more"
        }
        val installablePlugins = plugins.filter { it.canInstall }
        val installableCore = core?.takeIf { it.reinstallReason == null }
        val canInstall = installablePlugins.isNotEmpty() || installableCore != null
        if (canInstall) {
            UpdatePopups.show(title, "$list\nTap to update.") { Updater.installNow(it.context, installablePlugins, installableCore) }
        } else {
            // Every update needs something first (e.g. a newer Aliucord); the Updater says what.
            UpdatePopups.show(title, "$list\nTap to see what it needs.") { openUpdater(it.context) }
        }
        // Off by default; without an installable update it has no Update button.
        UpdateNotifications.updatesFound(title, list, installablePlugins, installableCore)
    }

    companion object {
        /** [UpdateChecker.describe]'s reasons that mean GitHub couldn't be reached, so a retry may help. */
        private val RETRYABLE = setOf("No internet connection", "Timed out")

        private const val ROW_TAG = "fixes_updater_settings"
        private val HIDDEN_SWITCHES = setOf("Automatically update Aliucord", "Automatically update plugins", "Disable Updater")

        @Volatile private var startedThisProcess = false

        fun openUpdater(context: Context) {
            Utils.openPageWithProxy(context, UpdaterPage())
        }

        private fun findText(view: View, text: String): TextView? = findAnyText(view, setOf(text))

        private fun findAnyText(view: View, texts: Set<String>): TextView? {
            if (view is TextView && view.text?.toString() in texts) return view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) findAnyText(view.getChildAt(i), texts)?.let { return it }
            }
            return null
        }
    }
}
