package com.github.yutaplug.playembeds

import android.net.Uri
import com.aliucord.Http
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * Resolves Twitch clips through Twitch's public GraphQL API (the web player's own
 * client ID). Clips are plain MP4 files that need the clip's signed access token;
 * live streams and VODs are HLS only, which Discord's progressive player can't play.
 */
internal class TwitchClipResolver : CachedResolver<ResolvedMedia>() {
    override fun key(url: String): String? = clipSlug(url)

    override fun load(key: String): Pair<ResolvedMedia, Long>? {
        val body = JSONObject()
            .put("query", CLIP_QUERY)
            .put("variables", JSONObject().put("slug", key))
            .toString()
        val text = Http.Request("https://gql.twitch.tv/gql", "POST").use { request ->
            request.setRequestTimeout(TIMEOUT_MS)
            request.setHeader("Client-Id", CLIENT_ID)
            request.setHeader("Content-Type", "application/json")
            request.setHeader("User-Agent", BROWSER_USER_AGENT)
            request.executeWithBody(body).use { response -> if (response.ok()) response.text() else null }
        } ?: return null
        val clip = JSONObject(text).optJSONObject("data")?.optJSONObject("clip") ?: return null
        val token = clip.optJSONObject("playbackAccessToken") ?: return null
        val signature = token.optString("signature")
        val value = token.optString("value")
        if (signature.isEmpty() || value.isEmpty()) return null

        // The best quality up to 1080p; qualities are heights ("1080", "720", ...).
        val qualities = clip.optJSONArray("videoQualities") ?: return null
        var bestUrl: String? = null
        var bestHeight = 0
        for (index in 0 until qualities.length()) {
            val quality = qualities.optJSONObject(index) ?: continue
            val height = quality.optString("quality").toIntOrNull() ?: continue
            val source = quality.optString("sourceURL")
            if (Uri.parse(source).scheme != "https" || height > MAX_HEIGHT || height <= bestHeight) continue
            bestUrl = source
            bestHeight = height
        }
        val source = bestUrl ?: return null
        val url = Uri.parse(source).buildUpon()
            .appendQueryParameter("sig", signature)
            .appendQueryParameter("token", value)
            .build()
            .toString()
        // The token lasts about a day; re-resolve well before that.
        return ResolvedMedia(url, 0, bestHeight, audioOnly = false) to 3_600_000L
    }

    companion object {
        private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        private const val MAX_HEIGHT = 1080
        private const val CLIP_QUERY =
            "query(\$slug: ID!) { clip(slug: \$slug) { videoQualities { quality sourceURL } " +
                "playbackAccessToken(params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
                "playerType: \"site\"}) { signature value } } }"
        private val SLUG: Pattern = Pattern.compile("^[A-Za-z0-9_-]+$")

        /**
         * The clip's slug for clips.twitch.tv/{slug}, clips.twitch.tv/embed?clip={slug}
         * and (www., m.)twitch.tv/{channel}/clip/{slug}; null for anything else.
         */
        fun clipSlug(url: String?): String? {
            url ?: return null
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase(Locale.ROOT) != "https" && uri.scheme?.lowercase(Locale.ROOT) != "http") return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val segments = uri.pathSegments.filter { it.isNotEmpty() }
            val slug = when {
                host == "clips.twitch.tv" ->
                    if (segments.firstOrNull() == "embed") uri.getQueryParameter("clip") else segments.firstOrNull()
                EmbedUrls.isHost(host, "twitch.tv") ->
                    segments.getOrNull(2)?.takeIf { segments.size == 3 && segments[1] == "clip" }
                else -> null
            } ?: return null
            return slug.takeIf { SLUG.matcher(it).matches() }
        }
    }
}
