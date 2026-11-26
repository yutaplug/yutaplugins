package com.github.yutaplug.fixes.updater

import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** An update that was installed and takes effect after a restart. */
internal class Installed(
    val isCore: Boolean,
    val name: String,
    val from: String,
    val to: String,
    /** The changelog entries since [from], or null for Aliucord, whose changelog is fetched when shown. */
    val changelog: String?,
    val media: String?,
    val repo: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("core", isCore)
        .put("name", name)
        .put("from", from)
        .put("to", to)
        .put("changelog", changelog ?: JSONObject.NULL)
        .put("media", media ?: JSONObject.NULL)
        .put("repo", repo ?: JSONObject.NULL)

    companion object {
        const val CORE_NAME = "Aliucord"

        fun fromJson(json: JSONObject) = Installed(
            json.optBoolean("core", false),
            json.optString("name", ""),
            json.optString("from", ""),
            json.optString("to", ""),
            json.opt("changelog") as? String,
            json.opt("media") as? String,
            json.opt("repo") as? String,
        )
    }
}

/** Updates installed but not yet loaded, remembered so the next start can say what was updated. */
internal object PendingUpdates {
    private const val KEY = "updater_pending"

    fun load(settings: SettingsAPI): List<Installed> = try {
        val array = JSONArray(settings.getString(KEY, "[]"))
        // A plain loop: mapping an IntRange breaks on Discord's obfuscated stdlib.
        val list = ArrayList<Installed>(array.length())
        for (i in 0 until array.length()) list.add(Installed.fromJson(array.getJSONObject(i)))
        list
    } catch (_: Throwable) {
        emptyList()
    }

    fun add(settings: SettingsAPI, installed: Installed) {
        val list = load(settings).filter { it.name != installed.name || it.isCore != installed.isCore }.toMutableList()
        // Keep the version the user actually had, if this replaces an update that wasn't loaded yet.
        val earlier = load(settings).firstOrNull { it.name == installed.name && it.isCore == installed.isCore }
        list.add(
            if (earlier == null) {
                installed
            } else {
                Installed(
                    installed.isCore, installed.name, earlier.from, installed.to,
                    installed.changelog?.let { Changelogs.since(it, earlier.from) } ?: earlier.changelog,
                    installed.media, installed.repo,
                )
            },
        )
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        settings.setString(KEY, array.toString())
    }

    fun clear(settings: SettingsAPI) = settings.setString(KEY, "[]")
}

/** Downloads and installs updates. A file is only replaced once its download is complete and verified. */
internal object UpdateInstaller {
    fun installPlugin(update: PluginUpdate, files: GitHubFiles): Installed {
        val dir = File(Constants.PLUGINS_PATH)
        val target = File(dir, "${update.fileName}.zip")
        // Aliucord only loads *.zip, so a half-finished download is never loaded.
        val part = File(dir, "${update.fileName}.zip.part")
        try {
            files.download(UpdaterUi.replaceLiteral(update.build.build, "%s", update.name), part)
            update.build.crc32?.let { expected ->
                if (crc32(part) != expected) {
                    throw IOException("The download didn't match its checksum. Try again in a few minutes.")
                }
            }
            val version = ZipFile(part).use { zip ->
                if (zip.getEntry("classes.dex") == null) throw IOException("The download isn't a plugin.")
                val entry = zip.getEntry("manifest.json") ?: throw IOException("The download has no manifest.")
                val manifest = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                manifest.optString("version", "")
            }
            if (Versions.newer(update.build.version, version)) {
                throw IOException("GitHub served an older build ($version). Try again in a few minutes.")
            }
            replace(part, target)
        } finally {
            part.delete()
        }
        return Installed(
            false, update.name, update.currentVersion, update.build.version,
            update.newChangelog, update.build.changelogMedia, update.repo,
        )
    }

    fun installCore(update: CoreUpdate, files: GitHubFiles): Installed {
        // Where Aliucord's injector loads the core from.
        val dir = Utils.appContext.codeCacheDir
        val target = File(dir, "Aliucord.zip")
        val part = File(dir, "Aliucord.zip.part")
        try {
            files.download(CoreBuilds.ZIP_URL, part)
            ZipFile(part).use { zip ->
                if (zip.getEntry("classes.dex") == null) throw IOException("The download isn't an Aliucord build.")
            }
            replace(part, target)
        } finally {
            part.delete()
        }
        return Installed(true, Installed.CORE_NAME, update.currentVersion, update.latestVersion, null, null, "Aliucord/Aliucord")
    }

    /**
     * Moves [from] over [to] with a rename, which also keeps the running app safe: the old file
     * stays readable for the classes and resources already loaded from it until the restart.
     * Aliucord's own updater writes into the loaded file instead.
     */
    private fun replace(from: File, to: File) {
        if (from.renameTo(to)) return
        to.delete()
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
