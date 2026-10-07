package com.github.yutaplug.gifdownloadfix

import java.io.OutputStream
import kotlin.math.max

/** Streaming GIF89a writer with an adaptive 256-colour palette for every frame. */
internal class GifEncoder(
    private val output: OutputStream,
    private val width: Int,
    private val height: Int,
) {
    private var finished = false
    private var frames = 0

    init {
        require(width in 1..65535 && height in 1..65535)
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width)
        short(height)
        output.write(byteArrayOf(0x70, 0, 0)) // Local palettes; no global colour table.
        output.write(byteArrayOf(0x21, 0xff.toByte(), 11))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 1, 0, 0, 0)) // Repeat indefinitely.
    }

    fun addFrame(pixels: IntArray, delayCentiseconds: Int) {
        check(!finished)
        require(pixels.size == width * height && delayCentiseconds in 1..65535)
        val (palette, indices) = quantize(pixels)
        output.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4)) // Keep previous frame; opaque full canvas.
        short(delayCentiseconds)
        output.write(byteArrayOf(0, 0))
        output.write(0x2c)
        short(0)
        short(0)
        short(width)
        short(height)
        output.write(0x87) // Local table of 256 colours.
        output.write(palette)
        writeImageData(indices)
        frames++
    }

    fun finish() {
        check(!finished && frames > 0)
        output.write(0x3b)
        output.flush()
        finished = true
    }

    private fun short(value: Int) {
        output.write(value and 255)
        output.write((value ushr 8) and 255)
    }

    private fun quantize(pixels: IntArray): Pair<ByteArray, ByteArray> {
        val counts = IntArray(32768)
        val red = LongArray(32768)
        val green = LongArray(32768)
        val blue = LongArray(32768)
        pixels.forEach { pixel ->
            val key = colourKey(pixel)
            counts[key]++
            red[key] += (pixel ushr 16) and 255
            green[key] += (pixel ushr 8) and 255
            blue[key] += pixel and 255
        }
        var used = 0
        for (i in 0 until counts.size) if (counts[i] > 0) used++
        val colours = IntArray(used)
        used = 0
        for (i in 0 until counts.size) if (counts[i] > 0) colours[used++] = i
        val boxes = ArrayList<ColourBox>()
        boxes.add(ColourBox(colours, counts))
        while (boxes.size < 256) {
            var splitIndex = -1
            for (i in 0 until boxes.size) {
                if (boxes[i].colours.size > 1 && (splitIndex < 0 || boxes[i].priority > boxes[splitIndex].priority)) {
                    splitIndex = i
                }
            }
            if (splitIndex < 0) break
            val box = boxes.removeAt(splitIndex)
            val buckets = IntArray(32)
            box.colours.forEach { buckets[component(it, box.channel)]++ }
            val positions = IntArray(32)
            for (index in 1..31) positions[index] = positions[index - 1] + buckets[index - 1]
            val ordered = IntArray(box.colours.size)
            box.colours.forEach { ordered[positions[component(it, box.channel)]++] = it }
            var population = 0L
            var cut = 0
            while (cut < ordered.size - 1 && population * 2 < box.population) population += counts[ordered[cut++]]
            cut = cut.coerceIn(1, ordered.size - 1)
            boxes.add(ColourBox(ordered.copyOfRange(0, cut), counts))
            boxes.add(ColourBox(ordered.copyOfRange(cut, ordered.size), counts))
        }
        val palette = ByteArray(768)
        val mapping = IntArray(32768)
        boxes.forEachIndexed { index, box ->
            var r = 0L
            var g = 0L
            var b = 0L
            box.colours.forEach { key ->
                mapping[key] = index
                r += red[key]
                g += green[key]
                b += blue[key]
            }
            palette[index * 3] = (r / box.population).toByte()
            palette[index * 3 + 1] = (g / box.population).toByte()
            palette[index * 3 + 2] = (b / box.population).toByte()
        }
        val indices = ByteArray(pixels.size) { mapping[colourKey(pixels[it])].toByte() }
        return palette to indices
    }

    private class ColourBox(val colours: IntArray, counts: IntArray) {
        val population: Long
        val channel: Int
        val priority: Long

        init {
            val minimum = IntArray(3) { 31 }
            val maximum = IntArray(3)
            var total = 0L
            colours.forEach { key ->
                total += counts[key]
                for (axis in 0..2) {
                    val value = component(key, axis)
                    minimum[axis] = minOf(minimum[axis], value)
                    maximum[axis] = max(maximum[axis], value)
                }
            }
            population = total
            channel = (0..2).maxByOrNull { maximum[it] - minimum[it] } ?: 0
            val range = maximum[channel] - minimum[channel]
            priority = range.toLong() * range * total
        }
    }

    private fun writeImageData(indices: ByteArray) {
        output.write(8) // LZW minimum code size for a 256-colour table.
        val bytes = ByteArray(255)
        var byteCount = 0
        var bits = 0
        var bitCount = 0
        val keys = IntArray(8192) { -1 }
        val codes = IntArray(8192)
        var nextCode = 258
        var codeSize = 9

        fun byte(value: Int) {
            bytes[byteCount++] = value.toByte()
            if (byteCount == bytes.size) {
                output.write(byteCount)
                output.write(bytes, 0, byteCount)
                byteCount = 0
            }
        }

        fun emit(code: Int) {
            bits = bits or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                byte(bits and 255)
                bits = bits ushr 8
                bitCount -= 8
            }
            // The decoder adds an entry one emission after the encoder does.
            if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
        }

        emit(256) // Clear dictionary.
        var prefix = indices[0].toInt() and 255
        for (index in 1 until indices.size) {
            val suffix = indices[index].toInt() and 255
            val key = (prefix shl 8) or suffix
            var slot = ((key ushr 12) xor key) and 8191
            while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) and 8191
            if (keys[slot] == key) {
                prefix = codes[slot]
            } else {
                emit(prefix)
                if (nextCode < 4096) {
                    keys[slot] = key
                    codes[slot] = nextCode++
                } else {
                    emit(256)
                    keys.fill(-1)
                    nextCode = 258
                    codeSize = 9
                }
                prefix = suffix
            }
        }
        emit(prefix)
        emit(257) // End of image.
        if (bitCount > 0) byte(bits and 255)
        if (byteCount > 0) {
            output.write(byteCount)
            output.write(bytes, 0, byteCount)
        }
        output.write(0) // End of image data blocks.
    }

    private companion object {
        fun colourKey(pixel: Int): Int =
            ((pixel ushr 9) and 0x7c00) or ((pixel ushr 6) and 0x3e0) or ((pixel ushr 3) and 31)

        fun component(key: Int, channel: Int): Int = (key ushr (10 - channel * 5)) and 31
    }
}
