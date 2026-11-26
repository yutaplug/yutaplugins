package com.github.yutaplug.fixes.updater

import com.aliucord.Logger
import java.util.regex.Pattern

/** Aliucord's own builds, from the `builds` branch of Aliucord/Aliucord on GitHub. */
internal object CoreBuilds {
    private const val REPO = "Aliucord/Aliucord"
    const val DATA_URL = "https://raw.githubusercontent.com/$REPO/builds/data.json"
    const val ZIP_URL = "https://raw.githubusercontent.com/$REPO/builds/Aliucord.zip"

    /** Aliucord has no changelog of its own; this one is kept on yutaplug's Aliucord documentation site. */
    private const val CHANGELOG_SOURCE = "https://raw.githubusercontent.com/yutaplug/Aliucord/refs/heads/main/index.md"
    const val CHANGELOG_URL = "https://yutaplug.github.io/Aliucord/#changelog"

    private val NEWLINE = Pattern.compile("\\r?\\n")
    private val VERSION_HEADER = Pattern.compile("^#{2,4}\\s*v?(\\d+(?:\\.\\d+){1,2})\\b.*$")

    /** Whether Aliucord is loading a custom core pushed by a developer, which must not be replaced. */
    fun isCustomCoreLoaded(): Boolean = try {
        val updater = Class.forName("com.aliucord.updater.CoreUpdater")
        updater.getDeclaredMethod("isCustomCoreLoaded").apply { isAccessible = true }.invoke(null) as Boolean
    } catch (_: Throwable) {
        false
    }

    /**
     * Aliucord's changes after [from] up to [to] (only [to]'s own if [from] is null), from the
     * `# Changelog` section of the documentation site, with a warning if [to] isn't listed there yet.
     */
    fun changelog(from: String?, to: String, logger: Logger): String = try {
        buildChangelog(from, to, GitHubFiles(logger).text(CHANGELOG_SOURCE))
    } catch (e: Throwable) {
        logger.warn("Could not load Aliucord's changelog: ${e.message}")
        "# Aliucord $to\n* Couldn't load the changelog. Tap the icon below to open it in your browser."
    }

    private fun buildChangelog(from: String?, to: String, page: String): String {
        // version -> its bullet points, newest first, as listed on the page.
        val versions = LinkedHashMap<String, MutableList<String>>()
        var inChangelog = false
        var current: MutableList<String>? = null
        for (raw in NEWLINE.split(page)) {
            val line = raw.trim()
            if (line.startsWith("# ")) {
                if (inChangelog) break
                inChangelog = line.substring(2).trim().equals("Changelog", ignoreCase = true)
                continue
            }
            if (!inChangelog) continue
            val m = VERSION_HEADER.matcher(line)
            if (m.matches()) {
                current = versions.getOrPut(m.group(1)!!) { ArrayList() }
                continue
            }
            if (current != null && (line.startsWith("- ") || line.startsWith("* "))) {
                current.add(line.substring(2).trim())
            }
        }
        if (versions.isEmpty()) throw IllegalStateException("No versions in the changelog")

        val newestListed = versions.keys.firstOrNull { Versions.parse(it) != null }
        val included = versions.filterKeys { version ->
            if (from == null) {
                Versions.parse(version) == Versions.parse(to)
            } else {
                Versions.newer(version, from) && !Versions.newer(version, to)
            }
        }
        return buildString {
            if (Versions.newer(to, newestListed)) {
                append("# Changelog not updated yet\n")
                append("* The changelog hasn't been updated for Aliucord ").append(to).append(" yet")
                append(if (included.isEmpty()) ", so its changes aren't listed.\n\n" else ". Only older changes are listed below.\n\n")
            }
            for ((version, lines) in included) {
                append("# ").append(version).append('\n')
                if (lines.isEmpty()) append("* No user-facing changes.\n")
                for (line in lines) append("* ").append(line).append('\n')
                append('\n')
            }
        }.trim()
    }
}
