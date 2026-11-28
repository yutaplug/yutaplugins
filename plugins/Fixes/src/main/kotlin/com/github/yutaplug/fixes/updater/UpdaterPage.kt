package com.github.yutaplug.fixes.updater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.ChangelogUtils
import com.discord.views.CheckedSetting
import com.github.yutaplug.fixes.updater.UpdaterUi.Kind
import com.lytefast.flexinput.R

/** Replaces Aliucord's Updater screen. */
class UpdaterPage : SettingsPage() {
    private var showUpToDate = false
    // Aliucord shows this page through a proxy fragment, so this one is never "added" and
    // isAdded/view stay false/null; track whether its views are bound instead.
    private var bound = false
    private val listener = Runnable { if (bound) render() }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Updater")
        setActionBarSubtitle("Aliucord and plugins, from GitHub")
        val context = requireContext()
        linearLayout.setPadding(0, UpdaterUi.dp(context, 8), 0, UpdaterUi.dp(context, 24))
        linearLayout.setBackgroundColor(UpdaterUi.color(context, "colorBackgroundPrimary", Color.DKGRAY))

        UpdaterUi.drawable(context, "ic_refresh_white_a60_24dp", null)?.let { icon ->
            addHeaderButton("Check for updates", icon) {
                Updater.check()
                true
            }
        }

        bound = true
        Updater.removeListener(listener)
        Updater.addListener(listener)
        render()
        // Check when opened, unless a check just ran (e.g. at startup).
        if (!Updater.checking && System.currentTimeMillis() - (Updater.result?.time ?: 0L) > 60_000L) Updater.check()
    }

    override fun onDestroyView() {
        bound = false
        Updater.removeListener(listener)
        super.onDestroyView()
    }

    private fun render() {
        val context = context ?: return
        linearLayout.removeAllViews()
        val result = Updater.result
        addStatus(context, result)
        addCore(context, result)
        addPluginUpdates(context, result)
        addProblems(context, result)
        addUpToDate(context, result)
        addIgnored(context, result)
        addSettings(context)
        addView(
            UpdaterUi.subtext(
                context,
                "Updates are downloaded straight from GitHub at each repository's latest commit, " +
                    "instead of through jsDelivr's cache, so new versions show up right away. " +
                    "Each download is checked before it replaces the old version.",
            ).apply {
                val pad = UpdaterUi.dp(context, 16)
                setPadding(pad, pad, pad, 0)
            },
        )
    }

    // region Status

    private fun addStatus(context: Context, result: CheckResult?) {
        val (card, content) = UpdaterUi.card(context)
        val pending = Updater.installed.values.toList()
        val installable = installable(result)
        val busy = Updater.installing.isNotEmpty()

        val (iconName, tint) = when {
            Updater.checking || busy -> "ic_refresh_white_a60_24dp" to UpdaterUi.color(context, "colorInteractiveNormal", Color.LTGRAY)
            pending.isNotEmpty() -> "ic_check_circle_24dp" to UpdaterUi.GREEN
            Updater.checkError != null && result == null -> "ic_warning_circle_24dp" to UpdaterUi.RED
            result != null && result.count > 0 -> "ic_file_download_white_24dp" to UpdaterUi.BRAND
            else -> "ic_check_circle_24dp" to UpdaterUi.GREEN
        }
        val title = when {
            busy -> "Installing updates…"
            Updater.checking -> "Checking for updates…"
            pending.isNotEmpty() && installable.isEmpty() -> "Restart to finish updating"
            Updater.checkError != null && result == null -> "Couldn't check for updates"
            result == null -> "Not checked yet"
            installable.isNotEmpty() -> Utils.pluralise(installable.size, "update") + " available"
            result.count > 0 -> Utils.pluralise(result.count, "update") + " found"
            else -> "Everything is up to date"
        }
        val subtitle = when {
            Updater.checkError != null && !Updater.checking -> Updater.checkError!!
            pending.isNotEmpty() -> "Installed: " + Updater.names(pending.map { "${it.name} ${it.to}" })
            Updater.lastCheck > 0 -> "Last checked " + DateUtils.getRelativeTimeSpanString(
                Updater.lastCheck,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString().replaceFirstChar { it.lowercaseChar() }
            else -> "Tap Check now to look for updates"
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor((tint and 0x00FFFFFF) or 0x2E000000)
            }
            addView(UpdaterUi.icon(context, UpdaterUi.drawable(context, iconName, tint), 26))
        }
        row.addView(badge, LinearLayout.LayoutParams(UpdaterUi.dp(context, 48), UpdaterUi.dp(context, 48)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(UpdaterUi.title(context, title))
            addView(
                UpdaterUi.subtext(context, subtitle).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = UpdaterUi.dp(context, 14) })
        content.addView(row)

        val buttons = ArrayList<View>()
        if (pending.isNotEmpty()) {
            buttons.add(UpdaterUi.button(context, "Restart now", Kind.GREEN) { Utils.restartAliucord(it.context) })
        }
        if (installable.isNotEmpty()) {
            buttons.add(
                UpdaterUi.button(context, if (installable.size == 1) "Update" else "Update all", Kind.PRIMARY) {
                    installAll(result!!)
                }.apply { isEnabled = !busy && !Updater.checking },
            )
        }
        if (buttons.size < 2) {
            buttons.add(
                UpdaterUi.button(context, "Check now", if (buttons.isEmpty()) Kind.PRIMARY else Kind.SECONDARY) {
                    Updater.check()
                }.apply { isEnabled = !Updater.checking && !busy },
            )
        }
        content.addView(UpdaterUi.space(context, 14))
        content.addView(UpdaterUi.buttonRow(context, buttons))
        addView(card.also { it.layoutParams = UpdaterUi.cardParams(context) })
    }

    private fun installable(result: CheckResult?): List<Any> {
        if (result == null) return emptyList()
        val list = ArrayList<Any>()
        result.core?.takeIf { it.reinstallReason == null && Installed.CORE_NAME !in Updater.installed }?.let { list.add(it) }
        result.plugins.filterTo(list) { it.canInstall && it.name !in Updater.installed }
        return list
    }

    private fun installAll(result: CheckResult) {
        val plugins = result.plugins.filter { it.canInstall && it.name !in Updater.installed }
        val core = result.core?.takeIf { it.reinstallReason == null && Installed.CORE_NAME !in Updater.installed }
        install(plugins, core)
    }

    private fun install(plugins: List<PluginUpdate>, core: CoreUpdate?) {
        Updater.install(plugins, core) { done ->
            val failed = (plugins.map { it.name } + listOfNotNull(core?.let { Installed.CORE_NAME }))
                .filter { name -> done.none { it.name == name } }
            if (failed.isNotEmpty()) {
                Utils.showToast("Couldn't update ${Updater.names(failed)}", true)
            }
            if (done.isNotEmpty()) Updater.promptRestart(context ?: Utils.appContext, done)
        }
    }

    // endregion

    // region Aliucord

    private fun addCore(context: Context, result: CheckResult?) {
        addView(UpdaterUi.header(context, "Aliucord"))
        val (card, content) = UpdaterUi.card(context)
        val core = result?.core
        val installed = Updater.installed[Installed.CORE_NAME]
        val installing = Installed.CORE_NAME in Updater.installing

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UpdaterUi.title(context, "Aliucord"), LinearLayout.LayoutParams(0, -2, 1f))
        val pill = when {
            installed != null -> UpdaterUi.pill(context, "${installed.from} → ${installed.to}", UpdaterUi.GREEN)
            core != null -> UpdaterUi.pill(context, "${core.currentVersion} → ${core.latestVersion}", UpdaterUi.BRAND)
            else -> UpdaterUi.pill(context, Versions.core, UpdaterUi.color(context, "colorInteractiveNormal", Color.LTGRAY))
        }
        row.addView(pill)
        content.addView(row)

        val status = when {
            installed != null -> "Installed. Restart to start using it."
            installing -> "Downloading…"
            Updater.failures[Installed.CORE_NAME] != null -> "Update failed: ${Updater.failures[Installed.CORE_NAME]}"
            core?.reinstallReason != null -> core.reinstallReason
            core != null -> "A new version of Aliucord is available."
            result?.coreError != null -> result.coreError
            result?.latestCore != null -> "You're on the latest version."
            else -> "The Aliucord core and its built-in plugins."
        }
        content.addView(
            UpdaterUi.subtext(context, status).apply {
                if (core?.reinstallReason != null || Updater.failures[Installed.CORE_NAME] != null) {
                    setTextColor(UpdaterUi.RED)
                }
                setPadding(0, UpdaterUi.dp(context, 4), 0, 0)
            },
        )

        val buttons = ArrayList<View>()
        val latest = core?.latestVersion ?: result?.latestCore ?: Versions.core
        buttons.add(
            UpdaterUi.button(context, "What's new", Kind.SECONDARY) {
                val from = if (core != null) core.currentVersion else null
                Updater.showChangelog(it.context, listOf(Installed(true, Installed.CORE_NAME, from ?: "", latest, null, null, null)))
            },
        )
        when {
            installed != null -> {}
            core?.reinstallReason != null -> buttons.add(
                UpdaterUi.button(context, "Open Manager", Kind.PRIMARY) { openManager(it.context) },
            )
            core != null -> buttons.add(
                UpdaterUi.button(context, if (installing) "Updating…" else "Update", Kind.PRIMARY) {
                    install(emptyList(), core)
                }.apply { isEnabled = !installing },
            )
        }
        content.addView(UpdaterUi.space(context, 12))
        content.addView(UpdaterUi.buttonRow(context, buttons))
        addView(card.also { it.layoutParams = UpdaterUi.cardParams(context) })
    }

    private fun openManager(context: Context) {
        val intent = Intent("com.aliucord.manager.REINSTALL")
            .setClassName("com.aliucord.manager", "com.aliucord.manager.MainActivity")
            .putExtra("aliucord.packageName", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val dialog = ConfirmDialog()
            dialog.setTitle("Aliucord Manager isn't installed")
                .setDescription("Download the latest Aliucord Manager from GitHub to reinstall Aliucord?")
                .setOnOkListener {
                    Utils.launchUrl("https://github.com/Aliucord/Manager/releases/latest")
                    dialog.dismiss()
                }.let { d -> Updater.fragmentManager(context)?.let { d.show(it, "UpdaterManager") } }
        }
    }

    // endregion

    // region Plugins

    private fun addPluginUpdates(context: Context, result: CheckResult?) {
        val updates = result?.plugins ?: return
        if (updates.isEmpty()) return
        addView(UpdaterUi.header(context, "Plugin updates · ${updates.size}"))
        for (update in updates) addView(pluginCard(context, update))
    }

    private fun pluginCard(context: Context, update: PluginUpdate): View {
        val (card, content) = UpdaterUi.card(context)
        val installed = Updater.installed[update.name]
        val installing = update.name in Updater.installing
        val failure = Updater.failures[update.name]

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(UpdaterUi.title(context, update.name), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            UpdaterUi.pill(
                context,
                "${update.currentVersion} → ${update.build.version}" +
                    (update.releaseDate?.let { " · " + shortDate(it) } ?: ""),
                if (installed != null) UpdaterUi.GREEN else UpdaterUi.BRAND,
            ),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = UpdaterUi.dp(context, 8) },
        )
        content.addView(row)
        update.repo?.let { content.addView(UpdaterUi.subtext(context, it)) }

        val preview = Changelogs.preview(update.newChangelog, 3)
        if (preview.isNotEmpty()) {
            content.addView(UpdaterUi.space(context, 8))
            for (line in preview) {
                content.addView(
                    UpdaterUi.body(context, "•  " + UpdaterUi.plain(line)).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, UpdaterUi.dp(context, 2), 0, 0)
                    },
                )
            }
        }

        val notice = when {
            installed != null -> "Installed. Restart to start using it." to UpdaterUi.GREEN
            failure != null -> "Update failed: $failure" to UpdaterUi.RED
            update.blockedReason != null -> update.blockedReason to UpdaterUi.YELLOW
            else -> null
        }
        if (notice != null) {
            content.addView(
                UpdaterUi.subtext(context, notice.first).apply {
                    setTextColor(notice.second)
                    setPadding(0, UpdaterUi.dp(context, 8), 0, 0)
                },
            )
        }

        val buttons = ArrayList<View>()
        buttons.add(
            UpdaterUi.button(context, "Changelog", Kind.SECONDARY) {
                val body = update.newChangelog ?: update.build.changelog
                showChangelog(it.context, update.name, "${update.name} ${update.build.version}", update.build.changelogMedia, body, update.repo)
            },
        )
        if (installed == null && !installing) {
            buttons.add(UpdaterUi.button(context, "Ignore", Kind.SECONDARY) { confirmIgnore(it.context, update.name) })
        }
        if (installed == null) {
            buttons.add(
                UpdaterUi.button(
                    context,
                    when {
                        installing -> "Updating…"
                        failure != null -> "Try again"
                        else -> "Update"
                    },
                    Kind.PRIMARY,
                ) { install(listOf(update), null) }.apply { isEnabled = update.canInstall && !installing },
            )
        }
        content.addView(UpdaterUi.space(context, 12))
        content.addView(UpdaterUi.buttonRow(context, buttons))
        card.layoutParams = UpdaterUi.cardParams(context)
        return card
    }

    private fun addIgnored(context: Context, result: CheckResult?) {
        val ignored = result?.ignored?.takeIf { it.isNotEmpty() }
            ?: Updater.ignored.map { PluginInfo(it, PluginManager.plugins[it]?.manifest?.version ?: "", null) }
        addView(UpdaterUi.header(context, "Ignored · ${ignored.size}"))
        val (card, content) = UpdaterUi.card(context)
        content.addView(
            UpdaterUi.subtext(
                context,
                if (ignored.isEmpty()) {
                    "Plugins you add here are never checked for updates."
                } else {
                    "These plugins are never checked for updates. Tap one to check it again."
                },
            ).apply { setPadding(0, 0, 0, UpdaterUi.dp(context, 6)) },
        )
        for (plugin in ignored) {
            content.addView(
                tappableRow(context, plugin.name, plugin.version, null) { view ->
                    val dialog = ConfirmDialog()
                    dialog.setTitle("Check ${plugin.name} again?")
                        .setDescription("The updater will look for ${plugin.name} updates again.")
                        .setOnOkListener {
                            Updater.setIgnored(plugin.name, false)
                            dialog.dismiss()
                        }
                    Updater.fragmentManager(view.context)?.let { dialog.show(it, "UpdaterUnignore") }
                },
            )
        }
        content.addView(UpdaterUi.space(context, 8))
        content.addView(
            UpdaterUi.buttonRow(
                context,
                listOf(UpdaterUi.button(context, "Ignore a plugin", Kind.SECONDARY) { pickPluginToIgnore(it.context) }),
            ),
        )
        addView(card.also { it.layoutParams = UpdaterUi.cardParams(context) })
    }

    private fun pickPluginToIgnore(context: Context) {
        val ignored = Updater.ignored.toSet()
        val names = PluginManager.plugins.values
            .filter { it.__filename != null && it.name !in ignored && !UpdateChecker.isCorePlugin(it) }
            .map { it.name }
            .sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            Utils.showToast("Every plugin is already ignored")
            return
        }
        val dialog = SelectDialog().apply {
            title = "Ignore a plugin"
            items = names.toTypedArray()
            onResultListener = { which -> Updater.setIgnored(names[which], true) }
        }
        Updater.fragmentManager(context)?.let { dialog.show(it, "UpdaterIgnore") }
    }

    private fun confirmIgnore(context: Context, name: String) {
        val dialog = ConfirmDialog()
        dialog.setTitle("Ignore $name?")
            .setDescription(
                "The updater won't check $name for updates anymore, or tell you about them. " +
                    "You can undo this under Ignored.",
            )
            .setOnOkListener {
                Updater.setIgnored(name, true)
                dialog.dismiss()
            }
        Updater.fragmentManager(context)?.let { dialog.show(it, "UpdaterIgnore") }
    }

    /** A tappable row: [name] with [trailing] on the right, and [detail] below. */
    private fun tappableRow(context: Context, name: String, trailing: String?, detail: String?, onClick: (View) -> Unit): View {
        val pad = UpdaterUi.dp(context, 5)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
            background = selectableBackground(context)
            isClickable = true
            setOnClickListener(onClick)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            UpdaterUi.body(context, name).apply {
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (trailing != null && trailing.isNotEmpty()) top.addView(UpdaterUi.subtext(context, trailing))
        row.addView(top)
        if (detail != null) row.addView(UpdaterUi.subtext(context, detail))
        return row
    }

    /** "Nov 26", or "Nov 26, 2025" when it's not this year, in the phone's language. */
    private fun shortDate(time: Long): String {
        val sameYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
            java.util.Calendar.getInstance().apply { timeInMillis = time }.get(java.util.Calendar.YEAR)
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
            (if (sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
        return DateUtils.formatDateTime(context ?: Utils.appContext, time, flags)
    }

    /** The theme's ripple for tappable rows. */
    private fun selectableBackground(context: Context): android.graphics.drawable.Drawable? {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return null
        return androidx.core.content.ContextCompat.getDrawable(context, value.resourceId)
    }

    private fun showChangelog(context: Context, name: String, title: String, media: String?, changelog: String?, repo: String?) {
        val github = Updater.repoUrl(repo)
        val footer = if (github == null) {
            emptyArray()
        } else {
            arrayOf(ChangelogUtils.FooterAction(R.e.ic_account_github_white_24dp, github))
        }
        // Looking up the versions' release dates can take a moment the first time.
        Utils.threadPool.execute {
            val body = ReleaseDates.dated(name, changelog)?.takeIf { it.trim().isNotEmpty() }
                ?: "* No changelog was published for this version."
            Utils.mainThread.post {
                try {
                    ChangelogUtils.show(context, title, media, body, *footer)
                } catch (e: Throwable) {
                    Updater.logger.error("Could not show the changelog", e)
                }
            }
        }
    }

    private fun addProblems(context: Context, result: CheckResult?) {
        val errors = result?.errors ?: return
        if (errors.isEmpty()) return
        addView(UpdaterUi.header(context, "Couldn't check · ${errors.size}"))
        val (card, content) = UpdaterUi.card(context)
        var first = true
        content.addView(
            UpdaterUi.subtext(context, "Tap a plugin to ignore it.").apply {
                setPadding(0, 0, 0, UpdaterUi.dp(context, 6))
            },
        )
        for ((name, reason) in errors) {
            if (!first) content.addView(UpdaterUi.space(context, 4))
            first = false
            content.addView(
                tappableRow(context, name, null, reason) { confirmIgnore(it.context, name) },
            )
        }
        addView(card.also { it.layoutParams = UpdaterUi.cardParams(context) })
    }

    private fun addUpToDate(context: Context, result: CheckResult?) {
        if (result == null) return
        val total = result.upToDate.size + result.untracked.size
        if (total == 0) return
        val header = UpdaterUi.header(context, "Up to date · ${result.upToDate.size}").apply {
            val chevron = UpdaterUi.drawable(
                context,
                if (showUpToDate) "ic_chevron_down_grey_12dp" else "ic_chevron_right_grey_12dp",
                null,
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
            compoundDrawablePadding = UpdaterUi.dp(context, 6)
            isClickable = true
            setOnClickListener {
                showUpToDate = !showUpToDate
                render()
            }
        }
        addView(header)
        if (!showUpToDate) return
        val (card, content) = UpdaterUi.card(context)
        content.addView(
            UpdaterUi.subtext(context, "Tap a plugin to see its changelog.").apply {
                setPadding(0, 0, 0, UpdaterUi.dp(context, 6))
            },
        )
        val pad = UpdaterUi.dp(context, 5)
        for (plugin in result.upToDate + result.untracked) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
                background = selectableBackground(context)
                isClickable = true
                setOnClickListener {
                    val title = "${plugin.name} ${plugin.version}"
                    showChangelog(it.context, plugin.name, title, plugin.changelogMedia, plugin.changelog, plugin.repo)
                }
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                UpdaterUi.body(context, plugin.name).apply {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            top.addView(UpdaterUi.subtext(context, plugin.version))
            row.addView(top)
            val details = listOfNotNull(
                plugin.pendingVersion?.let { "$it installed, restart to start using it" },
                if (plugin.repo == null) "No updates" else null,
            )
            if (details.isNotEmpty()) {
                row.addView(
                    UpdaterUi.subtext(context, details.joinToString(" · ")).apply {
                        if (plugin.pendingVersion != null) setTextColor(UpdaterUi.GREEN)
                    },
                )
            }
            content.addView(row)
        }
        addView(card.also { it.layoutParams = UpdaterUi.cardParams(context) })
    }

    // endregion

    private fun addSettings(context: Context) {
        addView(UpdaterUi.header(context, "Settings"))
        switch(
            context,
            "Check for updates on startup",
            "Shows a message with an Update button when updates are available.",
            Updater.setting(Updater.CHECK_ON_STARTUP, true),
        ) { Updater.settings?.setBool(Updater.CHECK_ON_STARTUP, it) }
        switch(
            context,
            "Show update popups",
            "Shows a popup at the top of the app when updates are found on startup, and after updating. " +
                "Swipe it away to dismiss it.",
            Updater.setting(Updater.SHOW_POPUPS, true),
        ) { Updater.settings?.setBool(Updater.SHOW_POPUPS, it) }
        switch(
            context,
            "Update from the popup",
            "Tapping the popup installs the updates right away. Turn this off to open the Updater instead, " +
                "so you can read the changelogs and choose which ones to install.",
            Updater.setting(Updater.POPUP_INSTALLS, true),
        ) { Updater.settings?.setBool(Updater.POPUP_INSTALLS, it) }
        switch(
            context,
            "Show phone notifications",
            "Also posts an Android notification for them, which stays in your notifications until you swipe it away. " +
                "Tap it to open the Updater, or use its Update button.",
            Updater.setting(Updater.SHOW_NOTIFICATIONS, false),
        ) { Updater.settings?.setBool(Updater.SHOW_NOTIFICATIONS, it) }
        switch(
            context,
            "Update plugins automatically",
            "Installs plugin updates found on startup, then asks to restart.",
            Updater.autoPlugins,
        ) { Updater.settings?.setBool(Updater.AUTO_PLUGINS, it) }
        switch(
            context,
            "Update Aliucord automatically",
            "Installs Aliucord updates found on startup, then asks to restart.",
            Updater.autoCore,
        ) { Updater.settings?.setBool(Updater.AUTO_CORE, it) }
    }

    private fun switch(context: Context, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        addView(
            Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = checked
                setOnCheckedListener { onChange(it) }
            },
        )
    }
}
