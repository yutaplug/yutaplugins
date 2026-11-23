package com.github.yutaplug.profileeffects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.RejectedExecutionException

/**
 * Plays one effect layer. A worker decodes one frame at a time into a reused bitmap while the
 * previous frame is on screen; the main thread composites it into [bitmap] (the only bitmap the
 * view draws), so rendering never races the decoder.
 */
internal class EffectLayerPlayer(
    val layer: EffectLayer,
    private val image: ApngImage,
    /** Composited canvas, already scaled down to roughly the size it is displayed at. */
    val bitmap: Bitmap,
    private val sampleSize: Int,
    private val invalidate: () -> Unit,
    private val log: (String, Throwable) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val canvas = Canvas(bitmap)
    private val scaleX = bitmap.width.toFloat() / image.width
    private val scaleY = bitmap.height.toFloat() / image.height
    private val buffer = FrameBuffer()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val frameRect = RectF()
    private val region = Rect()

    /** Decode target handed back and forth with the worker; never touched by both at once. */
    private var scratch: Bitmap? = null

    /** Canvas region saved for frames that dispose to the previous state. */
    private var saved: Bitmap? = null
    private var savedCanvas: Canvas? = null

    private var generation = 0
    private var decoding = false
    private var queued = -1
    private var ready: Bitmap? = null
    private var readyIndex = 0
    private var shownIndex = -1
    private var shownAt = 0L
    private var plays = 0
    private var failed = false
    private var released = false
    private val showTask = Runnable { showReady() }

    var visible = false
        private set
    var hasFrame = false
        private set

    /** Restarts the layer from its first frame and shows it. */
    fun play() {
        if (released || failed) return
        reset()
        visible = true
        bitmap.eraseColor(0)
        request(0)
    }

    /** Stops and hides the layer; the next [play] starts over. */
    fun stop() {
        reset()
        visible = false
    }

    fun release() {
        released = true
        stop()
        scratch = null
        saved = null
        savedCanvas = null
    }

    private fun reset() {
        generation++
        handler.removeCallbacks(showTask)
        ready = null
        queued = -1
        shownIndex = -1
        plays = 0
        hasFrame = false
    }

    private fun fail(error: Throwable?) {
        if (error != null) log("Could not play profile effect layer ${layer.source}", error)
        failed = true
        stop()
        invalidate()
    }

    private fun request(index: Int) {
        if (decoding) {
            // A stale decode still owns the scratch bitmap; continue once it hands it back.
            queued = index
            return
        }
        decoding = true
        val token = generation
        val reuse = scratch
        try {
            EffectAssets.decoders.execute {
                var result: Bitmap? = null
                var error: Throwable? = null
                try {
                    result = image.decode(index, sampleSize, reuse, buffer)
                } catch (t: Throwable) {
                    // Includes OutOfMemoryError, which must not take Discord down with it.
                    error = t
                }
                handler.post { onDecoded(token, index, result, error) }
            }
        } catch (error: RejectedExecutionException) {
            decoding = false
            fail(error)
        }
    }

    private fun onDecoded(token: Int, index: Int, result: Bitmap?, error: Throwable?) {
        decoding = false
        if (result != null) scratch = result
        if (released) {
            scratch = null
            return
        }
        if (token != generation) {
            val next = queued
            queued = -1
            if (next >= 0) request(next)
            return
        }
        if (result == null) {
            fail(error ?: IllegalStateException("Frame $index could not be decoded"))
            return
        }
        ready = result
        readyIndex = index
        val due = if (shownIndex < 0) 0L else shownAt + image.frames[shownIndex].delay
        // A late frame shows immediately; the animation slows down instead of skipping frames.
        if (due <= SystemClock.uptimeMillis()) showReady() else handler.postAtTime(showTask, due)
    }

    private fun showReady() {
        val frame = ready ?: return
        ready = null
        val index = readyIndex
        try {
            composite(index, frame)
        } catch (error: Throwable) {
            fail(error)
            return
        }
        shownIndex = index
        shownAt = SystemClock.uptimeMillis()
        hasFrame = true
        invalidate()
        val count = image.frames.size
        if (count <= 1) return
        var next = index + 1
        if (next >= count) {
            plays++
            // Finite APNGs hold their last frame, as browsers do.
            if (image.plays > 0 && plays >= image.plays) return
            next = 0
        }
        request(next)
    }

    private fun composite(index: Int, decoded: Bitmap) {
        val frame = image.frames[index]
        if (index == 0) {
            bitmap.eraseColor(0)
        } else {
            val previous = image.frames[index - 1]
            when (dispose(previous, index - 1)) {
                ApngOp.DISPOSE_BACKGROUND -> canvas.drawRect(rect(previous), clearPaint)

                ApngOp.DISPOSE_PREVIOUS -> savedCanvas?.let {
                    region(previous)
                    canvas.drawBitmap(saved!!, region, region, copyPaint)
                }
            }
        }
        if (dispose(frame, index) == ApngOp.DISPOSE_PREVIOUS) save(frame)
        val target = rect(frame)
        if (frame.blend != ApngOp.BLEND_OVER) canvas.drawRect(target, clearPaint)
        canvas.drawBitmap(decoded, null, target, drawPaint)
    }

    /** The first frame has nothing to go back to, so "previous" means "background" there. */
    private fun dispose(frame: ApngFrame, index: Int): Int = when (frame.dispose) {
        ApngOp.DISPOSE_BACKGROUND -> ApngOp.DISPOSE_BACKGROUND
        ApngOp.DISPOSE_PREVIOUS -> if (index == 0) ApngOp.DISPOSE_BACKGROUND else ApngOp.DISPOSE_PREVIOUS
        else -> ApngOp.DISPOSE_NONE
    }

    private fun save(frame: ApngFrame) {
        var target = saved
        if (target == null) {
            target = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            saved = target
            savedCanvas = Canvas(target)
        }
        region(frame)
        savedCanvas!!.drawBitmap(bitmap, region, region, copyPaint)
    }

    private fun rect(frame: ApngFrame): RectF {
        frameRect.set(
            frame.x * scaleX,
            frame.y * scaleY,
            (frame.x + frame.width) * scaleX,
            (frame.y + frame.height) * scaleY,
        )
        return frameRect
    }

    private fun region(frame: ApngFrame) {
        rect(frame).round(region)
        if (!region.intersect(0, 0, bitmap.width, bitmap.height)) region.setEmpty()
    }
}
