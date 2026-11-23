package com.github.yutaplug.profileeffects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.zip.CRC32

internal object ApngOp {
    const val DISPOSE_NONE = 0
    const val DISPOSE_BACKGROUND = 1
    const val DISPOSE_PREVIOUS = 2
    const val BLEND_SOURCE = 0
    const val BLEND_OVER = 1
}

internal class ApngFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Display time in milliseconds. */
    val delay: Long,
    val dispose: Int,
    val blend: Int,
    /** (offset, length) pairs of this frame's zlib payload inside the file. */
    val chunks: IntArray,
    val size: Int,
)

/** Reused per player; only one decode per player is ever in flight. */
internal class FrameBuffer {
    var bytes = ByteArray(0)
        private set

    fun ensure(size: Int): ByteArray {
        if (bytes.size < size) bytes = ByteArray(size + size / 4)
        return bytes
    }
}

/**
 * An APNG that keeps only the compressed file and chunk offsets. Each frame is rebuilt as a
 * standalone PNG and decoded on demand, so memory stays bounded no matter how many frames it has.
 * Discord's bundled LINE decoder unpacked every frame up front, which ran devices out of memory.
 */
internal class ApngImage private constructor(
    private val data: ByteArray,
    val width: Int,
    val height: Int,
    /** 0 loops forever. */
    val plays: Int,
    val frames: List<ApngFrame>,
    /** Offset of the IHDR chunk, or -1 when [raw]. */
    private val header: Int,
    /** (offset, length) pairs of whole chunks (PLTE, tRNS, sRGB) every frame PNG needs. */
    private val shared: IntArray,
    /** Not a PNG: BitmapFactory decodes the whole file as a still image. */
    private val raw: Boolean,
) {
    /** Worker thread only. Returns [reuse] (reconfigured) when it could hold the frame, otherwise a new bitmap. */
    fun decode(index: Int, sampleSize: Int, reuse: Bitmap?, buffer: FrameBuffer): Bitmap? {
        val bytes: ByteArray
        val length: Int
        if (raw) {
            bytes = data
            length = data.size
        } else {
            length = build(frames[index], buffer)
            bytes = buffer.bytes
        }
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inSampleSize = sampleSize
        options.inMutable = true
        options.inBitmap = reuse
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, length, options)
        } catch (_: IllegalArgumentException) {
            // The reused bitmap was too small for this frame (or the decoder refused it).
            options.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, length, options)
        }
    }

    private fun build(frame: ApngFrame, buffer: FrameBuffer): Int {
        var sharedSize = 0
        var i = 1
        while (i < shared.size) {
            sharedSize += shared[i]
            i += 2
        }
        val out = buffer.ensure(SIGNATURE.size + 25 + sharedSize + 12 + frame.size + 12)
        System.arraycopy(SIGNATURE, 0, out, 0, SIGNATURE.size)
        var pos = SIGNATURE.size
        // IHDR with this frame's size; bit depth, color type and interlacing stay the same.
        putInt(out, pos, 13)
        putInt(out, pos + 4, IHDR)
        putInt(out, pos + 8, frame.width)
        putInt(out, pos + 12, frame.height)
        System.arraycopy(data, header + 16, out, pos + 16, 5)
        putInt(out, pos + 21, crc(out, pos + 4, 17))
        pos += 25
        i = 0
        while (i < shared.size) {
            System.arraycopy(data, shared[i], out, pos, shared[i + 1])
            pos += shared[i + 1]
            i += 2
        }
        val start = pos
        putInt(out, pos, frame.size)
        putInt(out, pos + 4, IDAT)
        pos += 8
        i = 0
        while (i < frame.chunks.size) {
            System.arraycopy(data, frame.chunks[i], out, pos, frame.chunks[i + 1])
            pos += frame.chunks[i + 1]
            i += 2
        }
        putInt(out, pos, crc(out, start + 4, 4 + frame.size))
        pos += 4
        putInt(out, pos, 0)
        putInt(out, pos + 4, IEND)
        putInt(out, pos + 8, IEND_CRC)
        return pos + 12
    }

    private class PendingFrame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val delay: Long,
        val dispose: Int,
        val blend: Int,
    ) {
        val chunks = ArrayList<Int>()
        var size = 0

        fun add(offset: Int, length: Int) {
            chunks.add(offset)
            chunks.add(length)
            size += length
        }

        fun finish(): ApngFrame = ApngFrame(x, y, width, height, delay, dispose, blend, ints(chunks), size)
    }

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private const val IHDR = 0x49484452
        private const val ACTL = 0x6163544C
        private const val FCTL = 0x6663544C
        private const val IDAT = 0x49444154
        private const val FDAT = 0x66644154
        private const val IEND = 0x49454E44
        private const val PLTE = 0x504C5445
        private const val TRNS = 0x74524E53
        private const val SRGB = 0x73524742
        private const val IEND_CRC = 0xAE426082L.toInt()
        private const val MAX_DIMENSION = 8192
        private const val MAX_FRAMES = 4096

        /** Worker thread. Returns null for data that is neither a usable PNG/APNG nor another decodable image. */
        fun parse(data: ByteArray): ApngImage? {
            if (!isPng(data)) return still(data)
            var pos = SIGNATURE.size
            var header = -1
            var width = 0
            var height = 0
            var animation = false
            var plays = 0
            var seenData = false
            val shared = ArrayList<Int>()
            val stillChunks = PendingFrame(0, 0, 0, 0, 0, 0, 0)
            val frames = ArrayList<ApngFrame>()
            var pending: PendingFrame? = null
            while (pos + 12 <= data.size) {
                val length = int(data, pos)
                val type = int(data, pos + 4)
                if (length < 0 || length > data.size - pos - 12) return null
                val body = pos + 8
                if (type == IEND) break
                when (type) {
                    IHDR -> if (length >= 13 && header < 0) {
                        header = pos
                        width = int(data, body)
                        height = int(data, body + 4)
                    }

                    ACTL -> if (length >= 8 && !seenData) {
                        animation = true
                        plays = int(data, body + 4).coerceAtLeast(0)
                    }

                    FCTL -> if (length >= 26 && animation) {
                        pending?.let { if (it.size > 0) frames.add(it.finish()) }
                        if (frames.size >= MAX_FRAMES) return null
                        val delayNum = short(data, body + 20)
                        val delayDen = short(data, body + 22).let { if (it == 0) 100 else it }
                        var delay = delayNum * 1000L / delayDen
                        // Browsers (and so Discord's web client) stretch near-zero delays to 100 ms.
                        if (delay <= 10) delay = 100
                        pending = PendingFrame(
                            int(data, body + 12),
                            int(data, body + 16),
                            int(data, body + 4),
                            int(data, body + 8),
                            delay,
                            data[body + 24].toInt(),
                            data[body + 25].toInt(),
                        )
                    }

                    IDAT -> {
                        seenData = true
                        stillChunks.add(body, length)
                        // fcTL before the first IDAT makes the default image the first frame.
                        if (frames.isEmpty()) pending?.add(body, length)
                    }

                    FDAT -> if (length > 4) pending?.add(body + 4, length - 4)

                    PLTE, TRNS, SRGB -> if (!seenData) {
                        shared.add(pos)
                        shared.add(length + 12)
                    }
                }
                pos = body + length + 4
            }
            pending?.let { if (it.size > 0) frames.add(it.finish()) }
            if (header < 0 || width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                return null
            }
            var valid = animation && frames.isNotEmpty()
            for (frame in frames) {
                if (frame.x < 0 || frame.y < 0 || frame.width <= 0 || frame.height <= 0 ||
                    frame.x.toLong() + frame.width > width || frame.y.toLong() + frame.height > height
                ) {
                    valid = false
                }
            }
            if (valid) return ApngImage(data, width, height, plays, frames, header, ints(shared), false)
            if (stillChunks.size == 0) return null
            val frame = ApngFrame(0, 0, width, height, 0, 0, 0, ints(stillChunks.chunks), stillChunks.size)
            return ApngImage(data, width, height, 1, listOf(frame), header, ints(shared), false)
        }

        private fun still(data: ByteArray): ApngImage? {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) return null
            val frame = ApngFrame(0, 0, width, height, 0, 0, 0, IntArray(0), 0)
            return ApngImage(data, width, height, 1, listOf(frame), -1, IntArray(0), true)
        }

        private fun isPng(data: ByteArray): Boolean {
            if (data.size < SIGNATURE.size) return false
            var i = 0
            while (i < SIGNATURE.size) {
                if (data[i] != SIGNATURE[i]) return false
                i++
            }
            return true
        }

        private fun ints(list: List<Int>): IntArray {
            val result = IntArray(list.size)
            var i = 0
            while (i < result.size) {
                result[i] = list[i]
                i++
            }
            return result
        }

        private fun int(data: ByteArray, pos: Int): Int = (data[pos].toInt() and 0xff shl 24) or
            (data[pos + 1].toInt() and 0xff shl 16) or
            (data[pos + 2].toInt() and 0xff shl 8) or
            (data[pos + 3].toInt() and 0xff)

        private fun short(data: ByteArray, pos: Int): Int = (data[pos].toInt() and 0xff shl 8) or
            (data[pos + 1].toInt() and 0xff)

        private fun putInt(out: ByteArray, pos: Int, value: Int) {
            out[pos] = (value ushr 24).toByte()
            out[pos + 1] = (value ushr 16).toByte()
            out[pos + 2] = (value ushr 8).toByte()
            out[pos + 3] = value.toByte()
        }

        private fun crc(data: ByteArray, offset: Int, length: Int): Int {
            val crc = CRC32()
            crc.update(data, offset, length)
            return crc.value.toInt()
        }
    }
}
