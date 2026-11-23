package com.github.yutaplug.playembeds

import android.net.Uri
import com.aliucord.Http
import org.json.JSONObject
import java.util.Locale

/** Resolves missing FxEmbed video metadata without changing Discord's message model. */
internal class FxVideoResolver : CachedResolver<String>() {
    override fun key(url: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in FX_HOSTS) return null
        val match = STATUS_PATH.matchEntire(uri.path.orEmpty()) ?: return null
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
                if (media.optString("type") != "video" || source.scheme != "https" ||
                    source.host != "video.twimg.com" || !source.path.orEmpty().endsWith(".mp4")
                ) {
                    return null
                }
                source.toString()
            }
        }
        return url to 600_000L
    }

    private companion object {
        val FX_HOSTS = setOf("fxtwitter.com", "www.fxtwitter.com", "fixupx.com", "www.fixupx.com")
        val STATUS_PATH = Regex("^/[^/]+/status/([0-9]+)(?:/video/([1-9][0-9]*))?/?$")
    }
}
