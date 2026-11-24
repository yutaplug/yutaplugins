package com.github.yutaplug.fixes.markdown

import android.content.Context
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

/** Use compiled Discord 126.21 styles so resource-name normalization cannot drop styling. */
internal object DiscordSettingsUi {
    fun text(context: Context) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        setTextColor(color(context, "colorTextNormal"))
    }

    fun color(context: Context, name: String): Int = ColorCompat.getThemedColor(context, Utils.getResId(name, "attr"))

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
