package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** A palette of preset colors; any other color can still be typed as hex. */
internal class BulletColorPicker(context: Context, private val onColor: (String) -> Unit) : LinearLayout(context) {
    private val swatches = ArrayList<Pair<String, View>>()
    private val ring = MarkdownAppearance.themedColor(context, "colorHeaderPrimary", Color.WHITE)

    init {
        orientation = VERTICAL
        setPadding(0, dp(12), 0, 0)
        var index = 0
        var row: LinearLayout? = null
        while (index < PRESETS.size) {
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).also { addView(it, LayoutParams(-1, -2)) }
            }
            val color = PRESETS[index]
            val swatch = View(context).apply {
                contentDescription = color
                isFocusable = true
                setOnClickListener {
                    select(color)
                    onColor(color)
                }
            }
            // Equal-width cells keep the grid aligned on any dialog width.
            val cell = FrameLayout(context).apply {
                addView(swatch, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            }
            row!!.addView(cell, LayoutParams(0, dp(48), 1f))
            swatches.add(color to swatch)
            index++
        }
        select(null)
    }

    fun setColor(value: String) = select(MarkdownAppearance.normalizeColor(value))

    private fun select(selected: String?) {
        for ((color, view) in swatches) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color))
                if (color == selected) {
                    setStroke(dp(3), ring)
                } else {
                    setStroke(dp(1), MarkdownAppearance.themedColor(context, "colorBackgroundModifierAccent", Color.GRAY))
                }
            }
        }
    }

    private fun dp(value: Int) = MarkdownAppearance.dp(context, value)

    companion object {
        private const val COLUMNS = 6
        private val PRESETS = listOf(
            "#5865F2",
            "#3BA55C",
            "#FAA61A",
            "#ED4245",
            "#EB459E",
            "#9B59B6",
            "#00B0F4",
            "#1ABC9C",
            "#FEE75C",
            "#E67E22",
            "#FFFFFF",
            "#99AAB5",
        )
    }
}
