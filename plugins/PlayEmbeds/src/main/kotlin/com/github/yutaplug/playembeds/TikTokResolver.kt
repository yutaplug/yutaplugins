package com.github.yutaplug.playembeds

import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

/**
 * Resolves TikTok videos from the video's web page, read as plain text: its
 * `__UNIVERSAL_DATA_FOR_REHYDRATION__` JSON lists the MP4 files. TikTok only
 * serves those with the `tt_chain_token` cookie the page set and a TikTok
 * Referer, which Discord's player can't send, so they play through [server].
 * Photo slideshows have no video and stay external.
 */
internal class TikTokResolver : CachedResolver<ResolvedMedia>() {
    private val server = StreamServer {}

    override fun key(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" && uri.scheme?.lowercase(Locale.ROOT) != "http") return null
        if (host in SHORT_HOSTS) return if (uri.pathSegments.isNotEmpty()) "short:$url" else null
        if (!EmbedUrls.isHost(host, "tiktok.com")) return null
        val path = uri.path.orEmpty()
        if (SHORT_PATH.matcher(path).matches()) return "short:$url"
        return VIDEO_PATH.firstGroup(path)
    }

    override fun isStillValid(value: ResolvedMedia): Boolean = server.serves(value.uri)

    override fun onClose() = server.close()

    override fun load(key: String): Pair<ResolvedMedia, Long>? {
        val id = if (key.startsWith("short:")) {
            val final = finalUrl(key.removePrefix("short:"))
            VIDEO_PATH.firstGroup(Uri.parse(final).path.orEmpty()) ?: return null
        } else {
            key
        }

        val page = URL("https://www.tiktok.com/@i/video/$id").openConnection() as HttpURLConnection
        val (html, cookies) = try {
            page.connectTimeout = TIMEOUT_MS
            page.readTimeout = TIMEOUT_MS
            page.setRequestProperty("User-Agent", BROWSER_USER_AGENT)
            page.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (page.responseCode != HttpURLConnection.HTTP_OK) return null
            val cookies = page.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
            page.inputStream.bufferedReader().use { it.readText() } to cookies
        } finally {
            page.disconnect()
        }
        if (cookies.none { it.startsWith("tt_chain_token=") }) return null

        val json = DATA.firstGroup(html) ?: return null
        val detail = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail") ?: return null
        if (detail.optInt("statusCode", -1) != 0) return null
        val video = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")?.optJSONObject("video") ?: return null

        // The highest bitrate H.264 file (H.265 doesn't decode on every phone), else the default one.
        var url: String? = null
        var width = video.optInt("width")
        var height = video.optInt("height")
        var bestBitrate = -1
        val bitrates = video.optJSONArray("bitrateInfo")
        if (bitrates != null) {
            for (index in 0 until bitrates.length()) {
                val info = bitrates.optJSONObject(index) ?: continue
                if (info.optString("CodecType") != "h264") continue
                val address = info.optJSONObject("PlayAddr") ?: continue
                val first = address.optJSONArray("UrlList")?.optString(0).orEmpty()
                val bitrate = info.optInt("Bitrate")
                if (Uri.parse(first).scheme != "https" || bitrate <= bestBitrate) continue
                url = first
                bestBitrate = bitrate
                if (address.optInt("Width") > 0) width = address.optInt("Width")
                if (address.optInt("Height") > 0) height = address.optInt("Height")
            }
        }
        if (url == null) url = video.optString("playAddr").takeIf { Uri.parse(it).scheme == "https" }
        val source = url ?: return null

        val headers = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Referer" to "https://www.tiktok.com/",
            "Cookie" to cookies.joinToString("; "),
        )
        val proxied = server.registerProxy(source, headers, SystemClock.elapsedRealtime() + LIFETIME_MS)
        DebugLog.log("tiktok: $id resolved, ${width}x$height, ${if (bestBitrate >= 0) "h264" else "default"} file")
        return ResolvedMedia(proxied, width, height, audioOnly = false) to LIFETIME_MS
    }

    private companion object {
        /** TikTok's file URLs expire after a few hours; re-resolve well before. */
        const val LIFETIME_MS = 30L * 60L * 1_000L
        val SHORT_HOSTS = setOf("vm.tiktok.com", "vt.tiktok.com")
        val SHORT_PATH: Pattern = Pattern.compile("^/t/[A-Za-z0-9]+/?$")
        val VIDEO_PATH: Pattern = Pattern.compile("/(?:video|v|embed/v2|embed|player/v1)/([0-9]{6,})")
        val DATA: Pattern = Pattern.compile(
            "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">(.+?)</script>",
            Pattern.DOTALL,
        )
    }
}
