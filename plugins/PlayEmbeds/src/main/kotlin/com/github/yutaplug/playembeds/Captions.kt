package com.github.yutaplug.playembeds

import android.net.Uri
import com.aliucord.Http
import org.json.JSONObject

/** A YouTube caption track from the player API's `captions.playerCaptionsTracklistRenderer.captionTracks`. */
internal class CaptionTrack(val name: String, val languageCode: String, val baseUrl: String, val auto: Boolean)

/** One timed caption line or block, in milliseconds of the video. */
internal class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

internal object Captions {
    private const val TIMEOUT_MS = 15_000
    private const val YOUTUBE = "https://www.youtube.com"

    /** The caption tracks listed in a player API answer's `captions` object, or none. */
    fun tracks(captions: JSONObject?): List<CaptionTrack> {
        val list = captions?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
            ?: return emptyList()
        val tracks = ArrayList<CaptionTrack>()
        for (index in 0 until list.length()) {
            val track = list.optJSONObject(index) ?: continue
            val baseUrl = track.optString("baseUrl")
            if (baseUrl.isEmpty()) continue
            val language = track.optString("languageCode")
            val nameObject = track.optJSONObject("name")
            val name = nameObject?.optString("simpleText")?.takeIf { it.isNotEmpty() }
                ?: nameObject?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: language
            tracks.add(CaptionTrack(name, language, baseUrl, track.optString("kind") == "asr"))
        }
        // Written captions before auto-generated ones, like YouTube's own menu.
        return tracks.sortedBy { if (it.auto) 1 else 0 }
    }

    /** Downloads and parses [track]; runs on the calling (background) thread. Null when it fails. */
    fun load(track: CaptionTrack): List<CaptionCue>? = try {
        val text = Http.Request(json3Url(track.baseUrl)).use { request ->
            request.setRequestTimeout(TIMEOUT_MS)
            request.execute().use { response ->
                if (response.ok()) {
                    response.text()
                } else {
                    DebugLog.log("captions: ${track.languageCode} download: HTTP ${response.statusCode}")
                    null
                }
            }
        }
        val cues = text?.takeIf { it.trim().isNotEmpty() }?.let(::parseJson3)
        DebugLog.log("captions: ${track.name} [${track.languageCode}]: ${text?.length ?: 0} chars, ${cues?.size ?: 0} lines")
        cues
    } catch (e: Throwable) {
        DebugLog.log("captions: couldn't load ${track.languageCode}: $e")
        null
    }

    /** [baseUrl] asking for YouTube's json3 caption format instead of its default XML. */
    private fun json3Url(baseUrl: String): String {
        val absolute = if (baseUrl.startsWith("/")) YOUTUBE + baseUrl else baseUrl
        val uri = Uri.parse(absolute)
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "fmt") continue
            for (value in uri.getQueryParameters(name)) builder.appendQueryParameter(name, value)
        }
        return builder.appendQueryParameter("fmt", "json3").build().toString()
    }

    /**
     * json3 is `{"events": [{"tStartMs", "dDurationMs", "segs": [{"utf8"}]}]}`.
     * Auto-generated tracks add word timings within an event's segments and
     * newline-only events for line breaks; those are skipped.
     */
    private fun parseJson3(text: String): List<CaptionCue> {
        val events = JSONObject(text).optJSONArray("events") ?: return emptyList()
        val cues = ArrayList<CaptionCue>()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val segments = event.optJSONArray("segs") ?: continue
            val builder = StringBuilder()
            for (segment in 0 until segments.length()) builder.append(segments.optJSONObject(segment)?.optString("utf8").orEmpty())
            val line = builder.toString().trim()
            if (line.isEmpty()) continue
            val start = event.optLong("tStartMs", 0L)
            val duration = event.optLong("dDurationMs", 0L)
            if (duration <= 0L) continue
            cues.add(CaptionCue(start, start + duration, line))
        }
        return cues.sortedBy { it.startMs }
    }

    /** The lines showing at [positionMs]: every cue that covers it, the latest last. */
    fun textAt(cues: List<CaptionCue>, positionMs: Long): String {
        var lines: ArrayList<String>? = null
        for (cue in cues) {
            if (cue.startMs > positionMs) break
            if (positionMs < cue.endMs) (lines ?: ArrayList<String>().also { lines = it }).add(cue.text)
        }
        val shown = lines ?: return ""
        // Auto-generated captions overlap as they roll; YouTube shows at most two lines.
        return shown.takeLast(MAX_LINES).joinToString("\n")
    }

    private const val MAX_LINES = 2
}
