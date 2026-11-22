package com.github.yutaplug.fixes

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.github.yutaplug.fixes.fontscale.FontScaleFix
import com.github.yutaplug.fixes.forumtag.ForumTagFix
import com.github.yutaplug.fixes.friend.FriendFix
import com.github.yutaplug.fixes.friendrequest.FriendRequestFix
import com.github.yutaplug.fixes.gifdownload.GifDownloadFix
import com.github.yutaplug.fixes.markdown.MarkdownAppearance
import com.github.yutaplug.fixes.markdown.MarkdownFix
import com.github.yutaplug.fixes.messagelink.MessageLinkFix
import com.github.yutaplug.fixes.qrlogin.QRCodeLogin
import com.github.yutaplug.fixes.rawgithub.RawGitHubUpdater
import com.github.yutaplug.fixes.searchhistory.SearchHistoryFix
import com.github.yutaplug.fixes.servernickname.ServerNicknameFix
import com.github.yutaplug.fixes.serversheet.ServerSheetFix
import com.github.yutaplug.fixes.settings.SettingsFix

@AliucordPlugin(requiresRestart = true)
class Fixes : Plugin() {
    internal val fixes: List<Fix> = listOf(
        FontScaleFix(),
        FriendFix(),
        FriendRequestFix(),
        GifDownloadFix(),
        MarkdownFix(),
        MessageLinkFix(),
        SearchHistoryFix(),
        ServerSheetFix(),
        SettingsFix(),
        RawGitHubUpdater(),
        ForumTagFix(),
        ServerNicknameFix(),
        QRCodeLogin(),
    )

    internal val markdown: MarkdownFix get() = fixes.first { it is MarkdownFix } as MarkdownFix

    @Volatile private var running = false

    init {
        settingsTab = SettingsTab(FixesSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        running = true
        OpenActivities.start()
        fixes.forEach { it.attach(this, patcher) }
        migrateMarkdownSettings()

        // A fix is skipped while the plugin it replaces is still installed, so the two never
        // patch the same methods at once. Uninstall after every plugin has started: unloading
        // during Aliucord's start loop would modify the plugin map while it is iterated.
        val legacy = LegacyPlugins.installed(fixes.map { it.id })
        for (fix in fixes) {
            if (fix.id !in legacy && isFixEnabled(fix)) startFix(fix, context)
        }
        if (legacy.isEmpty()) return
        Utils.mainThread.post {
            val removed = LegacyPlugins.uninstall(legacy, logger)
            if (removed.isNotEmpty()) {
                Utils.showToast("Fixes replaced and uninstalled: ${removed.joinToString(", ")}", true)
            }
            if (!running) return@post
            for (fix in fixes) {
                if (isFixEnabled(fix)) startFix(fix, Utils.appContext)
            }
        }
    }

    override fun stop(context: Context) {
        running = false
        fixes.forEach { stopFix(it, context) }
        patcher.unpatchAll()
        OpenActivities.stop()
    }

    internal fun isFixEnabled(fix: Fix): Boolean = settings.getBool(enabledKey(fix), true)

    internal fun setFixEnabled(fix: Fix, enabled: Boolean) {
        settings.setBool(enabledKey(fix), enabled)
        if (running) {
            if (enabled) startFix(fix, Utils.appContext) else stopFix(fix, Utils.appContext)
            try {
                fix.onToggled()
            } catch (error: Throwable) {
                logger.error("Could not apply ${fix.title}", error)
            }
        }
        // Aliucord's own "A restart is required. Restart now?" snackbar.
        if (fix.requiresRestart) Utils.promptRestart()
    }

    private fun startFix(fix: Fix, context: Context) {
        if (fix.started) return
        try {
            fix.start(context)
            fix.started = true
        } catch (error: Throwable) {
            logger.error("Could not start ${fix.title}", error)
            try {
                fix.stop(context)
            } catch (_: Throwable) {
            }
            fix.patcher.unpatchAll()
        }
    }

    private fun stopFix(fix: Fix, context: Context) {
        if (!fix.started) return
        fix.started = false
        try {
            fix.stop(context)
        } catch (error: Throwable) {
            logger.error("Could not stop ${fix.title}", error)
        } finally {
            fix.patcher.unpatchAll()
        }
    }

    /** Carries over the appearance options of the standalone MarkdownFix plugin once. */
    private fun migrateMarkdownSettings() {
        if (settings.getBool(MARKDOWN_MIGRATED, false)) return
        try {
            val old = SettingsAPI("MarkdownFix")
            val keys = MarkdownAppearance.sizes.map { it.key } + MarkdownAppearance.BULLET_COLOR
            for (key in keys) {
                if (old.exists(key) && !settings.exists(key)) settings.setString(key, old.getString(key, ""))
            }
            val custom = MarkdownAppearance.CUSTOM_BULLET_COLOR
            if (old.exists(custom) && !settings.exists(custom)) settings.setBool(custom, old.getBool(custom, false))
        } catch (error: Throwable) {
            logger.error("Could not import MarkdownFix settings", error)
        }
        settings.setBool(MARKDOWN_MIGRATED, true)
    }

    private fun enabledKey(fix: Fix) = "enabled_${fix.id}"

    private companion object {
        const val MARKDOWN_MIGRATED = "markdownSettingsMigrated"
    }
}
