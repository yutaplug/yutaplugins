package com.github.yutaplug.fixes.updater

import android.os.Build
import com.aliucord.Constants
import com.aliucord.utils.SemVer
import org.json.JSONObject
import java.util.regex.Pattern

/** One plugin's entry in its repository's updater.json. */
internal class PluginBuild(
    val version: String,
    val build: String,
    val crc32: Long?,
    val changelog: String?,
    val changelogMedia: String?,
    val minimumDiscordVersion: Int,
    val minimumAliucordVersion: String?,
    val minimumApiLevel: Int,
) {
    companion object {
        fun parse(json: JSONObject): PluginBuild? {
            val version = json.optString("version", "")
            val build = json.optString("build", "")
            if (version.isEmpty() || build.isEmpty()) return null
            return PluginBuild(
                version = version,
                build = build,
                crc32 = json.optString("buildCrc32", "").takeIf { it.isNotEmpty() }?.toLongOrNull(16),
                changelog = json.optNullableString("changelog"),
                changelogMedia = json.optNullableString("changelogMedia"),
                minimumDiscordVersion = json.optInt("minimumDiscordVersion", 0),
                minimumAliucordVersion = json.optNullableString("minimumAliucordVersion"),
                // Aliucord's default before this field existed.
                minimumApiLevel = json.optInt("minimumApiLevel", 24),
            )
        }
    }
}

internal class PluginUpdate(
    val name: String,
    /** The zip's name in the plugins folder, without `.zip`. */
    val fileName: String,
    val currentVersion: String,
    val build: PluginBuild,
    val repo: String?,
    latestCore: String?,
) {
    /** Why this update can't be installed yet, or null if it can. */
    val blockedReason: String? = when {
        build.minimumDiscordVersion > Versions.discord ->
            "Needs a newer Discord base. Reinstall Aliucord with Aliucord Manager first."
        build.minimumApiLevel > Build.VERSION.SDK_INT ->
            "Needs Android API ${build.minimumApiLevel} or newer."
        Versions.newer(build.minimumAliucordVersion, Versions.core) -> {
            val min = build.minimumAliucordVersion
            if (latestCore != null && !Versions.newer(min, latestCore)) {
                "Needs Aliucord $min. Update Aliucord first."
            } else {
                "Needs Aliucord $min or newer."
            }
        }
        else -> null
    }

    val canInstall: Boolean get() = blockedReason == null

    /** Only the changelog entries newer than the installed version. */
    val newChangelog: String? get() = Changelogs.since(build.changelog, currentVersion)
}

internal class CoreUpdate(
    val currentVersion: String,
    val latestVersion: String,
    /** Why the update needs Aliucord Manager instead, or null if it can be downloaded here. */
    val reinstallReason: String?,
)

internal class PluginInfo(val name: String, val version: String, val repo: String?)

internal class CheckResult(
    val time: Long,
    val core: CoreUpdate?,
    /** The latest Aliucord version on GitHub, whether or not it's newer. */
    val latestCore: String?,
    val coreError: String?,
    val plugins: List<PluginUpdate>,
    val upToDate: List<PluginInfo>,
    /** Plugin name to the reason its update couldn't be checked. */
    val errors: Map<String, String>,
    /** Plugins without an updater URL. */
    val untracked: List<PluginInfo>,
) {
    val count: Int get() = plugins.size + (if (core != null) 1 else 0)
}

internal object Versions {
    /** The running Aliucord version. Read at runtime: `BuildConfig.VERSION` is inlined at compile time. */
    val core: String by lazy {
        try {
            Class.forName("com.aliucord.BuildConfig").getField("VERSION").get(null) as String
        } catch (_: Throwable) {
            "0.0.0"
        }
    }

    val discord: Int by lazy {
        try {
            Constants::class.java.getField("DISCORD_VERSION").getInt(null)
        } catch (_: Throwable) {
            126021
        }
    }

    fun parse(version: String?): SemVer? = if (version == null) null else SemVer.parseOrNull(version.trim().removePrefix("v"))

    /** Whether [a] is a newer version than [b]. Unparseable versions are never newer. */
    fun newer(a: String?, b: String?): Boolean {
        val va = parse(a) ?: return false
        val vb = parse(b) ?: SemVer.Zero
        return va > vb
    }
}

internal object Changelogs {
    private val VERSION_HEADER = Pattern.compile("^#{1,3}\\s*v?(\\d+(?:\\.\\d+){1,2})\\b.*$")
    private val NEWLINE = Pattern.compile("\\r?\\n")

    /** The sections of a `# 1.0.0`-style [changelog] newer than [installed]; the whole changelog if it has no such headers. */
    fun since(changelog: String?, installed: String?): String? {
        if (changelog == null || changelog.trim().isEmpty()) return null
        val out = StringBuilder()
        var sawHeader = false
        var keep = true
        for (line in NEWLINE.split(changelog)) {
            val m = VERSION_HEADER.matcher(line.trim())
            if (m.matches()) {
                sawHeader = true
                keep = Versions.newer(m.group(1), installed)
            }
            if (keep) out.append(line).append('\n')
        }
        val result = out.toString().trim()
        return if (!sawHeader || result.isEmpty()) changelog.trim() else result
    }

    /** The first few bullet points of [changelog], for a short preview. */
    fun preview(changelog: String?, max: Int): List<String> {
        if (changelog == null) return emptyList()
        val result = ArrayList<String>()
        for (raw in NEWLINE.split(changelog)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            result.add(line.removePrefix("* ").removePrefix("- ").removePrefix("+ "))
            if (result.size >= max) break
        }
        return result
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
