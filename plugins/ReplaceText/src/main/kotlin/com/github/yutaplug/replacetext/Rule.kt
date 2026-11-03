package com.github.yutaplug.replacetext

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Matcher
import java.util.regex.Pattern

class Rule(
    var find: String = "",
    var replace: String = "",
    var regex: Boolean = false,
    var caseSensitive: Boolean = false,
    var unsent: Boolean = true,
    var sent: Boolean = false,
    var embeds: Boolean = false,
) {
    private var cachedKey: String? = null

    private var cachedPattern: Pattern? = null

    fun copy() = Rule(find, replace, regex, caseSensitive, unsent, sent, embeds)

    /** The compiled pattern, or null if [find] is empty or not a valid regex. */
    fun pattern(): Pattern? {
        if (find.isEmpty()) return null
        val key = "$regex|$caseSensitive|$find"
        if (key == cachedKey) return cachedPattern
        cachedKey = key
        cachedPattern = try {
            compile()
        } catch (e: Throwable) {
            null
        }
        return cachedPattern
    }

    /** Throws if [find] is not a valid regex. */
    fun compile(): Pattern {
        var flags = if (regex) 0 else Pattern.LITERAL
        if (!caseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(find, flags)
    }

    fun apply(text: String): String {
        val pattern = pattern() ?: return text
        return try {
            // In regex mode the replacement may use $1 or ${name} groups; otherwise it is taken literally.
            pattern.matcher(text).replaceAll(if (regex) replace else Matcher.quoteReplacement(replace))
        } catch (e: Throwable) {
            text
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("find", find)
        .put("replace", replace)
        .put("regex", regex)
        .put("caseSensitive", caseSensitive)
        .put("unsent", unsent)
        .put("sent", sent)
        .put("embeds", embeds)

    companion object {
        fun fromJson(json: JSONObject) = Rule(
            json.optString("find"),
            json.optString("replace"),
            json.optBoolean("regex"),
            json.optBoolean("caseSensitive"),
            json.optBoolean("unsent", true),
            json.optBoolean("sent"),
            json.optBoolean("embeds"),
        )

        fun listFromJson(text: String): MutableList<Rule> {
            val array = try {
                JSONArray(text)
            } catch (e: Throwable) {
                return mutableListOf()
            }
            val rules = mutableListOf<Rule>()
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { rules.add(fromJson(it)) }
            return rules
        }

        fun listToJson(rules: List<Rule>) = JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()
    }
}
