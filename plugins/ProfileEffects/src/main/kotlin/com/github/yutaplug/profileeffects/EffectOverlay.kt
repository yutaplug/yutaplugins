package com.github.yutaplug.profileeffects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import b.f.g.c.c
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo
import java.io.File
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil

/**
 * Draws a profile effect natively: each layer's APNG is decoded frame by frame (see [ApngImage])
 * and drawn on Discord's 450px design canvas, scaled to the card width. The static image shows
 * while layers load, when animation is off, and when no layer can be played.
 */
internal class EffectOverlay(context: Context, private val log: (String, Throwable) -> Unit) :
    TouchThroughLayout(context) {
    private class Prepared(val layer: EffectLayer, val image: ApngImage, val bitmap: Bitmap, val sampleSize: Int)

    private var effect: Product.Effect? = null
    private var animate = true
    private var built = false
    private var players: List<EffectLayerPlayer> = emptyList()
    private var staticImage: SimpleDraweeView? = null
    private var session = 0
    private var running = false
    private var disposed = false
    private val main = Handler(Looper.getMainLooper())
    private val timeline = ArrayList<Runnable>()
    private val layerRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanup = DeferredCleanup({ cleanupHandler.post(it) }, { cleanupHandler.removeCallbacks(it) })
    private val restart = Runnable {
        if (!disposed && isShown && windowToken != null) {
            if (built) play() else rebuild()
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun bind(effect: Product.Effect?, animate: Boolean) {
        if (this.effect == effect && this.animate == animate) return
        this.effect = effect
        this.animate = animate
        rebuild()
    }

    fun restartAnimation() {
        removeCallbacks(restart)
        if (!disposed) postDelayed(restart, 120)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) {
            cleanup.cancel()
            restartAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        if (!disposed) {
            // View.post() can retain this until a future attach; use the main Handler instead.
            cleanup.schedule { if (windowToken == null) clearRenderer() }
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) restartAnimation() else pause()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Ancestors stop clipping while a frame is shown, so keep the effect inside the card.
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height)
        super.dispatchDraw(canvas)
        if (width > 0) {
            // As on Discord: every layer is the card's full width, as tall as its image's shape
            // allows, offset by its position in plain pixels.
            val density = resources.displayMetrics.density
            val layerWidth = width.toFloat()
            for (player in players) {
                if (!player.visible || !player.hasFrame) continue
                val layer = player.layer
                val left = layer.x * density
                val top = layer.y * density
                val aspect = player.bitmap.height.toFloat() / player.bitmap.width
                layerRect.set(left, top, left + layerWidth, top + layerWidth * aspect)
                canvas.drawBitmap(player.bitmap, null, layerRect, paint)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun rebuild() {
        clearRenderer()
        val effect = effect ?: return
        if (disposed || windowToken == null) return
        built = true
        showStatic(effect.source)
        if (animate && effect.layers.isNotEmpty()) load(withVariant(effect.layers))
    }

    /**
     * Some effects come in variants. Like Discord, pick one index below the smallest variant count
     * and use it on every layer that has variants, so the layers stay matched.
     */
    private fun withVariant(layers: List<EffectLayer>): List<EffectLayer> {
        var count = 0
        for (layer in layers) {
            val size = layer.variants.size
            if (size > 0) count = if (count == 0) size else minOf(count, size)
        }
        if (count == 0) return layers
        val index = (Math.random() * count).toInt().coerceIn(0, count - 1)
        return layers.map { if (it.variants.isEmpty()) it else it.copy(source = it.variants[index]) }
    }

    private fun load(layers: List<EffectLayer>) {
        val token = session
        val cacheDir = context.cacheDir
        // The overlay may not be laid out yet; the sheet spans the screen width on phones.
        val surfaceWidth = (if (width > 0) width else resources.displayMetrics.widthPixels).coerceAtLeast(1)
        val results = arrayOfNulls<Prepared>(layers.size)
        var remaining = layers.size
        var index = 0
        while (index < layers.size) {
            val slot = index++
            val layer = layers[slot]
            val task = Runnable {
                val prepared = try {
                    prepare(cacheDir, layer, surfaceWidth)
                } catch (error: Throwable) {
                    log("Could not load profile effect layer ${layer.source}", error)
                    null
                }
                main.post {
                    if (token != session || disposed) return@post
                    results[slot] = prepared
                    if (--remaining == 0) ready(results)
                }
            }
            try {
                EffectAssets.downloads.execute(task)
            } catch (_: RejectedExecutionException) {
                if (--remaining == 0) ready(results)
            }
        }
    }

    /** Worker thread: download, parse, and allocate the layer's canvas at about its displayed size. */
    private fun prepare(cacheDir: File, layer: EffectLayer, surfaceWidth: Int): Prepared {
        val image = ApngImage.parse(EffectAssets.load(cacheDir, layer.source))
            ?: throw IllegalStateException("Unsupported image")
        // Layers are drawn at the card's width (see dispatchDraw).
        val fit = (surfaceWidth.toFloat() / image.width)
            .coerceIn(1f / 64, 1f)
        val width = ceil(image.width * fit).toInt().coerceIn(1, MAX_BITMAP_SIZE)
        val height = ceil(image.height * fit).toInt().coerceIn(1, MAX_BITMAP_SIZE)
        // Decode at the largest power-of-two reduction that still covers the canvas.
        var sampleSize = 1
        while (sampleSize < 64 && sampleSize * 2 * fit <= 1f) sampleSize *= 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return Prepared(layer, image, bitmap, sampleSize)
    }

    private fun ready(results: Array<Prepared?>) {
        val list = ArrayList<EffectLayerPlayer>()
        for (prepared in results) {
            if (prepared == null) continue
            list.add(
                EffectLayerPlayer(prepared.layer, prepared.image, prepared.bitmap, prepared.sampleSize, ::invalidate, log),
            )
        }
        // With no playable layer the static image stays up.
        if (list.isEmpty()) return
        players = list
        if (isShown && windowToken != null) play()
    }

    private fun play() {
        if (players.isEmpty()) return
        stopLayers()
        removeStatic()
        running = true
        // Discord starts the timeline 500 ms after the effect appears.
        for (player in players) later(INTRO_DELAY + player.layer.start) { playLayer(player) }
    }

    private fun playLayer(player: EffectLayerPlayer) {
        if (!running) return
        player.play()
        val layer = player.layer
        // A looping layer without a delay stays up and its image loops by itself, as on Discord.
        if (layer.duration <= 0 || (layer.loop && layer.loopDelay <= 0)) return
        later(layer.duration) {
            player.stop()
            invalidate()
            if (layer.loop) later(layer.loopDelay) { playLayer(player) }
        }
    }

    private fun later(delay: Long, task: () -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                timeline.remove(this)
                task()
            }
        }
        timeline.add(runnable)
        main.postDelayed(runnable, delay)
    }

    private fun stopLayers() {
        running = false
        for (runnable in timeline) main.removeCallbacks(runnable)
        timeline.clear()
        for (player in players) player.stop()
        invalidate()
    }

    private fun pause() {
        removeCallbacks(restart)
        stopLayers()
    }

    private fun showStatic(source: String?) {
        if (source == null) return
        val image = SimpleDraweeView(context)
        staticImage = image
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        MGImages.setImage(
            image,
            listOf(source),
            0,
            0,
            false,
            null,
            MGImages.AlwaysUpdateChangeDetector.INSTANCE,
            object : c<ImageInfo>() {
                override fun onFinalImageSet(id: String?, info: ImageInfo?, animatable: Animatable?) {
                    if (disposed || staticImage !== image) return
                    if (info != null && info.width > 0 && info.height > 0) {
                        image.aspectRatio = info.width.toFloat() / info.height
                    }
                    // Reduced-motion/static assets must not auto-play through Fresco.
                    animatable?.stop()
                }
            },
        )
    }

    private fun removeStatic() {
        val image = staticImage ?: return
        staticImage = null
        image.controller = null
        removeView(image)
    }

    private fun clearRenderer() {
        cleanup.cancel()
        removeCallbacks(restart)
        session++
        built = false
        stopLayers()
        for (player in players) player.release()
        players = emptyList()
        removeStatic()
        removeAllViews()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pause()
        // Removing children inline can invalidate an ancestor's detach traversal.
        cleanup.schedule(::clearRenderer)
    }

    private companion object {
        /** Stays under the GPU texture limit of older devices. */
        const val MAX_BITMAP_SIZE = 4096

        const val INTRO_DELAY = 500L
    }
}
