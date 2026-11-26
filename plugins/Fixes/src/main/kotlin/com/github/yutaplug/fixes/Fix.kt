package com.github.yutaplug.fixes

import android.content.Context
import android.content.res.Resources
import com.aliucord.Logger
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI

/**
 * One independently toggleable fix. [id] is also the name of the standalone plugin it replaces,
 * which [LegacyPlugins] uninstalls, and the key of its on/off setting.
 */
internal abstract class Fix(
    val id: String,
    val title: String,
    val description: String,
    val author: String? = null,
    /** Hooks that only take effect for newly created activities or after Discord caches them. */
    val requiresRestart: Boolean = false,
    /** Whether the fix is on until the user toggles it. Only an explicit toggle is saved. */
    val enabledByDefault: Boolean = true,
    /** Shown in the settings, and confirmed before turning the fix on, e.g. while it's still being tested. */
    val warning: String? = null,
) {
    lateinit var plugin: Fixes
        private set
    lateinit var patcher: FixPatcher
        private set
    lateinit var logger: Logger
        private set

    val settings: SettingsAPI get() = plugin.settings
    val resources: Resources? get() = plugin.resources

    internal var started = false

    internal fun attach(plugin: Fixes, api: PatcherAPI) {
        if (::patcher.isInitialized) return
        this.plugin = plugin
        patcher = FixPatcher(api)
        logger = Logger("Fixes/$id")
    }

    abstract fun start(context: Context)

    abstract fun stop(context: Context)

    /** Called after the user turns this fix on or off in the settings, once it has started or stopped. */
    open fun onToggled() {}
}
