package com.github.yutaplug.newthemes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils

/** The "Nitro Themes" section: header, description and a scrollable row of swatches. */
internal class NitroThemePicker(
    context: Context,
    header: TextView,
    description: TextView,
    private val divider: View,
    private val choose: (GradientPreset) -> Unit,
) {
    private class Swatch(val preset: GradientPreset, val circle: View, val drawable: SwatchDrawable)

    private val swatches = ArrayList<Swatch>()
    private val scroll = HorizontalScrollView(context)
    private var scrolled = false

    /** Every view this section adds to the page, in order. */
    val views: List<View>

    init {
        header.text = "Nitro Themes"
        description.text = "Synced to your account with Nitro. Without Nitro, the theme stays on this device."
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density + 0.5f).toInt()
        val ring = resolveColor(context, "colorControlBrandForegroundNew") ?: Color.rgb(0x58, 0x65, 0xf2)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
        }
        GradientPreset.shown.forEach { preset ->
            val drawable = SwatchDrawable(preset, ring, dp(3f).toFloat(), dp(3f).toFloat())
            val circle = View(context).apply {
                background = drawable
                layoutParams = LinearLayout.LayoutParams(dp(72f), dp(72f)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            // Same font and color as the description; Discord's own text style.
            val label = TextView(context).apply {
                text = preset.name
                typeface = description.typeface
                setTextColor(description.textColors)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, description.textSize)
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                setPadding(0, dp(6f), 0, 0)
            }
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(92f), ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                isFocusable = true
                contentDescription = preset.name
                addView(circle)
                addView(label)
                setOnClickListener { choose(preset) }
            }
            row.addView(item)
            swatches.add(Swatch(preset, circle, drawable))
        }
        scroll.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        views = listOf(divider, header, description, scroll)
    }

    fun update(selected: GradientPreset?) {
        swatches.forEach { swatch ->
            swatch.drawable.selected = swatch.preset === selected
            swatch.circle.invalidate()
        }
        // Bring the current preset into view once, like the mobile client.
        if (!scrolled && selected != null) {
            scrolled = true
            val index = swatches.indexOfFirst { it.preset === selected }
            scroll.post {
                val item = (scroll.getChildAt(0) as? ViewGroup)?.getChildAt(index) ?: return@post
                scroll.scrollTo(maxOf(0, item.left - (scroll.width - item.width) / 2), 0)
            }
        }
    }

    private fun resolveColor(context: Context, attr: String): Int? {
        val id = Utils.getResId(attr, "attr")
        if (id == 0) return null
        val value = TypedValue()
        if (!context.theme.resolveAttribute(id, value, true)) return null
        return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) value.data else null
    }
}

/** A gradient circle with Discord's selection ring around it. */
internal class SwatchDrawable(
    private val preset: GradientPreset,
    ringColor: Int,
    private val ringWidth: Float,
    private val gap: Float,
) : Drawable() {
    var selected = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = ringColor
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val inset = ringWidth + gap
        val size = minOf(bounds.width(), bounds.height()) - inset * 2
        fill.shader = preset.shader(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2, size, size)
    }

    override fun draw(canvas: Canvas) {
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        canvas.drawCircle(cx, cy, radius - ringWidth - gap, fill)
        if (selected) canvas.drawCircle(cx, cy, radius - ringWidth / 2, ring)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        ring.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        ring.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
