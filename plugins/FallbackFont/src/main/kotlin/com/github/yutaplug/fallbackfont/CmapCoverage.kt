package com.github.yutaplug.fallbackfont

import java.io.File
import java.io.RandomAccessFile

/**
 * The codepoints a TTF/OTF/TTC file maps, read from its `cmap` table. Paint.hasGlyph can't answer this
 * for a custom font because it also searches the system fallback chain.
 */
internal class CmapCoverage private constructor(private val ranges: IntArray) {
    /** Whether [codepoint] falls in a range the font maps. Ranges are sorted and inclusive. */
    operator fun contains(codepoint: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                codepoint < ranges[mid * 2] -> high = mid - 1
                codepoint > ranges[mid * 2 + 1] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        private const val TAG_TTCF = 0x74746366 // "ttcf"
        private const val TAG_CMAP = 0x636D6170 // "cmap"

        /** Returns null when the file has no readable format 4 or 12 cmap. */
        fun read(file: File): CmapCoverage? = try {
            RandomAccessFile(file, "r").use { read(it) }
        } catch (e: Exception) {
            null
        }

        private fun read(input: RandomAccessFile): CmapCoverage? {
            // Font collections point to their fonts; use the first one.
            var fontOffset = 0L
            input.seek(0)
            if (input.readInt() == TAG_TTCF) {
                input.seek(12)
                fontOffset = input.readInt().toLong() and 0xFFFFFFFFL
            }
            input.seek(fontOffset + 4)
            val numTables = input.readUnsignedShort()
            var cmap = -1L
            for (i in 0 until numTables) {
                input.seek(fontOffset + 12 + i * 16L)
                val tag = input.readInt()
                input.readInt() // checksum
                val offset = input.readInt().toLong() and 0xFFFFFFFFL
                if (tag == TAG_CMAP) cmap = offset
            }
            if (cmap < 0) return null

            input.seek(cmap + 2)
            val subtables = input.readUnsignedShort()
            var format4 = -1L
            var format12 = -1L
            for (i in 0 until subtables) {
                input.seek(cmap + 4 + i * 8L)
                input.readUnsignedShort() // platform
                input.readUnsignedShort() // encoding
                val offset = cmap + (input.readInt().toLong() and 0xFFFFFFFFL)
                input.seek(offset)
                when (input.readUnsignedShort()) {
                    4 -> if (format4 < 0) format4 = offset
                    12 -> if (format12 < 0) format12 = offset
                }
            }
            // Format 12 covers the supplementary planes most emoji live in.
            return when {
                format12 >= 0 -> readFormat12(input, format12)
                format4 >= 0 -> readFormat4(input, format4)
                else -> null
            }
        }

        private fun readFormat12(input: RandomAccessFile, offset: Long): CmapCoverage {
            input.seek(offset + 12)
            val groups = input.readInt()
            val ranges = IntArray(groups * 2)
            for (i in 0 until groups) {
                ranges[i * 2] = input.readInt()
                ranges[i * 2 + 1] = input.readInt()
                input.readInt() // start glyph
            }
            return CmapCoverage(sorted(ranges))
        }

        private fun readFormat4(input: RandomAccessFile, offset: Long): CmapCoverage {
            input.seek(offset + 6)
            val segments = input.readUnsignedShort() / 2
            val ends = IntArray(segments)
            input.seek(offset + 14)
            for (i in 0 until segments) ends[i] = input.readUnsignedShort()
            input.seek(offset + 16 + segments * 2L)
            val ranges = IntArray(segments * 2)
            for (i in 0 until segments) {
                ranges[i * 2] = input.readUnsignedShort()
                ranges[i * 2 + 1] = ends[i]
            }
            return CmapCoverage(sorted(ranges))
        }

        /** Sorts (start, end) pairs by start; fonts normally store them sorted already. */
        private fun sorted(ranges: IntArray): IntArray {
            val count = ranges.size / 2
            var ordered = true
            for (i in 1 until count) {
                if (ranges[i * 2] < ranges[(i - 1) * 2]) ordered = false
            }
            if (ordered) return ranges
            val pairs = ArrayList<Long>(count)
            for (i in 0 until count) {
                pairs += (ranges[i * 2].toLong() shl 32) or (ranges[i * 2 + 1].toLong() and 0xFFFFFFFFL)
            }
            pairs.sort()
            val result = IntArray(ranges.size)
            for (i in 0 until count) {
                result[i * 2] = (pairs[i] shr 32).toInt()
                result[i * 2 + 1] = pairs[i].toInt()
            }
            return result
        }
    }
}
