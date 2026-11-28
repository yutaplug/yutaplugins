package com.github.yutaplug.profileeffects

import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Keeps chat author names whole: when a name doesn't fit next to its tag and timestamp (display
 * name fonts are wider than Discord's, and plugins add pronouns or local times to the timestamp),
 * it is drawn a little smaller instead of being cut short, down to [MIN_SCALE] of its normal size.
 * Past that it is cut short as usual. The timestamp is left alone.
 */
internal class NameFit {
    /** Each view's normal text size, in pixels, from before this ever changed it. */
    private val baseSizes = WeakHashMap<TextView, Float>()

    private val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        val text = view as? TextView ?: return@OnLayoutChangeListener
        // Shrinking requests another layout, which runs this again until the name fits.
        view.post { fit(text) }
    }

    /** Right after Discord set the name: back to the normal size, then shrink once laid out if needed. */
    fun bind(view: TextView) {
        val base = baseSizes.getOrPut(view) {
            view.addOnLayoutChangeListener(listener)
            view.textSize
        }
        if (view.textSize != base) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
    }

    private fun fit(view: TextView) {
        val base = baseSizes[view] ?: return
        val layout = view.layout ?: return
        // Only single-line names are cut short; others (FullUsernames) wrap instead.
        if (layout.lineCount != 1 || layout.getEllipsisCount(0) == 0) return
        val available = view.width - view.totalPaddingLeft - view.totalPaddingRight
        if (available <= 0) return
        val needed = Layout.getDesiredWidth(view.text, view.paint)
        if (needed <= available) return
        val current = view.textSize
        val min = base * MIN_SCALE
        if (current <= min) return
        // A hair smaller than the exact fit, since widths don't scale perfectly with size.
        val size = maxOf(min, current * available / needed * 0.99f)
        if (size < current) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }

    /** Restores every name's normal size, for when the plugin stops. */
    fun clear() {
        for ((view, base) in ArrayList(baseSizes.entries)) {
            view.removeOnLayoutChangeListener(listener)
            if (view.textSize != base) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
        }
        baseSizes.clear()
    }

    private companion object {
        const val MIN_SCALE = 0.8f
    }
}
