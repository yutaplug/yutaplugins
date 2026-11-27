package com.github.yutaplug.bettermessagelogger

import com.aliucord.Constants
import com.aliucord.Logger
import com.aliucord.PluginManager
import com.aliucord.Utils
import java.io.File

/**
 * Removes plugins BetterMessageLogger replaces. HideMessagesLocally hides a message by "deleting" it locally, which
 * the logger can mistake for a real deletion; "Hide Message" in the message menu does the same job without that.
 */
internal object ReplacedPlugins {
    private val NAMES = listOf("HideMessagesLocally")

    /** Run after every plugin has started: unloading during Aliucord's start loop changes the map it iterates. */
    fun removeLater(logger: Logger) {
        Utils.mainThread.post {
            val removed = NAMES.filter { remove(it, logger) }
            if (removed.isNotEmpty()) {
                Utils.showToast(
                    "BetterMessageLogger uninstalled ${removed.joinToString()}. Use Hide Message in the message menu instead.",
                    true,
                )
            }
        }
    }

    /** Stops, unloads and deletes one plugin. True when its file was deleted. */
    private fun remove(name: String, logger: Logger): Boolean {
        return try {
            val loaded = PluginManager.plugins.keys.toList().firstOrNull { it.equals(name, ignoreCase = true) }
            val plugin = loaded?.let { PluginManager.plugins[it] }
            if (loaded != null) {
                if (PluginManager.isPluginEnabled(loaded)) PluginManager.stopPlugin(loaded)
                PluginManager.unloadPlugin(loaded)
            }
            // Its zip, also when it failed to load and so isn't in the plugin map.
            val files = HashSet<File>()
            plugin?.__filename?.let { files.add(File(Constants.PLUGINS_PATH, "$it.zip")) }
            File(Constants.PLUGINS_PATH).listFiles()?.forEach {
                if (it.name.equals("$name.zip", ignoreCase = true)) files.add(it)
            }
            var deleted = false
            for (file in files) if (file.exists() && file.delete()) deleted = true
            deleted
        } catch (error: Throwable) {
            logger.error("Could not uninstall $name", error)
            false
        }
    }
}
