package com.github.yutaplug.fixes.updater

import android.text.format.DateUtils
import com.aliucord.PluginManager
import com.aliucord.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * When each version of a plugin was released, shown next to the versions in its changelog.
 *
 * A builds branch only commits a plugin's zip when it changed, so every version is among the commits of
 * its zip. Each of those commits' updater.json says which version it was; a version's release date is
 * the first commit that has it. Dates are cached in the settings, so a version is only looked up once.
 */
internal object ReleaseDates {
    private const val KEY = "updater_releaseDates"
    private val HEADER = Pattern.compile("^(\\s*#{1,3}\\s*v?(\\d+(?:\\.\\d+){1,2})\\b.*?)\\s*$")
    private val NEWLINE = Pattern.compile("\\r?\\n")

    /** Plugins already looked up in this process, so an unknown date isn't fetched over and over. */
    private val looked: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()

    /** [changelog] with each version header's release date. Does network requests; call it off the main thread. */
    fun dated(pluginName: String, changelog: String?): String? {
        if (changelog == null || changelog.trim().isEmpty()) return changelog
        val versions = NEWLINE.split(changelog).mapNotNull { line ->
            HEADER.matcher(line).takeIf { it.matches() }?.group(2)
        }
        if (versions.isEmpty()) return changelog
        val dates = try {
            dates(pluginName, versions)
        } catch (e: Throwable) {
            Updater.logger.warn("Could not get the release dates of $pluginName: ${e.message}")
            return changelog
        }
        if (dates.isEmpty()) return changelog
        return NEWLINE.split(changelog).joinToString("\n") { line ->
            val m = HEADER.matcher(line)
            val date = if (m.matches()) dates[m.group(2)] else null
            if (date == null) line else "${m.group(1)} · ${format(date)}"
        }
    }

    /** [version]'s release date, formatted, or null if unknown. Does network requests; call it off the main thread. */
    fun dateOf(pluginName: String, version: String): String? = try {
        val clean = version.trim().removePrefix("v")
        dates(pluginName, listOf(clean))[clean]?.let { format(it) }
    } catch (e: Throwable) {
        Updater.logger.warn("Could not get the release date of $pluginName $version: ${e.message}")
        null
    }

    /** The release date of each of [versions] that could be found. */
    private fun dates(pluginName: String, versions: List<String>): Map<String, Long> {
        val cached = load(pluginName)
        // The newest version is the one most likely missing; older ones without a date stay that way.
        if (versions.all { it in cached } || !looked.add(pluginName)) return cached
        val found = fetch(pluginName) ?: return cached
        if (found.isEmpty()) return cached
        synchronized(lock) {
            val all = load(pluginName) + found
            save(pluginName, all)
            return all
        }
    }

    private fun fetch(pluginName: String): Map<String, Long>? {
        val plugin = PluginManager.plugins[pluginName] ?: return null
        val updaterUrl = plugin.manifest.updateUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val updaterFile = GitHubFiles.parse(updaterUrl) ?: return null
        // The first lookup reads many commits, so the changelog can take a moment to open.
        Utils.mainThread.post { Utils.showToast("Loading release dates…") }
        val entryName = plugin.manifest.name ?: plugin.name
        val files = GitHubFiles(Updater.logger)

        val latest = JSONObject(files.text(updaterUrl))
        val build = (latest.optJSONObject(entryName) ?: latest.optJSONObject(pluginName))
            ?.optString("build", "")?.takeIf { it.isNotEmpty() } ?: return null
        val zip = GitHubFiles.parse(UpdaterUi.replaceLiteral(build, "%s", pluginName)) ?: return null
        // Each commit's updater.json is read at that commit, which needs both files in one repository.
        if (!zip.repoName.equals(updaterFile.repoName, ignoreCase = true)) return null

        val commits = commits(zip)
        if (commits.isEmpty()) return emptyMap()

        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = commits.map { (sha, time) ->
                pool.submit(
                    Callable {
                        val json = JSONObject(files.text(updaterFile.rawUrl(sha)))
                        val entry = json.optJSONObject(entryName) ?: json.optJSONObject(pluginName)
                        entry?.optString("version", "")?.takeIf { it.isNotEmpty() }?.let { it to time }
                    },
                )
            }
            val result = HashMap<String, Long>()
            for (future in futures) {
                val (version, time) = try {
                    future.get(30, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val clean = version.trim().removePrefix("v")
                val earlier = result[clean]
                if (earlier == null || time < earlier) result[clean] = time
            }
            return result
        } finally {
            pool.shutdownNow()
        }
    }

    /** The commits that changed [file] on its branch (up to 100, GitHub's page size), with their dates. */
    private fun commits(file: GitHubFile): List<Pair<String, Long>> {
        val sha = if (file.ref == "HEAD") "" else "&sha=" + encode(file.ref.removePrefix("refs/heads/").removePrefix("refs/tags/"))
        val api = "https://api.github.com/repos/${file.owner}/${file.repo}/commits?per_page=100$sha&path=${encode(file.path)}"
        GitHubFiles.request(api).setHeader("Accept", "application/vnd.github+json").execute().use { resp ->
            if (!resp.ok()) throw IOException("HTTP ${resp.statusCode}")
            val array = JSONArray(resp.text())
            val result = ArrayList<Pair<String, Long>>(array.length())
            for (i in 0 until array.length()) {
                val commit = array.getJSONObject(i)
                val date = commit.getJSONObject("commit").getJSONObject("committer").getString("date")
                val time = ISO_DATE.get()!!.parse(date)?.time ?: continue
                result.add(commit.getString("sha") to time)
            }
            return result
        }
    }

    private fun load(pluginName: String): Map<String, Long> {
        val all = try {
            JSONObject(Updater.settings?.getString(KEY, "{}") ?: "{}")
        } catch (_: Throwable) {
            JSONObject()
        }
        val obj = all.optJSONObject(pluginName) ?: return emptyMap()
        val result = HashMap<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.optLong(key, 0L).takeIf { it > 0 } ?: continue
        }
        return result
    }

    private fun save(pluginName: String, dates: Map<String, Long>) {
        val settings = Updater.settings ?: return
        val all = try {
            JSONObject(settings.getString(KEY, "{}"))
        } catch (_: Throwable) {
            JSONObject()
        }
        val obj = JSONObject()
        for ((version, time) in dates) obj.put(version, time)
        all.put(pluginName, obj)
        settings.setString(KEY, all.toString())
    }

    private fun format(time: Long): String = DateUtils.formatDateTime(
        Utils.appContext,
        time,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    // GitHub's dates are UTC, like 2026-11-26T19:22:38Z. SimpleDateFormat isn't thread-safe.
    private val ISO_DATE = object : ThreadLocal<java.text.SimpleDateFormat>() {
        override fun initialValue() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
