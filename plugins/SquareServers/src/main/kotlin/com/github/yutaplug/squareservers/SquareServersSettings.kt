package com.github.yutaplug.squareservers

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.lytefast.flexinput.R

class SquareServersSettings(private val plugin: SquareServers) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("SquareServers")
        setActionBarSubtitle("Plugin settings")
        val context = requireContext()

        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Corner radius"
                background = null
            },
        )

        // A brand-colored squircle the size of a server icon, like a selected server.
        val size = DimenUtils.dpToPx(46)
        val preview = View(context).apply {
            setBackgroundResource(Utils.getResId("drawable_squircle_brand_500", "drawable"))
        }
        val value = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right).apply {
            minWidth = DimenUtils.dpToPx(48)
            gravity = Gravity.END
        }

        fun update(radius: Int) {
            (preview.background.mutate() as? GradientDrawable)?.cornerRadius = DimenUtils.dpToPx(radius).toFloat()
            value.text = "$radius dp"
        }

        val bar = SeekBar(context, null, 0, R.i.UiKit_SeekBar).apply {
            max = SquareServers.MAX_RADIUS
            progress = plugin.radiusDp
            contentDescription = "Corner radius"
            // The UiKit style has no horizontal padding, which clips half the thumb at either end.
            val inset = maxOf(thumb?.intrinsicWidth ?: 0, DimenUtils.dpToPx(20)) / 2
            setPadding(inset, paddingTop, inset, paddingBottom)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = update(progress)

                    override fun onStartTrackingTouch(bar: SeekBar?) {}

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        plugin.radiusDp = bar.progress
                    }
                },
            )
        }

        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val margin = DimenUtils.dpToPx(16)
            setPadding(margin, DimenUtils.dpToPx(8), margin, DimenUtils.dpToPx(8))
            addView(preview, LinearLayout.LayoutParams(size, size).apply { marginEnd = margin })
            addView(bar, LinearLayout.LayoutParams(0, DimenUtils.dpToPx(48), 1f))
            addView(value)
        }
        addView(row)
        update(plugin.radiusDp)

        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "0 dp is fully square, ${SquareServers.MAX_RADIUS} dp is a circle. " +
                    "Default is ${SquareServers.DEFAULT_RADIUS} dp, like Discord's selected server icon."
                background = null
            },
        )

        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = "Reset to default"
                val icon = Utils.getResId("ic_refresh_white_a60_24dp", "drawable")
                if (icon != 0) setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                setOnClickListener {
                    plugin.radiusDp = SquareServers.DEFAULT_RADIUS
                    bar.progress = SquareServers.DEFAULT_RADIUS
                }
            },
        )
    }
}
