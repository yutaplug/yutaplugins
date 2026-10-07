package com.github.yutaplug.translatemessages

import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Translates message text while leaving mentions, emojis, timestamps, links and code untouched. */
object Translator {
    class Result(val text: String, val sourceLanguage: String?)

    // Discord tokens and code that translation services would mangle.
    private val protectedPattern = Regex(
        "```[\\s\\S]*?```|`[^`\\n]+`|<a?:\\w+:\\d+>|<(?:@[!&]?|#)\\d+>|<t:-?\\d+(?::[tTdDfFR])?>|https?://\\S+",
    )

    /**
     * @param apiUrl optional LibreTranslate-compatible endpoint. Google Translate is used when empty.
     */
    fun translate(text: String, target: String, apiUrl: String, apiKey: String): Result {
        var source: String? = null
        val out = StringBuilder()
        var last = 0
        fun translateSegment(segment: String) {
            if (segment.none { Character.isLetter(it) }) {
                out.append(segment)
                return
            }
            // Services trim whitespace, so keep the segment's own leading and trailing whitespace.
            val start = segment.indexOfFirst { !Character.isWhitespace(it) }
            val end = segment.indexOfLast { !Character.isWhitespace(it) } + 1
            val result = request(segment.substring(start, end), target, apiUrl, apiKey)
            if (source == null) source = result.sourceLanguage
            out.append(segment, 0, start).append(result.text).append(segment, end, segment.length)
        }
        for (match in protectedPattern.findAll(text, 0)) {
            translateSegment(text.substring(last, match.range.first))
            out.append(match.value)
            last = match.range.last + 1
        }
        translateSegment(text.substring(last))
        return Result(out.toString(), source)
    }

    private fun request(text: String, target: String, apiUrl: String, apiKey: String): Result =
        if (apiUrl.isEmpty()) google(text, target) else libreTranslate(text, target, apiUrl, apiKey)

    private fun google(text: String, target: String): Result {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t" +
            "&tl=" + URLEncoder.encode(target, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8")
        val body = Http.Request(url).execute().use { it.assertOk(); it.text() }
        val json = JSONArray(body)
        val sentences = json.getJSONArray(0)
        val translated = StringBuilder()
        for (i in 0 until sentences.length()) {
            translated.append(sentences.getJSONArray(i).optString(0))
        }
        return Result(translated.toString(), json.optString(2).takeIf { it.isNotEmpty() && it != "null" })
    }

    private fun libreTranslate(text: String, target: String, apiUrl: String, apiKey: String): Result {
        val payload = mutableMapOf<String, Any>(
            "q" to text,
            "source" to "auto",
            "target" to target,
            "format" to "text",
        )
        if (apiKey.isNotEmpty()) payload["api_key"] = apiKey
        val body = Http.Request(apiUrl, "POST").executeWithJson(payload).use { it.assertOk(); it.text() }
        val json = JSONObject(body)
        return Result(
            json.getString("translatedText"),
            json.optJSONObject("detectedLanguage")?.optString("language")?.takeIf { it.isNotEmpty() },
        )
    }
}
