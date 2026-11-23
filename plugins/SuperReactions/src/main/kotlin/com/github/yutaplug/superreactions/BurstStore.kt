package com.github.yutaplug.superreactions

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One emoji's super reactions on a message. */
internal data class Burst(val count: Int, val me: Boolean, val color: Int?)

/**
 * Super reaction state per message. Discord 126.21's models drop burst fields,
 * so this is filled from the raw REST message payloads Discord already loads
 * and kept current from gateway reaction events.
 */
internal class BurstStore {
    private class Entry(val bursts: HashMap<String, Burst>, var complete: Boolean)

    private val messages = ConcurrentHashMap<Long, Entry>()

    /** True once an authoritative snapshot (REST payload) was seen for [messageId]. */
    fun hasSnapshot(messageId: Long): Boolean = messages[messageId]?.complete == true

    fun get(messageId: Long, key: String?): Burst? {
        val entry = messages[messageId] ?: return null
        val normalized = normalize(key) ?: return null
        return synchronized(entry) { entry.bursts[normalized] }
    }

    fun hasAny(messageId: Long): Boolean {
        val entry = messages[messageId] ?: return false
        return synchronized(entry) { entry.bursts.isNotEmpty() }
    }

    /** Ingests a REST message list; returns the ids whose burst state changed. */
    fun ingestMessages(messages: JSONArray): List<Long> {
        val changed = ArrayList<Long>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val id = message.optString("id").toLongOrNull() ?: continue
            if (replace(id, parseReactions(message))) changed.add(id)
        }
        return changed
    }

    /** Marks [messageId] as known with no super reactions (it wasn't returned by a lookup). */
    fun markEmpty(messageId: Long): Boolean = replace(messageId, emptyMap())

    private fun replace(messageId: Long, bursts: Map<String, Burst>): Boolean {
        val previous = messages.put(messageId, Entry(HashMap(bursts), complete = true))
        if (previous == null) return bursts.isNotEmpty()
        return synchronized(previous) { previous.bursts != bursts }
    }

    /** Applies one burst add/remove from the gateway or a local action. */
    fun apply(messageId: Long, key: String?, add: Boolean, byMe: Boolean, color: Int?) {
        val normalized = normalize(key) ?: return
        val entry = messages.getOrPut(messageId) { Entry(HashMap(), complete = false) }
        synchronized(entry) {
            val current = entry.bursts[normalized]
            val count = ((current?.count ?: 0) + if (add) 1 else -1).coerceAtLeast(0)
            val me = if (byMe) add else current?.me == true
            if (count == 0 && !me) {
                entry.bursts.remove(normalized)
            } else {
                entry.bursts[normalized] = Burst(count.coerceAtLeast(if (me) 1 else 0), me, color ?: current?.color)
            }
        }
    }

    /** Sets an emoji's color without changing its count. */
    fun recolor(messageId: Long, key: String?, color: Int?) {
        color ?: return
        val normalized = normalize(key) ?: return
        val entry = messages[messageId] ?: return
        synchronized(entry) { entry.bursts[normalized]?.let { entry.bursts[normalized] = it.copy(color = color) } }
    }

    fun clear(messageId: Long) {
        messages[messageId]?.let { entry -> synchronized(entry) { entry.bursts.clear() } }
    }

    fun clearEmoji(messageId: Long, key: String?) {
        val normalized = normalize(key) ?: return
        messages[messageId]?.let { entry -> synchronized(entry) { entry.bursts.remove(normalized) } }
    }

    fun clearAll() = messages.clear()

    companion object {
        /** Variation selectors differ between clients for the same unicode emoji. */
        fun normalize(key: String?): String? = key?.replace("️", "")

        private fun parseReactions(message: JSONObject): Map<String, Burst> {
            val reactions = message.optJSONArray("reactions") ?: return emptyMap()
            val result = HashMap<String, Burst>()
            for (index in 0 until reactions.length()) {
                val reaction = reactions.optJSONObject(index) ?: continue
                val emoji = reaction.optJSONObject("emoji") ?: continue
                val key = normalize(
                    emoji.optString("id").takeIf { it.isNotEmpty() && it != "null" } ?: emoji.optString("name"),
                ) ?: continue
                if (key.isEmpty()) continue
                val details = reaction.optJSONObject("count_details")
                val me = reaction.optBoolean("me_burst")
                var count = maxOf(details?.optInt("burst") ?: 0, reaction.optInt("burst_count"))
                if (me) count = maxOf(count, 1)
                if (count <= 0) continue
                result[key] = Burst(count, me, pickColor(reaction.optJSONArray("burst_colors")))
            }
            return result
        }

        /** Discord uses the most vivid of the first palette entries for a reaction's tint. */
        fun pickColor(colors: JSONArray?): Int? {
            colors ?: return null
            val values = ArrayList<String>()
            for (index in 0 until minOf(colors.length(), 3)) colors.optString(index).let { values.add(it) }
            return pickColor(values)
        }

        fun pickColor(colors: List<*>?): Int? {
            colors ?: return null
            var best: Int? = null
            var bestScore = -1f
            val hsv = FloatArray(3)
            for (value in colors.take(3)) {
                val color = try {
                    Color.parseColor(value?.toString() ?: continue)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                Color.colorToHSV(color, hsv)
                val score = hsv[1] + hsv[2]
                if (score > bestScore) {
                    bestScore = score
                    best = color or 0xFF000000.toInt()
                }
            }
            return best
        }
    }
}
