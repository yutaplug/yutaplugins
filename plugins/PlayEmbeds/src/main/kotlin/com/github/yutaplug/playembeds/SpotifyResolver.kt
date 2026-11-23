package com.github.yutaplug.playembeds

import android.net.Uri
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * Spotify's audio is DRM-protected, so only the 30-second MP3 previews that its
 * embed page lists can play natively. Albums and playlists play their first
 * track's preview; artists and shows have no single preview and stay external.
 */
internal class SpotifyResolver : CachedResolver<ResolvedMedia>() {
    override fun key(url: String): String? {
        val uri = Uri.parse(url)
        if (EmbedUrls.isHost(uri.host, "spotify.link")) return "link:$url"
        if (!EmbedUrls.isHost(uri.host, "open.spotify.com")) return null
        return contentKey(url)
    }

    override fun load(key: String): Pair<ResolvedMedia, Long>? {
        val content = if (key.startsWith("link:")) {
            contentKey(finalUrl(key.removePrefix("link:"))) ?: return null
        } else {
            key
        }
        val type = content.substringBefore('/')
        if (type !in PLAYABLE_TYPES) return null

        val html = getText("https://open.spotify.com/embed/$content") ?: return null
        val preview = previewFromNextData(html)
            ?: PREVIEW_URL.firstGroup(html, 0)
            ?: return null
        val uri = Uri.parse(preview)
        if (uri.scheme != "https" || !(EmbedUrls.isHost(uri.host, "scdn.co") || EmbedUrls.isHost(uri.host, "spotifycdn.com"))) {
            return null
        }
        return ResolvedMedia(preview, 0, 0, audioOnly = true) to 3_600_000L
    }

    private fun previewFromNextData(html: String): String? {
        val json = NEXT_DATA.firstGroup(html) ?: return null
        val entity = try {
            JSONObject(json)
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
        } catch (_: Throwable) {
            null
        } ?: return null
        entity.optJSONObject("audioPreview")?.optString("url")?.takeIf { it.isNotEmpty() }?.let { return it }
        val tracks = entity.optJSONArray("trackList") ?: return null
        for (index in 0 until tracks.length()) {
            val url = tracks.optJSONObject(index)?.optJSONObject("audioPreview")?.optString("url")
            if (!url.isNullOrEmpty()) return url
        }
        return null
    }

    /** "type/id" for an open.spotify.com URL (with or without /embed or /intl-xx). */
    private fun contentKey(url: String): String? {
        val uri = Uri.parse(url)
        if (!EmbedUrls.isHost(uri.host, "open.spotify.com")) return null
        val segments = uri.pathSegments.filter {
            it.isNotEmpty() && !it.startsWith("intl-", ignoreCase = true) && !it.equals("embed", ignoreCase = true)
        }
        val type = segments.getOrNull(0)?.lowercase(Locale.ROOT) ?: return null
        val id = segments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return "$type/$id"
    }

    private companion object {
        val PLAYABLE_TYPES = setOf("track", "episode", "album", "playlist")
        val NEXT_DATA: Pattern = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.+?)</script>",
            Pattern.DOTALL,
        )
        val PREVIEW_URL: Pattern = Pattern.compile("https://p\\.scdn\\.co/mp3-preview/[0-9A-Za-z]+(\\?[^\"\\s<\\\\]*)?")
    }
}
