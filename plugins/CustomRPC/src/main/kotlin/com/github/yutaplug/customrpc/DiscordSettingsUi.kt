package com.github.yutaplug.customrpc

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.R

/** Use compiled Discord 126.21 styles so resource-name normalization cannot drop styling. */
internal object DiscordSettingsUi {
    /** Discord's brand button, or its red outline button for destructive actions. */
    fun button(context: Context, primary: Boolean = true) = MaterialButton(
        ContextThemeWrapper(context, if (primary) R.i.UiKit_Material_Button else R.i.UiKit_Material_Button_Red_Outline),
        null,
        0,
    ).apply {
        if (primary) setTextColor(Color.WHITE)
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
