package com.github.yutaplug.themes

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.FragmentProxy
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.RxUtils.subscribe
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.github.yutaplug.themes.settings.ReopenablePage
import com.github.yutaplug.themes.settings.ThemesSettings
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import rx.Subscription
import java.util.WeakHashMap

@AliucordPlugin
class Themes : Plugin() {
    private var subscription: Subscription? = null
    private var colorsInstalled = false
    private var iconsInstalled = false
    private var currentTheme: String? = null

    /** Bumped by every reload so that only the newest background build is applied. */
    @Volatile
    private var generation = 0

    init {
        settingsTab = SettingsTab(ThemesSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        ColorEngine.skippedHooks.clear()
        ColorEngine.fallbackHooks.clear()
        ThemeStore.settings = settings
        ThemeStore.loadThemes()
        ThemeStore.importThemer()
        if (isThemerActive()) {
            Utils.showToast("Themes: disable the old Themer plugin, both can't run at once", true)
        }

        InstallButton.install(patcher) { theme ->
            ThemeStore.loadThemes()
            handleDeadThemes(listOf(theme)) { dead ->
                if (dead.isEmpty()) Utils.showToast("Installed theme ${theme.name}. Enable it in the Themes settings.")
            }
        }
        Backgrounds.install(patcher)
        FontsAndSounds.install(patcher)
        patchColorPicker()

        val system = StoreStream.getUserSettingsSystem()
        currentTheme = system.theme
        // Discord recreates the activity itself on a theme change; only the mappings must follow.
        subscription = system.observeSettings(false).subscribe {
            val theme = this.theme
            if (theme != currentTheme) {
                currentTheme = theme
                Utils.mainThread.post { reloadNow(recreate = false) }
            }
        }

        // Activities remember which theme build they were inflated with and recreate on resume when
        // it changed. Aliucord's settings pages (proxy activities) are skipped: recreating them
        // loses the open page, and they are re-rendered in place instead.
        patcher.patch(
            AppActivity::class.java.getDeclaredMethod("onCreate", Bundle::class.java),
            PreHook {
                rendered[it.thisObject as AppActivity] = appliedVersion
            },
        )
        patcher.patch(
            AppActivity::class.java.getDeclaredMethod("onResume"),
            PreHook {
                val activity = it.thisObject as AppActivity
                refresh(activity)
                Utils.mainThread.post { showDeadNotice() }
            },
        )

        if (ThemeStore.randomizeOnStart) {
            ThemeStore.pickRandom { !DeadThemes.uses(it) }?.let { Utils.showToast("Random theme: ${it.name}") }
        }
        handleDeadThemes(ThemeStore.enabledThemes())
        val initial = resolve(download = false)
        apply(initial, recreate = false)
        // Plugins usually start before Discord inflates its views. When enabled later, the open
        // screen was drawn without the theme and must be rebuilt. Only peek at the window: building
        // it here (findViewById, getDecorView) would happen under Discord's fullscreen splash style,
        // before onCreate switches themes, and leave the status bar hidden for good.
        currentActivity()?.let { activity ->
            val content = activity.window.peekDecorView()?.findViewById<ViewGroup?>(android.R.id.content)
            rendered[activity] = if (content != null && content.childCount > 0) appliedVersion - 1 else appliedVersion
            refresh(activity)
        }
        Utils.threadPool.execute { checkUpdates() }
        if (initial.pendingDownloads.isNotEmpty()) reload()
    }

    override fun stop(context: Context) {
        generation++
        subscription?.unsubscribe()
        subscription = null
        patcher.unpatchAll()
        colorsInstalled = false
        iconsInstalled = false
        ColorEngine.state = ThemeState.EMPTY
        ColorEngine.clearCaches(context.resources)
        currentActivity()?.let { activity ->
            ColorEngine.clearCaches(activity.resources)
            Utils.mainThread.post { if (!activity.isFinishing) activity.recreate() }
        }
        rendered.clear()
    }

    private val rendered = WeakHashMap<AppActivity, Int>()
    private var appliedVersion = 0

    private fun currentActivity(): AppActivity? = runCatching { Utils.appActivity }.getOrNull()

    /** Recreates [activity] if it was drawn with an older theme build. */
    private fun refresh(activity: AppActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (rendered[activity] == appliedVersion) return
        rendered[activity] = appliedVersion
        Utils.mainThread.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (activity.intent?.hasExtra("AC_FRAGMENT_ID") == true) reopenPage(activity) else activity.recreate()
        }
    }

    /**
     * Aliucord settings pages run inside a proxy activity that takes its page only once; recreating
     * it closes the page. This plugin's own pages are reopened instead, as a fresh copy without
     * transitions, so a change shows right away. Other plugins' pages pick the theme up when reopened.
     */
    private fun reopenPage(activity: AppActivity) {
        val page = runCatching {
            activity.supportFragmentManager.fragments.firstNotNullOfOrNull { (it as? FragmentProxy)?.getmFragment() }
        }.getOrNull() as? ReopenablePage ?: return
        Utils.openPageWithProxy(activity, page.reopen())
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun isLight() = (currentTheme ?: StoreStream.getUserSettingsSystem().theme) == "light"

    private fun resolve(download: Boolean): ThemeState {
        ColorEngine.isLight = isLight()
        return try {
            ThemeResolver.resolve(Utils.appContext, ThemeStore.enabledThemes(), isLight(), download)
        } catch (error: Throwable) {
            logger.error("Failed to resolve themes", error)
            ThemeState.EMPTY
        }
    }

    private fun apply(state: ThemeState, recreate: Boolean) {
        if (state.hasColors && !colorsInstalled) {
            ColorEngine.install(patcher)
            colorsInstalled = true
        }
        if (state.hasIcons && !iconsInstalled) {
            Icons.install(patcher)
            iconsInstalled = true
        }
        ColorEngine.state = state
        appliedVersion++
        ColorEngine.clearCaches(Utils.appContext.resources)
        val activity = currentActivity() ?: return
        ColorEngine.clearCaches(activity.resources)
        if (recreate) refresh(activity) else rendered[activity] = appliedVersion
    }

    /** Rebuilds the theme from disk without touching the network and applies it right away. */
    internal fun reloadNow(recreate: Boolean = true) {
        generation++
        apply(resolve(download = false), recreate)
    }

    /** Rebuilds the theme in the background, downloading missing files, then applies it. */
    internal fun reload(onDone: ((ThemeState) -> Unit)? = null) {
        val token = ++generation
        Utils.threadPool.execute {
            val state = resolve(download = true)
            Utils.mainThread.post {
                if (token != generation) return@post
                apply(state, recreate = true)
                onDone?.invoke(state)
            }
        }
    }

    internal val currentState get() = ColorEngine.state

    private class DeadNotice(
        val themes: List<ThemeFile>,
        val mirrors: Map<ThemeFile, String>,
        val enableAfterReplace: Set<String>,
    )

    /** Shown on the next resumed screen; start-up runs before Discord can show dialogs. */
    private var pendingNotice: DeadNotice? = null

    /**
     * Checks in the background which of [themes] have a background image deleted from Discord, turns
     * those off, then explains why in a pop-up and offers fixed copies from the Aliucord theme mirror.
     * Themes in [enableAfterReplace] (by id) and those that were enabled are turned back on once
     * replaced. [onChecked] gets the themes it turned off, on the main thread.
     */
    internal fun handleDeadThemes(
        themes: List<ThemeFile>,
        enableAfterReplace: Set<String> = emptySet(),
        onChecked: (List<ThemeFile>) -> Unit = {},
    ) {
        val candidates = themes.filter { DeadThemes.hasDiscordBackground(it) }
        if (candidates.isEmpty()) return onChecked(candidates)
        Utils.threadPool.execute {
            val dead = candidates.filter { DeadThemes.isDead(it) }
            val mirrors = LinkedHashMap<ThemeFile, String>()
            dead.forEach { theme ->
                runCatching { DeadThemes.mirrorFor(theme) }
                    .onFailure { logger.warn("Themes: theme mirror unavailable (${it.message})") }
                    .getOrNull()
                    ?.let { mirrors[theme] = it }
            }
            Utils.mainThread.post {
                if (dead.isNotEmpty()) {
                    val wasEnabled = dead.filter { ThemeStore.isEnabled(it) }.map { it.id }.toSet()
                    dead.forEach { ThemeStore.setEnabled(it, false) }
                    logger.warn("Themes: disabled ${dead.joinToString { it.name }}, their background was deleted")
                    if (wasEnabled.isNotEmpty()) reload()
                    pendingNotice = DeadNotice(dead, mirrors, wasEnabled + enableAfterReplace)
                    showDeadNotice()
                }
                onChecked(dead)
            }
        }
    }

    private fun showDeadNotice() {
        val notice = pendingNotice ?: return
        val activity = currentActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val fragments = activity.supportFragmentManager
        if (fragments.isStateSaved) return
        pendingNotice = null

        val many = notice.themes.size > 1
        val names = notice.themes.joinToString { it.name }
        val description = StringBuilder()
            .append(names)
            .append(if (many) " use" else " uses")
            .append(" a background image that was deleted from Discord, so ")
            .append(if (many) "these themes can't load and were" else "it can't load and was")
            .append(" turned off.")
        val mirrors = notice.mirrors
        if (mirrors.isEmpty()) {
            description.append("\n\nNo fixed copy was found in the Aliucord theme mirror (${DeadThemes.MIRROR_URL}).")
        } else {
            description
                .append("\n\nFixed copies of ")
                .append(mirrors.keys.joinToString { it.name })
                .append(" are available from the Aliucord theme mirror. Download them to replace the broken versions?")
        }
        val dialog = ConfirmDialog()
            .setTitle(if (many) "${notice.themes.size} themes turned off" else "Theme turned off")
            .setDescription(description)
        dialog.setOnOkListener {
            dialog.dismiss()
            if (mirrors.isNotEmpty()) replaceWithMirror(notice)
        }
        dialog.show(fragments, "ThemesDeadLinks")
    }

    private fun replaceWithMirror(notice: DeadNotice) {
        Utils.showToast("Downloading fixed themes…")
        Utils.threadPool.execute {
            val replaced = ArrayList<ThemeFile>()
            notice.mirrors.forEach { (theme, url) ->
                runCatching { DeadThemes.replaceWithMirror(theme, url) }
                    .onSuccess { replaced += theme }
                    .onFailure { logger.error("Failed to download the mirror copy of ${theme.name}", it) }
            }
            Utils.mainThread.post {
                ThemeStore.loadThemes()
                replaced.filter { it.id in notice.enableAfterReplace }.forEach { ThemeStore.setEnabled(it, true) }
                val failed = notice.mirrors.size - replaced.size
                Utils.showToast(
                    if (failed == 0) {
                        "Replaced ${replaced.joinToString { it.name }} with the mirror version"
                    } else {
                        "Couldn't download $failed theme(s) from the mirror"
                    },
                )
                if (replaced.isNotEmpty()) reload()
            }
        }
    }

    /**
     * Discord never shows the picker's transparency slider, so its labels are unstyled and unreadable
     * on dark themes. The editor enables the slider; give its labels the picker's own text style.
     */
    private fun patchColorPicker() {
        runCatching {
            patcher.patch(
                ColorPickerDialog::class.java.getDeclaredMethod("j"),
                Hook { param ->
                    val args = (param.thisObject as ColorPickerDialog).arguments ?: return@Hook
                    val view = param.result as? View ?: return@Hook
                    val color = args.getInt("customButtonTextColor")
                    val font = args
                        .getInt("buttonFont")
                        .takeIf {
                            it != 0
                        }?.let { runCatching { ResourcesCompat.getFont(view.context, it) }.getOrNull() }
                    arrayOf("transparency_text", "transparency_title").forEach { name ->
                        view.findViewById<TextView?>(Utils.getResId(name, "id"))?.run {
                            setTextColor(color)
                            font?.let { typeface = it }
                        }
                    }
                },
            )
        }.onFailure { logger.warn("Themes: color picker label fix unavailable (${it.message})") }
    }

    /** Checks the updater URL of every enabled theme; reloads when one changed. */
    internal fun checkUpdates(onDone: ((List<String>) -> Unit)? = null) {
        val updated = ArrayList<String>()
        ThemeStore.enabledThemes().forEach { theme ->
            try {
                if (theme.update()) updated += theme.name
            } catch (error: Throwable) {
                logger.warn("Themes: failed to update ${theme.name} (${error.message})")
            }
        }
        Utils.mainThread.post {
            if (updated.isNotEmpty()) {
                ThemeStore.loadThemes()
                Utils.showToast("Updated theme${if (updated.size > 1) "s" else ""}: ${updated.joinToString()}")
                reload()
            }
            onDone?.invoke(updated)
        }
    }
}
