package com.github.yutaplug.playembeds

import android.net.Uri
import com.aliucord.Http
import org.json.JSONObject
import java.util.Locale

/**
 * Resolves a tweet's mp4 through the FxTwitter API, for embeds whose video Discord
 * can't play: FxEmbed links without video metadata, and X embeds whose video is
 * X's HTML player page (`twitter.com/i/videos/tweet/{id}`) with no proxy URL.
 */
internal class FxVideoResolver : CachedResolver<String>() {
    override fun key(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.path.orEmpty()
        if (host in X_HOSTS) {
            val match = PLAYER_PATH.matchEntire(path) ?: return null
            return "${match.groupValues[1]}:1"
        }
        if (host !in FX_HOSTS) return null
        val match = STATUS_PATH.matchEntire(path) ?: return null
        val index = match.groupValues[2].takeIf { it.isNotEmpty() } ?: "1"
        return "${match.groupValues[1]}:$index"
    }

    override fun load(key: String): Pair<String, Long>? {
        val url = Http.Request("https://api.fxtwitter.com/status/${key.substringBefore(':')}").use { request ->
            request.setRequestTimeout(TIMEOUT_MS)
            request.setHeader("User-Agent", "Aliucord-PlayEmbeds")
            request.execute().use { response ->
                if (!response.ok()) return null
                val data = JSONObject(response.text())
                if (data.optInt("code") != 200) return null
                val videos = data.optJSONObject("tweet")?.optJSONObject("media")?.optJSONArray("videos")
                val media = videos?.optJSONObject(key.substringAfter(':').toInt() - 1) ?: return null
                val source = Uri.parse(media.optString("url"))
                val type = media.optString("type")
                if (type == "gif") return GIF to 600_000L
                if (type != "video" || source.scheme != "https" ||
                    source.host != "video.twimg.com" || !source.path.orEmpty().endsWith(".mp4")
                ) {
                    return null
                }
                source.toString()
            }
        }
        return url to 600_000L
    }

    companion object {
        /**
         * Cached for a tweet whose media is a GIF (FxTwitter's type "gif", an mp4 too).
         * Those aren't played: Discord opens them as before, in its fullscreen viewer.
         */
        const val GIF = "gif"

        fun isVideo(value: String?): Boolean = value != null && value != GIF

        private val FX_HOSTS = setOf("fxtwitter.com", "www.fxtwitter.com", "fixupx.com", "www.fixupx.com")
        private val X_HOSTS = setOf("twitter.com", "www.twitter.com", "mobile.twitter.com", "x.com", "www.x.com")
        private val STATUS_PATH = Regex("^/[^/]+/status/([0-9]+)(?:/video/([1-9][0-9]*))?/?$")
        private val PLAYER_PATH = Regex("^/i/videos/(?:tweet/)?([0-9]+)/?$")
    }
}
