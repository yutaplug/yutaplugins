package com.github.yutaplug.themes.settings

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.ToolbarButton
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.intent.IntentUtils
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.github.yutaplug.themes.ColorEngine
import com.github.yutaplug.themes.DeadThemes
import com.github.yutaplug.themes.THEME_DIR
import com.github.yutaplug.themes.ThemeException
import com.github.yutaplug.themes.ThemeFile
import com.github.yutaplug.themes.ThemeStore
import com.github.yutaplug.themes.Themes
import com.github.yutaplug.themes.TransparencyMode
import com.github.yutaplug.themes.isThemerActive
import com.github.yutaplug.themes.logger
import com.lytefast.flexinput.R

class ThemesSettings(private val plugin: Themes) :
    SettingsPage(),
    ReopenablePage {
    override fun reopen() = ThemesSettings(plugin)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Themes")
        setActionBarSubtitle("${ThemeStore.enabledIds().size} enabled")
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))

        if (isThemerActive()) {
            section("Themer is enabled")
            note("The old Themer plugin is still enabled. Disable it, or both will recolor the app at once.")
            divider()
        }
        val discordTheme = StoreStream.getUserSettingsSystem().theme
        if (discordTheme != "dark" && ThemeStore.enabledIds().isNotEmpty()) {
            action("Switch to Dark", "Most themes are made for Discord's regular Dark theme") {
                ConfirmDialog()
                    .apply {
                        setTitle("Switch to Dark?")
                        setDescription("Most themes only look right on Discord's regular Dark theme.")
                        setOnOkListener {
                            // Setting "dark" while on "pureEvil" keeps pureEvil, so go through light.
                            StoreStream.getUserSettingsSystem().setTheme("light", false, null)
                            StoreStream.getUserSettingsSystem().setTheme("dark", false, null)
                            dismiss()
                        }
                    }.show(parentFragmentManager, "ThemesSwitchDark")
            }
            divider()
        }

        themeList()
        divider()
        options()
        divider()
        status()
    }

    private fun themeList() {
        section("Themes")
        note(
            if (ThemeStore.themes.isEmpty()) {
                "No themes installed. Long-press a theme in Aliucord's #themes channel and tap Install, " +
                    "or put .json files in ${THEME_DIR.absolutePath}."
            } else {
                "Enabled themes stack: later ones win. Long-press a theme to reorder or update it."
            },
        )
        val enabled = ThemeStore.enabledIds()
        ThemeStore.themes.forEach { theme -> add(themeRow(theme, enabled.indexOf(theme.id))) }

        action("Install themes", "Open Aliucord's #themes channel. Long-press a theme there and tap Install") {
            openThemesChannel()
        }
        action("New theme", "Create an empty theme and edit it") {
            val dialog = InputDialog()
                .setTitle("New theme")
                .setDescription("Choose a name for your theme")
                .setPlaceholderText("Name")
            dialog.setOnOkListener {
                try {
                    val theme = ThemeFile.create(dialog.input)
                    dialog.dismiss()
                    ThemeStore.loadThemes()
                    Utils.openPageWithProxy(requireContext(), ThemeEditor(plugin, theme))
                } catch (error: ThemeException) {
                    Utils.showToast(error.message, true)
                } catch (error: Throwable) {
                    logger.errorToast("Failed to create theme", error)
                }
            }
            dialog.show(parentFragmentManager, "ThemesNew")
        }
        action("Reload themes", "Read the themes folder again and reapply") {
            ThemeStore.loadThemes()
            plugin.handleDeadThemes(ThemeStore.enabledThemes())
            plugin.reload { if (view != null) reRender() }
            reRender()
        }
        action("Check for updates", "Update enabled themes that have an updater link") {
            Utils.showToast("Checking for theme updates…")
            Utils.threadPool.execute {
                plugin.checkUpdates { updated ->
                    if (updated.isEmpty()) Utils.showToast("All themes are up to date")
                    if (view != null) reRender()
                }
            }
        }
    }

    /** Opens #themes through Discord's own channel link, or the server invite when not a member. */
    private fun openThemesChannel() {
        val ctx = requireContext()
        try {
            val joined = StoreStream.getGuilds().getGuild(Constants.ALIUCORD_GUILD_ID) != null
            val intent = if (joined) {
                IntentUtils.RouteBuilders.selectChannel(Constants.THEMES_CHANNEL_ID, Constants.ALIUCORD_GUILD_ID, null)
            } else {
                Utils.showToast("Join the Aliucord server to browse themes")
                Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.gg/${Constants.ALIUCORD_SUPPORT}"))
            }
            startActivity(intent.setPackage(ctx.packageName))
        } catch (error: Throwable) {
            logger.errorToast("Couldn't open the #themes channel", error)
        }
    }

    private fun themeRow(theme: ThemeFile, layer: Int): View {
        val ctx = requireContext()
        val subtitle = buildString {
            append("by ").append(theme.author)
            theme.license?.let { append(" · ").append(it) }
            if (layer >= 0 && ThemeStore.enabledIds().size > 1) append(" · layer ").append(layer + 1)
            if (DeadThemes.uses(theme)) append(" · background was deleted from Discord")
        }
        val switch = Utils
            .createCheckedSetting(
                ctx,
                CheckedSetting.ViewType.SWITCH,
                "${theme.name} v${theme.version}",
                subtitle,
            ).apply {
                isChecked = layer >= 0
                setOnCheckedListener { checked ->
                    if (checked && DeadThemes.uses(theme)) {
                        // Explains the deleted background and offers the mirror copy, enabling it once replaced.
                        plugin.handleDeadThemes(listOf(theme), setOf(theme.id))
                        post { if (view != null) reRender() }
                        return@setOnCheckedListener
                    }
                    ThemeStore.setEnabled(theme, checked)
                    setActionBarSubtitle("${ThemeStore.enabledIds().size} enabled")
                    plugin.reload { if (view != null) reRender() }
                    // Turns the theme back off with an explanation if its Discord background was deleted.
                    if (checked) plugin.handleDeadThemes(listOf(theme)) { if (it.isNotEmpty() && view != null) reRender() }
                }
            }

        fun button(icon: String, description: String, onClick: () -> Unit) = ToolbarButton(ctx).apply {
            ContextCompat.getDrawable(ctx, Utils.getResId(icon, "drawable"))?.mutate()?.let {
                Utils.tintToTheme(it)
                setImageDrawable(it, false)
            }
            contentDescription = description
            val p = DimenUtils.dpToPx(12)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(switch, LinearLayout.LayoutParams(0, -2, 1f))
            val size = DimenUtils.dpToPx(48)
            addView(
                button("ic_edit_24dp", "Edit ${theme.name}") {
                    Utils.openPageWithProxy(ctx, ThemeEditor(plugin, theme))
                },
                LinearLayout.LayoutParams(size, size),
            )
            addView(
                button("ic_delete_24dp", "Delete ${theme.name}") { confirmDelete(theme) },
                LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = DimenUtils.dpToPx(8)
                },
            )
            switch.setOnLongClickListener {
                showMenu(theme)
                true
            }
        }
    }

    private fun showMenu(theme: ThemeFile) {
        val enabled = ThemeStore.isEnabled(theme)
        val options = ArrayList<Pair<String, () -> Unit>>()
        options += "Edit" to { Utils.openPageWithProxy(requireContext(), ThemeEditor(plugin, theme)) }
        if (enabled) {
            options += "Move up a layer" to { if (ThemeStore.move(theme, true)) afterChange() }
            options += "Move down a layer" to { if (ThemeStore.move(theme, false)) afterChange() }
        }
        if (theme.updaterUrl != null) {
            options += "Check for update" to {
                Utils.threadPool.execute {
                    val result = runCatching { theme.update() }
                    Utils.mainThread.post {
                        result.onFailure { logger.errorToast("Failed to update ${theme.name}", it) }
                        result.onSuccess { updated ->
                            Utils.showToast(if (updated) "Updated ${theme.name}" else "${theme.name} is up to date")
                            if (updated) afterChange()
                        }
                    }
                }
            }
        }
        options += "Copy file path" to {
            Utils.setClipboard(theme.name, theme.file.absolutePath)
            Utils.showToast("Copied")
        }
        options += "Delete" to { confirmDelete(theme) }
        SelectDialog()
            .apply {
                title = theme.name
                items = options.map { it.first }.toTypedArray()
                onResultListener = { options[it].second() }
            }.show(parentFragmentManager, "ThemesMenu")
    }

    private fun afterChange() {
        ThemeStore.loadThemes()
        plugin.reload { if (view != null) reRender() }
        reRender()
    }

    private fun confirmDelete(theme: ThemeFile) {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Delete ${theme.name}?")
            .setDescription("The theme file will be deleted. This can't be undone.")
            .setIsDangerous(true)
            .setOnOkListener {
                dialog.dismiss()
                val wasEnabled = ThemeStore.isEnabled(theme)
                if (theme.file.delete()) {
                    ThemeStore.setEnabled(theme, false)
                    ThemeStore.loadThemes()
                    Utils.showToast("Deleted ${theme.name}")
                    if (wasEnabled) plugin.reload()
                    reRender()
                } else {
                    Utils.showToast("Failed to delete ${theme.name}")
                }
            }
        dialog.show(parentFragmentManager, "ThemesDelete")
    }

    private fun options() {
        val ctx = requireContext()
        section("Background transparency")
        note("Where a theme's background image shows. The theme's colors must also be transparent there.")
        val radios = TransparencyMode.values().map {
            Utils.createCheckedSetting(ctx, CheckedSetting.ViewType.RADIO, it.label, it.description)
        }
        val manager = RadioManager(radios)
        manager.a(radios[ThemeStore.transparencyMode.value])
        radios.forEachIndexed { i, radio ->
            radio.e {
                manager.a(radio)
                ThemeStore.transparencyMode = TransparencyMode.from(i)
                plugin.reloadNow()
            }
            add(radio)
        }

        divider()
        section("Randomizer")
        add(
            Utils
                .createCheckedSetting(
                    ctx,
                    CheckedSetting.ViewType.SWITCH,
                    "Random theme on startup",
                    "Each time Discord starts, enable one random installed theme instead of your selection",
                ).apply {
                    isChecked = ThemeStore.randomizeOnStart
                    setOnCheckedListener { ThemeStore.randomizeOnStart = it }
                },
        )

        divider()
        section("Theme files")
        add(
            Utils
                .createCheckedSetting(
                    ctx,
                    CheckedSetting.ViewType.SWITCH,
                    "Custom fonts",
                    "Use fonts from themes",
                ).apply {
                    isChecked = ThemeStore.customFonts
                    setOnCheckedListener {
                        ThemeStore.customFonts = it
                        plugin.reload()
                    }
                },
        )
        add(
            Utils
                .createCheckedSetting(
                    ctx,
                    CheckedSetting.ViewType.SWITCH,
                    "Custom sounds",
                    "Use sounds from themes",
                ).apply {
                    isChecked = ThemeStore.customSounds
                    setOnCheckedListener {
                        ThemeStore.customSounds = it
                        plugin.reload()
                    }
                },
        )
        add(
            Utils
                .createCheckedSetting(
                    ctx,
                    CheckedSetting.ViewType.SWITCH,
                    "Custom icons",
                    "Use icons from themes",
                ).apply {
                    isChecked = ThemeStore.customIcons
                    setOnCheckedListener {
                        ThemeStore.customIcons = it
                        plugin.reload()
                    }
                },
        )
        action("Clear downloaded files", "Download theme backgrounds, fonts, sounds and icons again") {
            ThemeStore.clearCache()
            plugin.reload { if (view != null) reRender() }
        }
    }

    private fun status() {
        val state = plugin.currentState
        val lines = ArrayList<String>()
        ThemeStore.brokenFiles.forEach { (file, reason) -> lines += "Can't read $file: $reason" }
        state.pendingDownloads.forEach { lines += "Downloading $it" }
        lines += state.warnings
        if (ColorEngine.skippedHooks.isNotEmpty()) {
            lines += "Unsupported on this device: ${ColorEngine.skippedHooks.joinToString()}"
        }
        if (ColorEngine.fallbackHooks.isNotEmpty()) {
            lines += "Using fallbacks for: ${ColorEngine.fallbackHooks.joinToString()}"
        }
        section("Status")
        if (lines.isEmpty()) {
            val enabled = ThemeStore.enabledIds().isNotEmpty()
            note(if (enabled) "All enabled themes loaded without problems." else "No theme enabled.")
        } else {
            val shown = lines.take(MAX_STATUS_LINES)
            val more = lines.size - shown.size
            note(shown.joinToString("\n") + if (more > 0) "\n…and $more more" else "")
        }
    }

    private companion object {
        const val MAX_STATUS_LINES = 25
    }
}
