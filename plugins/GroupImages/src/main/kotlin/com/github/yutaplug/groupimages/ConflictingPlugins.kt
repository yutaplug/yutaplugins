package com.github.yutaplug.groupimages

import com.aliucord.Constants
import com.aliucord.Logger
import com.aliucord.PluginManager
import java.io.File
import java.util.Locale

/** Removes other plugins that group media or add swiping to the viewer, since they'd fight with GroupImages. */
internal object ConflictingPlugins {
    val NAMES = listOf("MosaicFork", "SwipeMediaViewer")

    /** Returns which of [NAMES] are installed, including zips that failed to load. */
    fun installed(): List<String> {
        val loaded = PluginManager.plugins.keys.map { it.lowercase(Locale.ROOT) }.toSet()
        val files = File(Constants.PLUGINS_PATH).listFiles()
            ?.filter { it.name.endsWith(".zip", ignoreCase = true) }
            ?.map { it.name.dropLast(4).lowercase(Locale.ROOT) }
            ?.toSet()
            ?: emptySet()
        return NAMES.filter { it.lowercase(Locale.ROOT) in loaded || it.lowercase(Locale.ROOT) in files }
    }

    /** Stops, unloads and deletes each plugin. Returns the names that were removed. */
    fun uninstall(names: List<String>, logger: Logger): List<String> {
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
