package com.github.yutaplug.playembeds

import android.net.Uri
import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

/**
 * Resolves SoundCloud tracks (or a set's first track) through api-v2.
 *
 * SoundCloud is replacing progressive MP3 with AAC HLS, which Discord's
 * progressive-only ExoPlayer can't play. HLS tracks are downloaded segment by
 * segment into one local file that plays as progressive media.
 */
internal class SoundCloudResolver(private val cacheDir: File) : CachedResolver<ResolvedMedia>() {
    @Volatile private var clientId: String? = null

    init {
        clearCache()
    }

    override fun key(url: String): String? {
        var uri = Uri.parse(url)
        if (EmbedUrls.isHost(uri.host, "w.soundcloud.com")) {
            uri = Uri.parse(uri.getQueryParameter("url") ?: return null)
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!EmbedUrls.isHost(host, "soundcloud.com") || host == "w.soundcloud.com") return null
        // trimEnd iterates a reversed IntProgression, which Discord's obfuscated stdlib breaks.
        var path = uri.path.orEmpty()
        while (path.endsWith("/")) path = path.dropLast(1)
        if (path.isEmpty()) return null
        return "https://$host$path"
    }

    override fun isStillValid(value: ResolvedMedia): Boolean {
        val uri = Uri.parse(value.uri)
        return uri.scheme != "file" || File(uri.path.orEmpty()).exists()
    }

    override fun onClose() = clearCache()

    override fun load(key: String): Pair<ResolvedMedia, Long>? {
        var target = key
        val host = Uri.parse(key).host
        if (host == "on.soundcloud.com") target = finalUrl(key)
        if (host == "m.soundcloud.com") target = key.replaceFirst("m.soundcloud.com", "soundcloud.com")

        val resolved = JSONObject(apiGet { "$API/resolve?url=${Uri.encode(target)}&client_id=$it" } ?: return null)
        var track = when (resolved.optString("kind")) {
            "track" -> resolved
            "playlist" -> resolved.optJSONArray("tracks")?.optJSONObject(0) ?: return null
            else -> return null
        }
        if (track.optJSONObject("media") == null) {
            val id = track.optLong("id")
            val tracks = JSONArray(apiGet { "$API/tracks?ids=$id&client_id=$it" } ?: return null)
            track = tracks.optJSONObject(0) ?: return null
        }

        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings") ?: return null
        var progressive: JSONObject? = null
        var hls: JSONObject? = null
        var hlsIsAac = false
        for (index in 0 until transcodings.length()) {
            val transcoding = transcodings.optJSONObject(index) ?: continue
            val format = transcoding.optJSONObject("format") ?: continue
            val protocol = format.optString("protocol")
            val mime = format.optString("mime_type").lowercase(Locale.ROOT)
            if (protocol == "progressive") {
                if (progressive == null) progressive = transcoding
            } else if (protocol == "hls") {
                val aac = mime.contains("mp4") || mime.contains("aac")
                if ((aac && !hlsIsAac) || (hls == null && mime.contains("mpeg"))) {
                    hls = transcoding
                    hlsIsAac = aac
                }
            }
        }

        val authorization = track.optString("track_authorization")
        progressive?.let { transcoding ->
            streamUrl(transcoding, authorization)?.let { url ->
                return ResolvedMedia(url, 0, 0, audioOnly = true) to 600_000L
            }
        }
        val chosen = hls ?: return null
        if (track.optLong("duration") > MAX_HLS_DURATION_MS) return null
        val playlist = streamUrl(chosen, authorization) ?: return null
        val file = downloadHls(playlist, track.optLong("id")) ?: return null
        return ResolvedMedia(file, 0, 0, audioOnly = true) to 3_600_000L
    }

    private fun streamUrl(transcoding: JSONObject, authorization: String): String? {
        val base = transcoding.optString("url")
        if (!EmbedUrls.isHttpUrl(base)) return null
        val body = apiGet { id ->
            Uri.parse(base).buildUpon().apply {
                appendQueryParameter("client_id", id)
                if (authorization.isNotEmpty()) appendQueryParameter("track_authorization", authorization)
            }.build().toString()
        } ?: return null
        return JSONObject(body).optString("url").takeIf { Uri.parse(it).scheme == "https" }
    }

    /** GET an api-v2 URL built from the client ID, scraping a fresh ID once on 401/403. */
    private fun apiGet(build: (String) -> String): String? {
        for (attempt in 0..1) {
            val id = clientId(refresh = attempt == 1) ?: return null
            Http.Request(build(id)).use { request ->
                request.setRequestTimeout(TIMEOUT_MS)
                request.setHeader("User-Agent", BROWSER_USER_AGENT)
                request.execute().use { response ->
                    if (response.ok()) return response.text()
                    if (response.statusCode != 401 && response.statusCode != 403) return null
                }
            }
        }
        return null
    }

    private fun clientId(refresh: Boolean): String? {
        if (!refresh) clientId?.let { return it }
        clientId = null
        val html = getText("https://soundcloud.com/") ?: return null
        val scripts = SCRIPT_SRC.allGroups(html).asReversed()
        for (script in scripts) {
            val js = try {
                getText(script)
            } catch (_: IOException) {
                null
            } ?: continue
            val id = CLIENT_ID.firstGroup(js) ?: continue
            clientId = id
            return id
        }
        return null
    }

    private fun downloadHls(playlistUrl: String, trackId: Long): String? {
        var url = playlistUrl
        var text = getText(url) ?: return null
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = LINE_BREAK.split(text)
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
            url = URL(URL(url), variant).toString()
            text = getText(url) ?: return null
        }

        val parts = ArrayList<String>()
        var hasInit = false
        for (raw in LINE_BREAK.split(text)) {
            val line = raw.trim()
            when {
                line.isEmpty() -> continue
                line.startsWith("#EXT-X-KEY") -> if (!line.contains("METHOD=NONE")) return null
                line.startsWith("#EXT-X-MAP") -> if (!hasInit) {
                    val init = MAP_URI.firstGroup(line) ?: return null
                    parts.add(URL(URL(url), init).toString())
                    hasInit = true
                }
                !line.startsWith("#") -> parts.add(URL(URL(url), line).toString())
            }
        }
        if (parts.isEmpty()) return null

        cacheDir.mkdirs()
        val target = File(cacheDir, "sc_$trackId$AUDIO_SUFFIX")
        if (target.length() > 0) return Uri.fromFile(target).toString()
        val partial = File(cacheDir, "sc_$trackId.part")
        try {
            FileOutputStream(partial).use { out ->
                for (part in parts) {
                    Http.Request(part).use { request ->
                        request.setRequestTimeout(TIMEOUT_MS)
                        request.setHeader("User-Agent", BROWSER_USER_AGENT)
                        request.execute().use { response ->
                            if (!response.ok()) throw IOException("HTTP ${response.statusCode}")
                            response.pipe(out)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            partial.delete()
            return null
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        trimCache()
        return Uri.fromFile(target).toString()
    }

    private fun trimCache() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(AUDIO_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_FILES)
            ?.forEach { it.delete() }
    }

    private fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val API = "https://api-v2.soundcloud.com"
        const val AUDIO_SUFFIX = ".audio"
        const val MAX_CACHED_FILES = 5
        const val MAX_HLS_DURATION_MS = 30L * 60L * 1_000L
        val SCRIPT_SRC: Pattern = Pattern.compile("<script[^>]+src=\"(https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js)\"")
        val CLIENT_ID: Pattern = Pattern.compile("client_id\\s*[:=]\\s*\"?([0-9A-Za-z]{32})")
        val MAP_URI: Pattern = Pattern.compile("URI=\"([^\"]+)\"")
        val LINE_BREAK: Pattern = Pattern.compile("\r?\n")
    }
}
