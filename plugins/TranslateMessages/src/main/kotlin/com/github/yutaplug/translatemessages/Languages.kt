package com.github.yutaplug.translatemessages

import java.util.Locale

object Languages {
    /** Language codes shared by Google Translate and LibreTranslate, with their English names. */
    val all: List<Pair<String, String>> = listOf(
        "af" to "Afrikaans",
        "ar" to "Arabic",
        "bg" to "Bulgarian",
        "bn" to "Bengali",
        "ca" to "Catalan",
        "cs" to "Czech",
        "da" to "Danish",
        "de" to "German",
        "el" to "Greek",
        "en" to "English",
        "es" to "Spanish",
        "et" to "Estonian",
        "fa" to "Persian",
        "fi" to "Finnish",
        "fil" to "Filipino",
        "fr" to "French",
        "ga" to "Irish",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hr" to "Croatian",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ka" to "Georgian",
        "kk" to "Kazakh",
        "ko" to "Korean",
        "lt" to "Lithuanian",
        "lv" to "Latvian",
        "ms" to "Malay",
        "nl" to "Dutch",
        "no" to "Norwegian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "sq" to "Albanian",
        "sr" to "Serbian",
        "sv" to "Swedish",
        "sw" to "Swahili",
        "ta" to "Tamil",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "uz" to "Uzbek",
        "vi" to "Vietnamese",
        "zh-CN" to "Chinese (Simplified)",
        "zh-TW" to "Chinese (Traditional)",
    )

    fun name(code: String): String = all.firstOrNull { it.first == code }?.second ?: code

    /** The device language when it is supported, otherwise English. */
    fun deviceDefault(): String {
        val locale = Locale.getDefault()
        val code = when (locale.language) {
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn" -> "no"
            "tl" -> "fil"
            "zh" -> if (locale.country == "TW" || locale.country == "HK" || locale.script == "Hant") "zh-TW" else "zh-CN"
            else -> locale.language
        }
        return if (all.any { it.first == code }) code else "en"
    }

    /** Whether a detected source language is the same language as [target]. */
    fun same(source: String?, target: String): Boolean {
        if (source == null) return false
        return source.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true) &&
            (!target.startsWith("zh") || source.equals(target, ignoreCase = true) || !source.contains('-'))
    }
}
