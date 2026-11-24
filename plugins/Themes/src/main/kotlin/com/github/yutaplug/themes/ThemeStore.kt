package com.github.yutaplug.themes

import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Installed themes, which of them are enabled and in what order, and their downloaded files. */
internal object ThemeStore {
    private const val KEY_ENABLED = "enabledThemes"
    private const val KEY_IMPORTED = "importedThemer"
    private const val KEY_TRANSPARENCY = "transparencyMode"
    private const val KEY_FONTS = "customFonts"
    private const val KEY_SOUNDS = "customSounds"
    private const val KEY_ICONS = "customIcons"
    private const val KEY_RANDOMIZE = "randomizeOnStart"
    private const val KEY_LAST_RANDOM = "lastRandomTheme"

    lateinit var settings: SettingsAPI

    /** Installed themes, sorted by name. */
    val themes = ArrayList<ThemeFile>()

    /** Files that could not be read, with the reason. */
    val brokenFiles = LinkedHashMap<String, String>()

    var transparencyMode: TransparencyMode
        get() = TransparencyMode.from(settings.getInt(KEY_TRANSPARENCY, TransparencyMode.CHAT.value))
        set(value) = settings.setInt(KEY_TRANSPARENCY, value.value)

    var customFonts: Boolean
        get() = settings.getBool(KEY_FONTS, true)
        set(value) = settings.setBool(KEY_FONTS, value)

    var customSounds: Boolean
        get() = settings.getBool(KEY_SOUNDS, true)
        set(value) = settings.setBool(KEY_SOUNDS, value)

    var customIcons: Boolean
        get() = settings.getBool(KEY_ICONS, true)
        set(value) = settings.setBool(KEY_ICONS, value)

    var randomizeOnStart: Boolean
        get() = settings.getBool(KEY_RANDOMIZE, false)
        set(value) = settings.setBool(KEY_RANDOMIZE, value)

    /**
     * Enables one random installed theme, and only that one. [usable] filters out themes that can't
     * load; the previous pick is avoided when there is another choice. Returns the pick, or null.
     */
    fun pickRandom(usable: (ThemeFile) -> Boolean): ThemeFile? {
        val candidates = themes.filter(usable)
        if (candidates.isEmpty()) return null
        val last = settings.getString(KEY_LAST_RANDOM, "")
        val pool = candidates.filter { it.id != last }.ifEmpty { candidates }
        val pick = pool.random()
        settings.setString(KEY_LAST_RANDOM, pick.id)
        saveEnabled(listOf(pick.id))
        return pick
    }

    fun loadThemes() {
        if (!THEME_DIR.exists() && !THEME_DIR.mkdirs()) {
            logger.error("Failed to create theme directory ${THEME_DIR.absolutePath}", null)
        }
        val loaded = ArrayList<ThemeFile>()
        brokenFiles.clear()
        THEME_DIR.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { file ->
            try {
                loaded += ThemeFile(file)
            } catch (error: Throwable) {
                brokenFiles[file.name] = error.message ?: error.javaClass.simpleName
                logger.error("Failed to read theme ${file.name}", error)
            }
        }
        loaded.sortBy { it.name.lowercase() }
        themes.clear()
        themes.addAll(loaded)
        // Forget enabled entries whose file is gone.
        val ids = themes.map { it.id }.toSet()
        val enabled = enabledIds()
        if (enabled.any { it !in ids }) saveEnabled(enabled.filter { it in ids })
    }

    /** Enabled theme file names, lowest layer first. */
    fun enabledIds(): List<String> = settings.getString(KEY_ENABLED, "").split('\n').filter { it.isNotEmpty() }

    private fun saveEnabled(ids: List<String>) = settings.setString(KEY_ENABLED, ids.distinct().joinToString("\n"))

    fun enabledThemes(): List<ThemeFile> {
        val byId = themes.associateBy { it.id }
        return enabledIds().mapNotNull { byId[it] }
    }

    fun isEnabled(theme: ThemeFile) = theme.id in enabledIds()

    fun setEnabled(theme: ThemeFile, enabled: Boolean) {
        val ids = enabledIds().filter { it != theme.id }
        saveEnabled(if (enabled) ids + theme.id else ids)
    }

    /** Moves an enabled theme one layer up (later, wins more) or down. */
    fun move(theme: ThemeFile, up: Boolean): Boolean {
        val ids = enabledIds().toMutableList()
        val index = ids.indexOf(theme.id)
        val target = if (up) index + 1 else index - 1
        if (index < 0 || target !in ids.indices) return false
        ids[index] = ids[target]
        ids[target] = theme.id
        saveEnabled(ids)
        return true
    }

    fun rename(old: String, new: String) {
        saveEnabled(enabledIds().map { if (it == old) new else it })
    }

    /** One-time import of the old Themer plugin's enabled themes and transparency mode. */
    fun importThemer() {
        if (settings.getBool(KEY_IMPORTED, false)) return
        settings.setBool(KEY_IMPORTED, true)
        try {
            if (!File(com.aliucord.Constants.SETTINGS_PATH, "Themer.json").exists()) return
            val themer = SettingsAPI("Themer")
            val enabled = themes.filter { themer.getBool("${it.name}-enabled", false) }.map { it.id }
            if (enabled.isNotEmpty()) saveEnabled(enabledIds() + enabled)
            if (themer.exists("transparencyMode")) {
                transparencyMode = TransparencyMode.from(themer.getInt("transparencyMode", TransparencyMode.CHAT.value))
            }
            if (themer.exists("customSounds")) customSounds = themer.getBool("customSounds", true)
            logger.info("Imported ${enabled.size} enabled theme(s) from Themer")
        } catch (error: Throwable) {
            logger.error("Failed to import Themer settings", error)
        }
    }

    // ---- Downloaded theme files ----

    private val cacheDir: File
        get() = File(Utils.appContext.cacheDir, "Themes-Cache").also { it.mkdirs() }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { String.format("%02x", it) }
        return File(cacheDir, name)
    }

    /** The local file for [url] if it is a `file://` path or already downloaded, else null. */
    fun localFile(url: String): File? {
        if (url.startsWith("file://")) return File(url.removePrefix("file://")).takeIf { it.isFile }
        return cacheFile(url).takeIf { it.isFile && it.length() > 0 }
    }

    /** Downloads [url] into the cache if needed. Blocking. */
    fun fetch(url: String): File {
        if (url.startsWith("file://")) {
            return localFile(url) ?: throw FileNotFoundException(url.removePrefix("file://"))
        }
        localFile(url)?.let { return it }
        // The resolver and the dead theme check can ask for the same file at once.
        synchronized(downloadLocks.getOrPut(url) { Any() }) {
            localFile(url)?.let { return it }
            verifyUntrustedUrl(url)
            val file = cacheFile(url)
            val part = File(file.path + ".part")
            try {
                if (DiscordLinks.isDiscordUrl(url)) {
                    DiscordLinks.download(url, part)
                } else {
                    // Mirror links contain theme names with spaces.
                    Http.Request(url.replace(" ", "%20")).use { it.execute().apply { assertOk() }.saveToFile(part) }
                }
                if (!part.renameTo(file)) part.copyTo(file, overwrite = true)
            } finally {
                part.delete()
            }
            return file
        }
    }

    private val downloadLocks = ConcurrentHashMap<String, Any>()

    fun clearCache() {
        cacheDir.deleteRecursively()
    }
}
