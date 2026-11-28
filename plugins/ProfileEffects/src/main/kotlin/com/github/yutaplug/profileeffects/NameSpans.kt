package com.github.yutaplug.profileeffects

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Display name effects, drawn as Discord's web CSS draws them when not animating (the
 * `UserNameWithEffects` component and its `*_or4Hj-` classes). Lengths in CSS px are dp here.
 */

/** Marks the spans this plugin adds to names, so they can be found and removed. */
internal interface StyledName

/**
 * The FallbackFont plugin's font spans. They cover characters the device (and so a name font) can't
 * draw, and emoji, or all text when its text font is set. Android applies spans in the order they were
 * added, so ours, added later, would replace their font; [raise] moves them back in front.
 */
internal object FallbackFonts {
    private const val FONT_SPAN = "com.github.yutaplug.fallbackfont.FallbackFont\$FontSpan"

    /** FallbackFont's FontSlot.TEXT, which a display name font should still beat. */
    private const val TEXT_SLOT = 0

    @Volatile private var slotField: java.lang.reflect.Field? = null

    private fun slot(span: Any): Int? {
        val field = slotField?.takeIf { it.declaringClass === span.javaClass } ?: try {
            span.javaClass.getDeclaredField("slot").apply { isAccessible = true }.also { slotField = it }
        } catch (_: Exception) {
            return null
        }
        return try {
            field.getInt(span)
        } catch (_: Exception) {
            null
        }
    }

    /** Whether [span] is a FallbackFont span that applies over a name drawn [withFont] (a display name font). */
    fun applies(span: Any, withFont: Boolean): Boolean {
        if (span.javaClass.name != FONT_SPAN) return false
        if (!withFont) return true
        val slot = slot(span) ?: return false
        return slot != TEXT_SLOT
    }

    /** Re-adds FallbackFont's spans that should win over a name font, so they apply after ours. */
    fun raise(text: android.text.Spannable) {
        for (span in text.getSpans(0, text.length, MetricAffectingSpan::class.java)) {
            if (!applies(span, true)) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val flags = text.getSpanFlags(span)
            text.removeSpan(span)
            text.setSpan(span, start, end, flags)
        }
    }

    /** Runs [block] with the FallbackFont spans over [start]..[end] applied to [paint], then undoes them. */
    inline fun <T> applying(text: Spanned, start: Int, end: Int, paint: TextPaint, withFont: Boolean, block: () -> T): T {
        val typeface = paint.typeface
        val fakeBold = paint.isFakeBoldText
        val skew = paint.textSkewX
        for (span in text.getSpans(start, end, MetricAffectingSpan::class.java)) {
            if (applies(span, withFont)) span.updateMeasureState(paint)
        }
        try {
            return block()
        } finally {
            paint.typeface = typeface
            paint.isFakeBoldText = fakeBold
            paint.textSkewX = skew
        }
    }
}

/** Evenly spaced gradient stops from 10% to 90%, as Discord places them. */
private fun stopPositions(count: Int): FloatArray = FloatArray(count) { 0.1f + 0.8f * it / (count - 1) }

/**
 * CSS `linear-gradient(to bottom right|left, ...)` over a box: the 50% line runs through the other
 * two corners, so on a wide name the colors change mostly from top to bottom.
 */
private fun cornerGradient(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    toRight: Boolean,
    colors: IntArray,
    positions: FloatArray?,
): Shader {
    val w = max(width, 1f)
    val h = max(height, 1f)
    val length = sqrt(w * w + h * h)
    val dx = (if (toRight) h else -h) / length
    val dy = w / length
    val half = w * h / length
    val cx = left + w / 2
    val cy = top + h / 2
    return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, positions, Shader.TileMode.CLAMP)
}

/** Discord's shades of a style's first color (chroma-js HSL math from its web app). */
internal class Shades(val main: Int) {
    private val h: Float
    private val s: Float
    private val l: Float

    init {
        val hsl = toHsl(main)
        h = hsl[0]
        s = hsl[1]
        l = hsl[2]
    }

    val light1 get() = fromHsl(h, s, min(1f, 1.2f * l))
    val light2 get() = fromHsl(h, s, min(1f, 1.6f * l))
    val dark2 get() = fromHsl(h, s, max(0f, 0.2f * l))
    val toonStroke get() = fromHsl(h, s, max(0.12f, 0.4f * l))
    val neonStroke get() = fromHsl(h, min(1f, 1.2f * s), min(0.6f, l + 0.1f))

    private companion object {
        fun toHsl(color: Int): FloatArray {
            val r = Color.red(color) / 255f
            val g = Color.green(color) / 255f
            val b = Color.blue(color) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val l = (maxC + minC) / 2
            if (maxC == minC) return floatArrayOf(0f, 0f, l)
            val d = maxC - minC
            val s = if (l > 0.5f) d / (2 - maxC - minC) else d / (maxC + minC)
            val h = when (maxC) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } * 60
            return floatArrayOf(h, s, l)
        }

        fun fromHsl(h: Float, s: Float, l: Float): Int {
            if (s == 0f) {
                val v = Math.round(l * 255)
                return Color.rgb(v, v, v)
            }
            val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            val hk = h / 360f
            fun channel(t0: Float): Int {
                var t = t0
                if (t < 0) t += 1
                if (t > 1) t -= 1
                val v = when {
                    t < 1f / 6 -> p + (q - p) * 6 * t
                    t < 1f / 2 -> q
                    t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                    else -> p
                }
                return Math.round(v * 255).coerceIn(0, 255)
            }
            return Color.rgb(channel(hk + 1f / 3), channel(hk), channel(hk - 1f / 3))
        }
    }
}

/**
 * A name (or one Gummy letter) drawn with the view's own text layout, so it still wraps and
 * ellipsizes: a fill color, or a gradient across the whole name.
 */
internal class NameSpan(
    private val typeface: Typeface?,
    private val fill: Int,
    private val colors: IntArray?,
    private val mode: Int,
    private val name: String,
    /** Size relative to the name's own, for fonts Discord draws smaller or larger. */
    private val scale: Float = 1f,
    /** Extra letter spacing, in em. */
    private val spacing: Float = 0f,
    /** For [PRISM]: the shortest tile, in pixels. */
    private val prismMinCycle: Float = 0f,
    /** For [DIAGONAL]: the view, to find where the name's line is drawn. */
    view: TextView? = null,
) : MetricAffectingSpan(), StyledName {
    private val view = view?.let { WeakReference(it) }

    /** Finding the line measures the text, which calls back into this span. */
    private var locating = false

    override fun updateMeasureState(paint: TextPaint) {
        if (typeface != null) paint.typeface = typeface
        if (scale != 1f) paint.textSize *= scale
        if (spacing != 0f) paint.letterSpacing += spacing
    }

    override fun updateDrawState(paint: TextPaint) {
        updateMeasureState(paint)
        if (fill != 0) paint.color = fill
        val stops = colors
        if (stops == null || stops.size < 2) return
        val shader = when (mode) {
            PRISM -> prism(paint, stops)
            DIAGONAL -> diagonal(paint, stops)
            else -> null
        } ?: LinearGradient(0f, 0f, max(paint.measureText(name), 1f), 0f, stops, stopPositions(stops.size), Shader.TileMode.CLAMP)
        paint.color = Color.BLACK
        paint.shader = shader
    }

    /** Discord's Gradient and Glow: `linear-gradient(to bottom right)` over the name, stops at 10-90%. */
    private fun diagonal(paint: TextPaint, colors: IntArray): Shader? {
        if (locating) return null
        val textView = view?.get() ?: return null
        val layout = textView.layout ?: return null
        val text = textView.text as? Spanned ?: return null
        val start = text.getSpanStart(this)
        if (start < 0) return null
        locating = true
        try {
            val line = layout.getLineForOffset(start)
            val left = layout.getPrimaryHorizontal(start)
            val baseline = layout.getLineBaseline(line).toFloat()
            val top = baseline + paint.ascent()
            val height = paint.descent() - paint.ascent()
            return cornerGradient(left, top, paint.measureText(name), height, true, colors, stopPositions(colors.size))
        } finally {
            locating = false
        }
    }

    /**
     * Discord's Prism: the colors in order and back to the first, evenly spaced, repeating every
     * `max(<name length>ch, 38px)`. Discord scrolls this once when animated and stops where it started.
     */
    private fun prism(paint: TextPaint, colors: IntArray): Shader {
        // CSS's ch is the width of "0" in the font, without letter spacing.
        val letterSpacing = paint.letterSpacing
        paint.letterSpacing = 0f
        val ch = paint.measureText("0")
        paint.letterSpacing = letterSpacing
        val cycle = max(ch * name.length, prismMinCycle)
        val count = colors.size
        val stops = IntArray(count + 1) { colors[it % count] }
        val positions = FloatArray(count + 1) { it.toFloat() / count }
        return LinearGradient(0f, 0f, cycle, 0f, stops, positions, Shader.TileMode.REPEAT)
    }

    companion object {
        /** Role gradients: left to right across the name. */
        const val HORIZONTAL = 0
        const val DIAGONAL = 1
        const val PRISM = 2
    }
}

/**
 * Neon, Toon and Pop: these outline the text (`-webkit-text-stroke` with `paint-order: stroke
 * fill`), so the name is drawn several times. A replacement span can, but it doesn't wrap.
 */
internal class OutlineSpan(
    private val effect: Int,
    main: Int,
    private val typeface: Typeface?,
    private val scale: Float,
    private val spacing: Float,
    private val density: Float,
) : ReplacementSpan(), StyledName {
    private val shades = Shades(main)
    private val work = TextPaint()

    private fun styled(paint: Paint): TextPaint {
        work.set(paint)
        if (typeface != null) work.typeface = typeface
        if (scale != 1f) work.textSize *= scale
        if (spacing != 0f) work.letterSpacing += spacing
        work.shader = null
        work.clearShadowLayer()
        work.style = Paint.Style.FILL
        work.alpha = 255
        return work
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val p = styled(paint)
        if (fm != null) p.getFontMetricsInt(fm)
        return ceil(measure(text, start, end, p)).toInt()
    }

    /** The name's width, with FallbackFont's fonts for the characters they cover. */
    private fun measure(text: CharSequence, start: Int, end: Int, p: TextPaint): Float {
        val spanned = text as? Spanned ?: return p.measureText(text, start, end)
        var width = 0f
        var i = start
        while (i < end) {
            val next = spanned.nextSpanTransition(i, end, MetricAffectingSpan::class.java)
            width += FallbackFonts.applying(spanned, i, next, p, typeface != null) { p.measureText(text, i, next) }
            i = next
        }
        return width
    }

    /** Draws the name run by run, so FallbackFont's fonts still draw the characters they cover. */
    private fun drawRuns(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, y: Float, p: TextPaint) {
        val spanned = text as? Spanned
        if (spanned == null) {
            canvas.drawText(text, start, end, x, y, p)
            return
        }
        var left = x
        var i = start
        while (i < end) {
            val next = spanned.nextSpanTransition(i, end, MetricAffectingSpan::class.java)
            FallbackFonts.applying(spanned, i, next, p, typeface != null) {
                canvas.drawText(text, i, next, left, y, p)
                left += p.measureText(text, i, next)
            }
            i = next
        }
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val p = styled(paint)
        val em = p.textSize
        val baseline = y.toFloat()
        fun pass() = drawRuns(canvas, text, start, end, x, baseline, p)
        fun stroke(width: Float, color: Int) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = width
            p.strokeJoin = Paint.Join.ROUND
            p.color = color
            pass()
            p.style = Paint.Style.FILL
        }
        when (effect) {
            NameStyle.EFFECT_NEON -> {
                // White text, a saturated outline, and `text-shadow: 0 0 (4px + 0.12em)` in the main color.
                p.color = shades.main
                p.setShadowLayer(shadowRadius(4f * density + 0.12f * em), 0f, 0f, shades.main)
                pass()
                p.clearShadowLayer()
                stroke(1f * density + 0.04f * em, shades.neonStroke)
                p.color = Color.WHITE
                pass()
            }

            NameStyle.EFFECT_TOON -> {
                // A dark outline, then the text filled with the top quarter of a 400%-tall gradient.
                stroke(1.6f * density + 0.04f * em, shades.toonStroke)
                val boxTop = baseline + p.ascent()
                val height = p.descent() - p.ascent()
                p.color = Color.BLACK
                p.shader = LinearGradient(
                    0f,
                    boxTop,
                    0f,
                    boxTop + height * 4,
                    intArrayOf(
                        Color.WHITE, shades.light2, shades.light1, shades.main, shades.light2,
                        shades.main, Color.WHITE, shades.light2, shades.light1, shades.main,
                    ),
                    floatArrayOf(0f, 0.08f, 0.15f, 0.25f, 0.45f, 0.55f, 0.75f, 0.83f, 0.9f, 1f),
                    Shader.TileMode.CLAMP,
                )
                pass()
                p.shader = null
            }

            NameStyle.EFFECT_POP -> {
                // Behind: a copy 0.08em lower, filled with the top right of a 200% `to bottom left` gradient.
                val drop = 0.08f * em
                val width = measure(text, start, end, p)
                val height = p.descent() - p.ascent()
                val boxTop = baseline + drop + p.ascent()
                val light = shades.light1
                val main = shades.main
                p.color = Color.BLACK
                p.shader = cornerGradient(
                    x - width,
                    boxTop,
                    width * 2,
                    height * 2,
                    false,
                    intArrayOf(light, light, main, main, light, main, main),
                    floatArrayOf(0f, 0.06f, 0.2f, 0.5f, 0.56f, 0.7f, 1f),
                )
                drawRuns(canvas, text, start, end, x, baseline + drop, p)
                p.shader = null
                // In front: white text with a very dark outline.
                stroke(1.2f * density + 0.04f * em, shades.dark2)
                p.color = Color.WHITE
                pass()
            }

            else -> {
                p.color = shades.main
                pass()
            }
        }
    }

    /** CSS blurs a shadow with sigma = radius / 2; Skia uses sigma = 0.57735 * radius + 0.5. */
    private fun shadowRadius(cssRadius: Float) = max(0.01f, (cssRadius / 2 - 0.5f) / 0.57735f)
}
