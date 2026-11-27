package com.github.yutaplug.fixes.voice

internal object Bytes {
    fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

    fun u16(b: ByteArray, o: Int) = (u8(b, o) shl 8) or u8(b, o + 1)

    fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun beU64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (56 - 8 * i)).toByte()
        return out
    }

    fun leU64(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (v ushr (8 * i)).toByte()
        return out
    }

    fun fromBeU64(b: ByteArray): Long {
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    fun ctEquals(a: ByteArray, ao: Int, b: ByteArray, bo: Int, len: Int): Boolean {
        var d = 0
        for (i in 0 until len) d = d or (a[ao + i].toInt() xor b[bo + i].toInt())
        return d == 0
    }

    // ULEB128 (DAVE frame footer)

    fun leb128Size(value: Long): Int {
        var v = value
        var n = 1
        while (v >= 0x80) {
            v = v ushr 7
            n++
        }
        return n
    }

    fun writeLeb128(value: Long, out: ByteArray, off: Int): Int {
        var v = value
        var o = off
        while (v >= 0x80) {
            out[o++] = (0x80 or (v and 0x7F).toInt()).toByte()
            v = v ushr 7
        }
        out[o++] = v.toByte()
        return o - off
    }

    /** Reads a ULEB128 value at [pos[0]] no further than [end], advancing pos. Returns -1 when malformed. */
    fun readLeb128(b: ByteArray, pos: IntArray, end: Int): Long {
        var v = 0L
        var shift = 0
        while (pos[0] < end) {
            val x = u8(b, pos[0]++)
            if (shift >= 63) return -1
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return v
            shift += 7
        }
        return -1
    }

    // MLS variable-size vector length headers (RFC 9420 section 2.1.2)

    /** Reads an MLS varint at [pos[0]], advancing pos. */
    fun readMlsVarint(b: ByteArray, pos: IntArray): Int {
        val first = u8(b, pos[0])
        return when (first ushr 6) {
            0 -> {
                pos[0] += 1
                first
            }
            1 -> {
                val v = u16(b, pos[0]) and 0x3FFF
                pos[0] += 2
                v
            }
            2 -> {
                val v = (u32(b, pos[0]) and 0x3FFFFFFF).toInt()
                pos[0] += 4
                v
            }
            else -> throw IllegalArgumentException("invalid MLS varint")
        }
    }

    fun hex(b: ByteArray, max: Int = 32): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(b.size, max)) {
            val x = u8(b, i)
            sb.append(HEX[x ushr 4]).append(HEX[x and 15])
        }
        if (b.size > max) sb.append("…")
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
