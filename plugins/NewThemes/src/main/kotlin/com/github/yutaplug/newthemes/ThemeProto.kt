package com.github.yutaplug.newthemes

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire handling that preserves fields we do not understand. */
internal object ThemeProto {
    private data class Field(val number: Int, val wire: Int, val value: ByteArray, val raw: ByteArray)

    fun theme(settings: ByteArray): Int = scalar(message(settings, 13) ?: byteArrayOf(), 1).toInt()

    fun dataVersion(settings: ByteArray): Long = scalar(message(settings, 1) ?: byteArrayOf(), 3)

    /** appearance.client_theme_settings.background_gradient_preset_id, or null when unset. */
    fun preset(settings: ByteArray): Int? {
        val client = message(message(settings, 13) ?: return null, 3) ?: return null
        val wrapper = message(client, 2) ?: return null
        return scalar(wrapper, 1).toInt()
    }

    fun patch(settings: ByteArray, theme: Int, preset: Int?): ByteArray {
        require(theme in 1..4)
        // A top-level section is replaced in full. Preserve unknown appearance
        // fields, including density and developer mode.
        var appearance = replace(message(settings, 13) ?: byteArrayOf(), 1, varint(8) + varint(theme.toLong()))
        val client = message(appearance, 3)
        // Like the official clients, a preset replaces any custom gradient
        // (field 4), and choosing a standard theme clears both.
        val stripped = remove(remove(client ?: byteArrayOf(), 2), 4)
        val updated = if (preset != null) {
            // UInt32Value omits its value field when it is zero (Mint Apple).
            stripped + bytes(2, if (preset == 0) byteArrayOf() else varint(8) + varint(preset.toLong()))
        } else stripped
        appearance = if (updated.isEmpty()) remove(appearance, 3) else replace(appearance, 3, bytes(3, updated))
        return bytes(13, appearance)
    }

    private fun remove(data: ByteArray, number: Int): ByteArray = replace(data, number, byteArrayOf())

    private fun message(data: ByteArray, number: Int): ByteArray? =
        fields(data).lastOrNull { it.number == number && it.wire == 2 }?.value

    private fun scalar(data: ByteArray, number: Int): Long =
        fields(data).lastOrNull { it.number == number && it.wire == 0 }?.value?.let {
            var result = 0L
            it.forEachIndexed { index, byte -> result = result or ((byte.toLong() and 127) shl (index * 7)) }
            result
        } ?: 0L

    private fun replace(data: ByteArray, number: Int, replacement: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            fields(data).filter { it.number != number }.forEach { write(it.raw) }
            write(replacement)
        }.toByteArray()

    private fun bytes(number: Int, value: ByteArray): ByteArray =
        varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value

    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var remaining = value
        do {
            val next = (remaining and 127).toInt()
            remaining = remaining ushr 7
            write(next or if (remaining != 0L) 128 else 0)
        } while (remaining != 0L)
    }.toByteArray()

    private fun fields(data: ByteArray): List<Field> {
        var position = 0
        fun readVarint(): Long {
            var value = 0L
            // Discord's Kotlin runtime renames IntProgression.Companion. Avoid
            // the stdlib step() call, which still references the original field.
            var shift = 0
            while (shift <= 63) {
                require(position < data.size) { "Truncated protobuf varint" }
                val byte = data[position++].toInt() and 255
                require(shift != 63 || byte <= 1) { "Invalid protobuf varint" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
                shift += 7
            }
            error("Invalid protobuf varint")
        }
        val result = ArrayList<Field>()
        while (position < data.size) {
            val start = position
            val tag = readVarint()
            require(tag >= 8 && tag <= 0xffffffffL) { "Invalid protobuf tag" }
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            val valueStart: Int
            when (wire) {
                0 -> {
                    valueStart = position
                    readVarint()
                }
                1, 5 -> {
                    valueStart = position
                    position += if (wire == 1) 8 else 4
                }
                2 -> {
                    val length = readVarint()
                    require(length >= 0 && length <= (data.size - position).toLong()) { "Invalid protobuf length" }
                    valueStart = position
                    position += length.toInt()
                }
                else -> error("Unsupported protobuf wire type: $wire")
            }
            require(position <= data.size) { "Truncated protobuf field" }
            result.add(Field(number, wire, data.copyOfRange(valueStart, position), data.copyOfRange(start, position)))
        }
        return result
    }
}
