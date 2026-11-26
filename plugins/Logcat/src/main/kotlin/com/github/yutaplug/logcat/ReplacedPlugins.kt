package com.github.yutaplug.logcat

import com.aliucord.Constants
import com.aliucord.Logger
import com.aliucord.PluginManager
import com.aliucord.Utils
import java.io.File

/** Removes plugins that Logcat now replaces, like Fixes' LegacyPlugins. */
internal object ReplacedPlugins {
    /** DebugLogEnhancer swaps Discord's Debug page for its own, which [DebugLogPage] replaces. */
    private val NAMES = listOf("DebugLogEnhancer")

    fun uninstall(logger: Logger) {
        val removed = ArrayList<String>()
        val loadedNames = PluginManager.plugins.keys.toList()
        val zips = File(Constants.PLUGINS_PATH).listFiles()?.toList() ?: emptyList()
        for (name in NAMES) {
            val loadedName = loadedNames.firstOrNull { it.equals(name, ignoreCase = true) }
            // Also catch a zip that failed to load and so is not in the plugin map.
            if (loadedName == null && zips.none { it.name.equals("$name.zip", ignoreCase = true) }) continue
            try {
                val plugin = loadedName?.let { PluginManager.plugins[it] }
                if (loadedName != null && plugin != null) {
                    if (PluginManager.isPluginEnabled(loadedName)) PluginManager.stopPlugin(loadedName)
                    PluginManager.unloadPlugin(loadedName)
                }
                val files = LinkedHashSet<File>()
                plugin?.__filename?.let { files.add(File(Constants.PLUGINS_PATH, "$it.zip")) }
                for (file in zips) if (file.name.equals("$name.zip", ignoreCase = true)) files.add(file)
                var deleted = false
                for (file in files) if (file.exists() && file.delete()) deleted = true
                if (deleted) removed.add(name) else logger.warn("Could not delete the $name plugin file")
            } catch (e: Throwable) {
                logger.error("Could not uninstall $name", e)
            }
        }
        if (removed.isNotEmpty()) {
            Utils.showToast("Logcat replaced and uninstalled: ${removed.joinToString(", ")}", true)
        }
    }
}
