package com.github.yutaplug.translatemessages

import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Translates message text while leaving mentions, emojis, timestamps, links and code untouched. */
object Translator {
    class Result(val text: String, val sourceLanguage: String?)

    /** A failure with a short reason worth showing to the user. */
    class TranslationException(message: String) : Exception(message)

    sealed class Service {
        object Google : Service()

        class DeepL(val key: String) : Service()

        /** Any LibreTranslate-compatible endpoint; the key is optional. */
        class Libre(val url: String, val key: String) : Service()
    }

    // Discord tokens and code that translation services would mangle.
    private val protectedPattern = Regex(
        "```[\\s\\S]*?```|`[^`\\n]+`|<a?:\\w+:\\d+>|<(?:@[!&]?|#)\\d+>|<t:-?\\d+(?::[tTdDfFR])?>|https?://\\S+",
    )

    fun translate(text: String, target: String, service: Service): Result {
        when (service) {
            is Service.DeepL -> if (service.key.isEmpty()) throw TranslationException("add your DeepL API key in settings")
            is Service.Libre -> if (service.url.isEmpty()) throw TranslationException("add an API URL in settings")
            Service.Google -> {}
        }
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
            val result = request(segment.substring(start, end), target, service)
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

    private fun request(text: String, target: String, service: Service): Result =
        when (service) {
            Service.Google -> google(text, target)
            is Service.DeepL -> deepL(text, target, service.key)
            is Service.Libre -> libreTranslate(text, target, service.url, service.key)
        }

    private fun deepL(text: String, target: String, key: String): Result {
        // Free-plan keys end in ":fx" and use a separate host. Try the other host if the key is rejected,
        // in case a plan uses a host its key suffix does not suggest.
        val hosts = if (key.endsWith(":fx")) {
            listOf("https://api-free.deepl.com", "https://api.deepl.com")
        } else {
            listOf("https://api.deepl.com", "https://api-free.deepl.com")
        }
        val payload = mapOf("text" to listOf(text), "target_lang" to Languages.deepLTarget(target))
        var status = 0
        for (host in hosts) {
            Http.Request("$host/v2/translate", "POST")
                .setHeader("Authorization", "DeepL-Auth-Key $key")
                .executeWithJson(payload)
                .use { response ->
                    status = response.statusCode
                    if (response.ok()) {
                        val translation = JSONObject(response.text()).getJSONArray("translations").getJSONObject(0)
                        return Result(
                            translation.getString("text"),
                            translation.optString("detected_source_language").takeIf { it.isNotEmpty() },
                        )
                    }
                }
            if (status != 403) break
        }
        throw TranslationException(
            when (status) {
                403 -> "DeepL rejected the API key"
                456 -> "DeepL character limit reached"
                429 -> "too many requests, try again later"
                400 -> "DeepL doesn't support ${Languages.name(target)}"
                else -> "DeepL error $status"
            },
        )
    }

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
