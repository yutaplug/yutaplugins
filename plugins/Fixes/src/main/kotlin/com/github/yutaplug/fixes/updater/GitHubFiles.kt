package com.github.yutaplug.fixes.updater

import com.aliucord.Http
import com.aliucord.Logger
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/** A file in a GitHub repository, at a branch, tag or commit. */
internal class GitHubFile(val owner: String, val repo: String, val ref: String, val path: String) {
    val repoName: String get() = "$owner/$repo"

    fun rawUrl(at: String = ref) = "https://raw.githubusercontent.com/$owner/$repo/$at/$path"
}

/**
 * Fetches updater files straight from GitHub instead of jsDelivr, whose cache can serve
 * builds that are hours or days old. Each repository's branch is first resolved to its latest
 * commit, and files are read at that commit, so raw.githubusercontent.com's own 5 minute cache
 * can't serve a stale copy either. If resolving fails, the branch
 * URL is used.
 *
 * One instance is used per update check, so every file of a repository comes from the same commit.
 */
internal class GitHubFiles(private val logger: Logger) {
    private val repoRefs = ConcurrentHashMap<String, Map<String, String>>()
    private val failed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun text(url: String): String {
        var lastError: Throwable? = null
        for (candidate in candidates(url)) {
            try {
                request(candidate).execute().use { resp ->
                    if (resp.ok()) return resp.text()
                    // A missing file stays missing at every candidate URL.
                    if (resp.statusCode == 404 && candidate == url) throw NotFound(url)
                    lastError = IOException("HTTP ${resp.statusCode} from $candidate")
                }
            } catch (e: NotFound) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Could not fetch $url")
    }

    fun download(url: String, to: File) {
        var lastError: Throwable? = null
        for (candidate in candidates(url)) {
            try {
                request(candidate).execute().use { resp ->
                    if (!resp.ok()) {
                        lastError = IOException("HTTP ${resp.statusCode} from $candidate")
                        return@use
                    }
                    to.parentFile?.mkdirs()
                    if (to.exists()) to.delete()
                    resp.saveToFile(to)
                    if (to.length() > 0) return
                    lastError = IOException("Empty download from $candidate")
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }
        to.delete()
        throw lastError ?: IOException("Could not download $url")
    }

    /** The URLs to try for [url], most up to date first. */
    private fun candidates(url: String): List<String> {
        val file = parse(url) ?: return listOf(url)
        val result = ArrayList<String>(3)
        commit(file)?.let { result.add(file.rawUrl(it)) }
        result.add(file.rawUrl())
        if (url !in result) result.add(url)
        return result
    }

    /** The latest commit of [file]'s branch, or null if it can't be looked up. */
    fun commit(file: GitHubFile): String? {
        if (SHA.matcher(file.ref).matches()) return file.ref
        val refs = refs(file) ?: return null
        val ref = file.ref
        val names = if (ref == "HEAD" || ref.startsWith("refs/")) listOf(ref) else listOf("refs/heads/$ref", "refs/tags/$ref")
        // An annotated tag's commit is listed as "<tag>^{}".
        return names.asSequence().mapNotNull { refs["$it^{}"] ?: refs[it] }.firstOrNull()
            .also { if (it == null) logger.warn("Could not resolve ${file.repoName}@$ref: no such branch") }
    }

    /**
     * Every branch and tag of [file]'s repository with its commit, from the endpoint `git fetch` uses.
     * Unlike GitHub's API, it isn't limited to 60 requests an hour per IP address, which a mobile
     * network's shared address can use up on its own.
     */
    private fun refs(file: GitHubFile): Map<String, String>? {
        val key = file.repoName.lowercase()
        repoRefs[key]?.let { return it }
        if (key in failed) return null
        return try {
            val url = "https://github.com/${file.owner}/${file.repo}.git/info/refs?service=git-upload-pack"
            request(url).execute().use { resp ->
                if (!resp.ok()) throw IOException("HTTP ${resp.statusCode}")
                val refs = HashMap<String, String>()
                val m = REF_LINE.matcher(resp.text())
                while (m.find()) refs[m.group(2)!!] = m.group(1)!!
                if (refs.isEmpty()) throw IOException("no refs listed")
                repoRefs[key] = refs
                refs
            }
        } catch (e: Throwable) {
            logger.warn("Could not resolve ${file.repoName}@${file.ref}: ${e.message}")
            failed.add(key)
            null
        }
    }

    /**
     * When [url]'s file last changed on its branch, from GitHub's API, or null if it can't be looked up
     * (not on GitHub, or the API's limit of 60 requests an hour per IP address was used up).
     * Builds branches only commit a plugin's zip when it changed, so for a zip that's its release date.
     */
    fun lastChanged(url: String): Long? {
        val file = parse(url) ?: return null
        return try {
            // HEAD means the default branch, which the API uses when no sha is given.
            val sha = if (file.ref == "HEAD") "" else "&sha=${encode(file.ref.removePrefix("refs/heads/").removePrefix("refs/tags/"))}"
            val api = "https://api.github.com/repos/${file.owner}/${file.repo}/commits?per_page=1" +
                "$sha&path=${encode(file.path)}"
            request(api).setHeader("Accept", "application/vnd.github+json").execute().use { resp ->
                if (!resp.ok()) throw IOException("HTTP ${resp.statusCode}")
                val commits = org.json.JSONArray(resp.text())
                if (commits.length() == 0) return null
                val date = commits.getJSONObject(0).getJSONObject("commit").getJSONObject("committer").getString("date")
                ISO_DATE.get()!!.parse(date)?.time
            }
        } catch (e: Throwable) {
            logger.warn("Could not get the release date of ${file.repoName}/${file.path}: ${e.message}")
            null
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    class NotFound(url: String) : IOException("Not found: $url")

    companion object {
        private val SLASH = Pattern.compile("/")
        private val SHA = Pattern.compile("^[0-9a-f]{40}$")

        // GitHub's dates are UTC, like 2026-11-26T19:22:38Z. SimpleDateFormat isn't thread-safe.
        private val ISO_DATE = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        }

        // "<pkt-len><sha> <ref>" followed by NUL (capabilities, first line only) or a newline.
        private val REF_LINE = Pattern.compile("([0-9a-f]{40}) ([^\u0000\n]+)")

        // java.util.regex rather than kotlin.text.Regex, which is broken by Discord's obfuscated stdlib.
        private val RAW = Pattern.compile("^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(.+)$")
        private val GITHUB = Pattern.compile("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/(?:raw|blob)/(.+)$")
        private val JSDELIVR = Pattern.compile(
            "^https?://(?:cdn|fastly|gcore|testingcf|originfastly)\\.jsdelivr\\.net/gh/([^/@]+)/([^/@]+)(?:@([^/]+(?:/heads/[^/]+)?))?/(.+)$",
        )

        /** Parses raw GitHub, github.com raw/blob and jsDelivr GitHub URLs. Others return null. */
        fun parse(url: String): GitHubFile? {
            val clean = url.substringBefore('?').substringBefore('#')
            var m = JSDELIVR.matcher(clean)
            if (m.matches()) {
                return GitHubFile(m.group(1)!!, m.group(2)!!, m.group(3) ?: "HEAD", m.group(4)!!)
            }
            m = RAW.matcher(clean)
            if (!m.matches()) m = GITHUB.matcher(clean)
            if (!m.matches()) return null
            val rest = m.group(3)!!
            val parts = SLASH.split(rest).toList()
            // "refs/heads/<branch>/<path>" or "<branch>/<path>"
            val refParts = if (parts.size > 3 && parts[0] == "refs" && (parts[1] == "heads" || parts[1] == "tags")) 3 else 1
            if (parts.size <= refParts) return null
            val ref = parts.subList(0, refParts).joinToString("/")
            val path = parts.subList(refParts, parts.size).joinToString("/")
            return GitHubFile(m.group(1)!!, m.group(2)!!, ref, path)
        }

        fun request(url: String): Http.Request = Http.Request(url)
            .setHeader("User-Agent", "Aliucord-Updater")
            .setHeader("Cache-Control", "no-cache")
            .setRequestTimeout(20_000)
    }
}
