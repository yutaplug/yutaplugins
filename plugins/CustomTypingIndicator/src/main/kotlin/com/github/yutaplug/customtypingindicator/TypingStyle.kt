package com.github.yutaplug.customtypingindicator

import org.json.JSONArray
import org.json.JSONObject

/** One emoji of a typing indicator: either a unicode emoji or a custom emoji id. */
data class TypingEmoji(val unicode: String?, val customId: Long?, val animated: Boolean) {
    fun toJson(): JSONObject = if (customId != null) {
        JSONObject().put("custom_emoji_id", customId.toString())
    } else {
        JSONObject().put("unicode_emoji", unicode ?: "")
    }
}

/** Discord's `typing_indicator_style`, as sent on the user object and on TYPING_START. */
data class TypingStyle(val emojis: List<TypingEmoji>, val animation: Int, val suggestion: Int) {
    fun toJson(): JSONObject {
        val array = JSONArray()
        for (emoji in emojis) array.put(emoji.toJson())
        return JSONObject()
            .put("emojis", array)
            .put("animation", animation)
            .put("typing_suggestion", suggestion)
    }

    companion object {
        const val MAX_EMOJIS = 3

        const val ANIMATION_UNSPECIFIED = 0
        const val ANIMATION_PULSE = 1
        const val ANIMATION_RING = 2
        const val ANIMATION_WAVE = 3

        /** Animations offered in the editor, in Discord's enum order. */
        val ANIMATIONS = intArrayOf(ANIMATION_PULSE, ANIMATION_RING, ANIMATION_WAVE)

        /** Phrases for Discord's TypingSuggestion enum, indexed by its value. */
        val PHRASES = arrayOf(
            "is typing…",
            "is yapping…",
            "is venting…",
            "is oversharing…",
            "is barking…",
            "is babbling…",
            "is daydreaming…",
            "is meowing…",
        )

        fun animationLabel(animation: Int) = when (animation) {
            ANIMATION_PULSE -> "Pulse"
            ANIMATION_RING -> "Ring"
            else -> "Wave"
        }

        fun phrase(suggestion: Int) = PHRASES.getOrNull(suggestion) ?: PHRASES[0]

        /** Parses a `typing_indicator_style` value; null for a missing, null or empty style. */
        fun fromJson(obj: JSONObject?): TypingStyle? {
            if (obj == null) return null
            val emojis = mutableListOf<TypingEmoji>()
            val array = obj.optJSONArray("emojis")
            var index = 0
            while (array != null && index < array.length() && emojis.size < MAX_EMOJIS) {
                val emoji = array.optJSONObject(index++) ?: continue
                val id = if (emoji.isNull("custom_emoji_id")) null else emoji.optString("custom_emoji_id").toLongOrNull()
                val unicode = if (emoji.isNull("unicode_emoji")) null else emoji.optString("unicode_emoji")
                if (id != null) {
                    emojis += TypingEmoji(null, id, emoji.optBoolean("animated", false))
                } else if (unicode != null && unicode.isNotEmpty()) {
                    emojis += TypingEmoji(unicode, null, false)
                }
            }
            val animation = obj.optInt("animation", ANIMATION_UNSPECIFIED)
            val suggestion = obj.optInt("typing_suggestion", 0)
            if (emojis.isEmpty() && suggestion == 0) return null
            return TypingStyle(emojis, animation, suggestion)
        }

        /** Reads `typing_indicator_style` from a user object or TYPING_START payload. */
        fun fromParent(parent: JSONObject): TypingStyle? =
            if (parent.isNull("typing_indicator_style")) null else fromJson(parent.optJSONObject("typing_indicator_style"))
    }
}
