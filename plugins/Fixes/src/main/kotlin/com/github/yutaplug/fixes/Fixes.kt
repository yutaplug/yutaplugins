package com.github.yutaplug.fixes

import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.github.yutaplug.fixes.appactivity.AppActivityFix
import com.github.yutaplug.fixes.attachmentpreview.AttachmentPreviewFix
import com.github.yutaplug.fixes.fontscale.FontScaleFix
import com.github.yutaplug.fixes.forumtag.ForumTagFix
import com.github.yutaplug.fixes.friend.FriendFix
import com.github.yutaplug.fixes.friendrequest.FriendRequestFix
import com.github.yutaplug.fixes.gifdownload.GifDownloadFix
import com.github.yutaplug.fixes.gifsticker.GifStickerFix
import com.github.yutaplug.fixes.ipv4first.IPv4FirstFix
import com.github.yutaplug.fixes.markdown.MarkdownAppearance
import com.github.yutaplug.fixes.markdown.MarkdownFix
import com.github.yutaplug.fixes.messagelink.MessageLinkFix
import com.github.yutaplug.fixes.messagewidth.MessageWidthFix
import com.github.yutaplug.fixes.newbadges.NewBadgesFix
import com.github.yutaplug.fixes.pasteimages.PasteImagesFix
import com.github.yutaplug.fixes.qrlogin.QRCodeLogin
import com.github.yutaplug.fixes.searchhistory.SearchHistoryFix
import com.github.yutaplug.fixes.searchmenu.SearchMessageMenuFix
import com.github.yutaplug.fixes.screenrefresh.ScreenRefreshFix
import com.github.yutaplug.fixes.serverhold.ServerHoldFix
import com.github.yutaplug.fixes.servernickname.ServerNicknameFix
import com.github.yutaplug.fixes.serversettings.ServerSettingsFix
import com.github.yutaplug.fixes.serversheet.ServerSheetFix
import com.github.yutaplug.fixes.settings.SettingsFix
import com.github.yutaplug.fixes.updater.UpdaterFix
import com.github.yutaplug.fixes.validuser.ValidUserFix
import com.github.yutaplug.fixes.videodecoder.VideoDecoderFix
import com.github.yutaplug.fixes.voiceself.VoiceSelfFix
import com.github.yutaplug.fixes.voice.VoiceFix

@AliucordPlugin(requiresRestart = true)
class Fixes : Plugin() {
    internal val fixes: List<Fix> = listOf(
        FontScaleFix(),
        FriendFix(),
        FriendRequestFix(),
        GifDownloadFix(),
        GifStickerFix(),
        MarkdownFix(),
        MessageLinkFix(),
        SearchHistoryFix(),
        ServerSheetFix(),
        SettingsFix(),
        UpdaterFix(),
        ForumTagFix(),
        ServerNicknameFix(),
        QRCodeLogin(),
        MessageWidthFix(),
        ServerSettingsFix(),
        ValidUserFix(),
        AppActivityFix(),
        SearchMessageMenuFix(),
        ServerHoldFix(),
        ScreenRefreshFix(),
        IPv4FirstFix(),
        PasteImagesFix(),
        NewBadgesFix(),
        AttachmentPreviewFix(),
        VoiceFix(),
        VideoDecoderFix(),
        VoiceSelfFix(),
    )

    internal val markdown: MarkdownFix get() = fixes.first { it is MarkdownFix } as MarkdownFix

    /** The plugin's slash commands, for fixes that add one (Plugin.commands is protected). */
    internal val commandsApi: CommandsAPI get() = commands

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

    internal fun isFixEnabled(fix: Fix): Boolean =
        fix.disabledReason == null && settings.getBool(enabledKey(fix), fix.enabledByDefault)

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

    /** Restarts a running fix so it picks up changes to its own options. */
    internal fun restartFix(fix: Fix) {
        if (!running || !fix.started) return
        stopFix(fix, Utils.appContext)
        startFix(fix, Utils.appContext)
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
