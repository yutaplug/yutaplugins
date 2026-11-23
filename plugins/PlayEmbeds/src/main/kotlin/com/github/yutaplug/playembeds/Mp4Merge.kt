package com.github.yutaplug.playembeds

import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * Merges a DASH video stream and a DASH audio stream (fragmented MP4, as
 * YouTube serves its adaptive formats) into one fragmented MP4 with both
 * tracks, without touching any samples: only the containers are rewritten.
 *
 * The merged file is `ftyp + moov (both tracks) + sidx`, followed by one
 * chunk per video segment: that segment, then the audio segments that end in
 * it. The sidx indexes those chunks, so ExoPlayer can seek. Audio is placed
 * by its end time so a seek to any chunk still finds the audio that covers it.
 * Every byte position is known up front, so any range of the merged file can
 * be served straight from ranges of the two source streams.
 */
internal object Mp4Merge {
    class Box(val type: String, val start: Int, val headerSize: Int, val size: Int) {
        val contentStart: Int get() = start + headerSize
        val end: Int get() = start + size
    }

    /** A segment of a source stream: its byte range there and its time span in the track's timescale. */
    class Segment(val sourceOffset: Long, val size: Int, val start: Long, val duration: Long)

    class Track(
        val url: String,
        val init: ByteArray,
        val ftyp: Box?,
        val moov: Box,
        val trackId: Int,
        val timescale: Long,
        val segments: List<Segment>,
    )

    /** A source segment placed at [offset] in the merged file, rewritten to [trackId]. */
    class Piece(val track: Track, val segment: Segment, val offset: Long, val trackId: Int)

    class Layout(val header: ByteArray, val pieces: List<Piece>, val total: Long)

    /** Parse a stream's init segment and sidx from its first [bytes] (offset 0 through the end of the sidx). */
    fun parseTrack(url: String, bytes: ByteArray, initEnd: Int, indexStart: Int): Track? {
        val top = boxes(bytes, 0, minOf(initEnd + 1, bytes.size))
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val ftyp = top.firstOrNull { it.type == "ftyp" }
        val trak = children(bytes, moov).firstOrNull { it.type == "trak" } ?: return null
        val tkhd = children(bytes, trak).firstOrNull { it.type == "tkhd" } ?: return null
        val trackId = u32(bytes, trackIdOffset(bytes, tkhd)).toInt()

        val sidx = boxes(bytes, indexStart, bytes.size).firstOrNull { it.type == "sidx" } ?: return null
        var position = sidx.contentStart
        val version = bytes[position].toInt()
        position += 8 // version + flags, reference_ID
        val timescale = u32(bytes, position)
        position += 4
        val earliest: Long
        val firstOffset: Long
        if (version == 0) {
            earliest = u32(bytes, position)
            firstOffset = u32(bytes, position + 4)
            position += 8
        } else {
            earliest = u64(bytes, position)
            firstOffset = u64(bytes, position + 8)
            position += 16
        }
        val count = u16(bytes, position + 2)
        position += 4
        if (timescale <= 0 || count <= 0 || position + count * 12 > sidx.end) return null

        val segments = ArrayList<Segment>(count)
        var offset = sidx.end + firstOffset
        var time = earliest
        while (segments.size < count) {
            val reference = u32(bytes, position)
            // A reference to another sidx (hierarchical index) isn't supported.
            if (reference ushr 31 != 0L) return null
            val size = (reference and 0x7FFFFFFFL).toInt()
            val duration = u32(bytes, position + 4)
            segments.add(Segment(offset, size, time, duration))
            offset += size
            time += duration
            position += 12
        }
        return Track(url, bytes, ftyp, moov, trackId, timescale, segments)
    }

    fun merge(video: Track, audio: Track): Layout? {
        val audioId = video.trackId + 1
        val moov = mergedMoov(video, audio, audioId) ?: return null
        val ftyp = video.ftyp?.let { Arrays.copyOfRange(video.init, it.start, it.end) } ?: ByteArray(0)

        // Group the audio segments by the video segment their end falls in.
        val videoSegments = video.segments
        val audioByChunk = ArrayList<ArrayList<Segment>>(videoSegments.size)
        while (audioByChunk.size < videoSegments.size) audioByChunk.add(ArrayList())
        var chunk = 0
        for (segment in audio.segments) {
            val endUs = micros(segment.start + segment.duration, audio.timescale)
            while (chunk + 1 < videoSegments.size && micros(videoSegments[chunk + 1].start, video.timescale) < endUs) {
                chunk++
            }
            audioByChunk[chunk].add(segment)
        }

        val sidxSize = 12 + 4 + 4 + 8 + 8 + 4 + 12 * videoSegments.size
        val headerSize = ftyp.size + moov.size + sidxSize
        val pieces = ArrayList<Piece>()
        val chunkSizes = LongArray(videoSegments.size)
        var offset = headerSize.toLong()
        for ((index, segmentAt) in videoSegments.withIndex()) {
            val start = offset
            pieces.add(Piece(video, segmentAt, offset, video.trackId))
            offset += segmentAt.size
            for (segment in audioByChunk[index]) {
                pieces.add(Piece(audio, segment, offset, audioId))
                offset += segment.size
            }
            chunkSizes[index] = offset - start
            if (chunkSizes[index] > 0x7FFFFFFFL) return null
        }

        val sidx = ByteArray(sidxSize)
        put32(sidx, 0, sidxSize.toLong())
        putType(sidx, 4, "sidx")
        sidx[8] = 1 // version 1: 64-bit times and offsets
        put32(sidx, 12, video.trackId.toLong())
        put32(sidx, 16, video.timescale)
        put64(sidx, 20, videoSegments.firstOrNull()?.start ?: 0L)
        put64(sidx, 28, 0L)
        put32(sidx, 36, videoSegments.size.toLong()) // reserved (16 bits) + reference_count
        for ((index, segmentAt) in videoSegments.withIndex()) {
            val entry = 40 + index * 12
            put32(sidx, entry, chunkSizes[index])
            put32(sidx, entry + 4, segmentAt.duration)
            put32(sidx, entry + 8, 0x90000000L) // starts with a SAP, type 1
        }

        val header = ByteArrayOutputStream(headerSize).apply {
            write(ftyp)
            write(moov)
            write(sidx)
        }.toByteArray()
        return Layout(header, pieces, offset)
    }

    /** Points a fetched segment's fragments at [Piece.trackId] and its new position. */
    fun patchSegment(bytes: ByteArray, piece: Piece) {
        for (moof in boxes(bytes, 0, bytes.size)) {
            if (moof.type != "moof") continue
            for (traf in children(bytes, moof)) {
                if (traf.type != "traf") continue
                for (tfhd in children(bytes, traf)) {
                    if (tfhd.type != "tfhd") continue
                    val position = tfhd.contentStart
                    val flags = u32(bytes, position) and 0xFFFFFFL
                    put32(bytes, position + 4, piece.trackId.toLong())
                    if (flags and 1L != 0L) {
                        // An absolute base offset; move it with the segment.
                        val base = u64(bytes, position + 8)
                        put64(bytes, position + 8, piece.offset + (base - piece.segment.sourceOffset))
                    }
                }
            }
        }
    }

    /** `moov` with the video's mvhd and trak, the audio trak as [audioId], and both trex in mvex. */
    private fun mergedMoov(video: Track, audio: Track, audioId: Int): ByteArray? {
        val videoChildren = children(video.init, video.moov)
        val audioChildren = children(audio.init, audio.moov)
        val mvhd = videoChildren.firstOrNull { it.type == "mvhd" } ?: return null
        val videoTrak = videoChildren.firstOrNull { it.type == "trak" } ?: return null
        val videoMvex = videoChildren.firstOrNull { it.type == "mvex" } ?: return null
        val audioTrak = audioChildren.firstOrNull { it.type == "trak" } ?: return null
        val audioMvex = audioChildren.firstOrNull { it.type == "mvex" } ?: return null

        val audioTrakBytes = Arrays.copyOfRange(audio.init, audioTrak.start, audioTrak.end)
        val tkhd = boxes(audioTrakBytes, 8, audioTrakBytes.size).firstOrNull { it.type == "tkhd" } ?: return null
        put32(audioTrakBytes, trackIdOffset(audioTrakBytes, tkhd), audioId.toLong())

        val audioTrex = children(audio.init, audioMvex).firstOrNull { it.type == "trex" } ?: return null
        val audioTrexBytes = Arrays.copyOfRange(audio.init, audioTrex.start, audioTrex.end)
        put32(audioTrexBytes, 12, audioId.toLong())

        val mvex = box(
            "mvex",
            Arrays.copyOfRange(video.init, videoMvex.contentStart, videoMvex.end),
            audioTrexBytes,
        )
        return box(
            "moov",
            Arrays.copyOfRange(video.init, mvhd.start, mvhd.end),
            Arrays.copyOfRange(video.init, videoTrak.start, videoTrak.end),
            audioTrakBytes,
            mvex,
        )
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        var size = 8
        for (part in parts) size += part.size
        val out = ByteArrayOutputStream(size)
        val header = ByteArray(8)
        put32(header, 0, size.toLong())
        putType(header, 4, type)
        out.write(header)
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun trackIdOffset(bytes: ByteArray, tkhd: Box): Int {
        val version = bytes[tkhd.contentStart].toInt()
        return tkhd.contentStart + 4 + if (version == 1) 16 else 8
    }

    private fun micros(time: Long, timescale: Long): Double = time * 1_000_000.0 / timescale

    private fun children(bytes: ByteArray, parent: Box): List<Box> = boxes(bytes, parent.contentStart, parent.end)

    fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val result = ArrayList<Box>()
        var position = from
        while (position + 8 <= to) {
            var size = u32(bytes, position)
            var header = 8
            if (size == 1L) {
                if (position + 16 > to) break
                size = u64(bytes, position + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - position).toLong()
            }
            if (size < header || position + size > to) break
            result.add(Box(String(bytes, position + 4, 4, Charsets.US_ASCII), position, header, size.toInt()))
            position += size.toInt()
        }
        return result
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long = (u32(bytes, at) shl 32) or u32(bytes, at + 4)

    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun put64(bytes: ByteArray, at: Int, value: Long) {
        put32(bytes, at, value ushr 32)
        put32(bytes, at + 4, value)
    }

    private fun putType(bytes: ByteArray, at: Int, type: String) {
        var index = 0
        while (index < 4) {
            bytes[at + index] = type[index].code.toByte()
            index++
        }
    }
}
