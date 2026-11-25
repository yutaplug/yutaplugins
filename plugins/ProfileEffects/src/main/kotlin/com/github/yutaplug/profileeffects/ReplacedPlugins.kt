package com.github.yutaplug.profileeffects

import com.aliucord.Constants
import com.aliucord.Logger
import com.aliucord.PluginManager
import java.io.File
import java.util.Locale

/** Finds and removes other plugins that do what ProfileEffects now does. */
internal object ReplacedPlugins {
    /** Returns the replaced plugin names (from [names]) that are installed, as plugin-manager names. */
    fun installed(names: Collection<String>): Set<String> {
        val wanted = names.associateBy { it.lowercase(Locale.ROOT) }
        val found = LinkedHashSet<String>()
        for (name in PluginManager.plugins.keys.toList()) {
            wanted[name.lowercase(Locale.ROOT)]?.let { found.add(it) }
        }
        // Also catch zips that failed to load and so are not in the plugin map.
        File(Constants.PLUGINS_PATH).listFiles()?.forEach { file ->
            if (!file.name.endsWith(".zip", ignoreCase = true)) return@forEach
            wanted[file.name.dropLast(4).lowercase(Locale.ROOT)]?.let { found.add(it) }
        }
        return found
    }

    /** Stops, unloads and deletes each replaced plugin. Returns the names that were removed. */
    fun uninstall(names: Collection<String>, logger: Logger): List<String> {
        val removed = ArrayList<String>()
        val loadedNames = PluginManager.plugins.keys.toList()
        for (name in names) {
            val loadedName = loadedNames.firstOrNull { it.equals(name, ignoreCase = true) }
            val plugin = loadedName?.let { PluginManager.plugins[it] }
            try {
                if (loadedName != null && plugin != null) {
                    if (PluginManager.isPluginEnabled(loadedName)) PluginManager.stopPlugin(loadedName)
                    PluginManager.unloadPlugin(loadedName)
                }
                val files = LinkedHashSet<File>()
                plugin?.__filename?.let { files.add(File(Constants.PLUGINS_PATH, "$it.zip")) }
                File(Constants.PLUGINS_PATH).listFiles()?.forEach { file ->
                    if (file.name.equals("$name.zip", ignoreCase = true)) files.add(file)
                }
                var deleted = false
                for (file in files) {
                    if (file.exists() && file.delete()) deleted = true
                }
                if (deleted) removed.add(name) else logger.warn("Could not delete the $name plugin file")
            } catch (error: Throwable) {
                logger.error("Could not uninstall $name", error)
            }
        }
        return removed
    }
}
