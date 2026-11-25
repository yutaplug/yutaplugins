package com.github.yutaplug.profileeffects

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal
import b.i.d.g as JsonArray
import b.i.d.k as JsonPrimitive

/** Converts Gson trees to plain maps/lists, keeping lazy numbers so snowflakes never pass through Double. */
internal object Json {
    fun value(element: JsonElement?): Any? = when (element) {
        is JsonPrimitive -> {
            element.a
        }

        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>()
            for (entry in element.j()) map[entry.key] = value(entry.value)
            map
        }

        is JsonArray -> {
            val list = ArrayList<Any?>()
            for (item in element) list.add(value(item))
            list
        }

        else -> {
            null
        }
    }

    fun long(raw: Any?): Long? = try {
        BigDecimal(raw?.toString() ?: "").longValueExact()
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /** Discord colors are 24-bit RGB integers; return them opaque. */
    fun color(raw: Any?): Int? {
        val value = long(raw) ?: return null
        if (value < 0 || value > 0xFFFFFFL) return null
        return (value.toInt() and 0xFFFFFF) or (0xFF shl 24)
    }

    fun colors(raw: Any?): IntArray? {
        val list = raw as? List<*> ?: return null
        val result = ArrayList<Int>()
        for (item in list) color(item)?.let { result.add(it) }
        if (result.isEmpty()) return null
        val array = IntArray(result.size)
        var i = 0
        while (i < array.size) {
            array[i] = result[i]
            i++
        }
        return array
    }
}
