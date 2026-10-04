package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.google.android.material.textfield.TextInputEditText
import com.lytefast.flexinput.R

/** Use compiled Discord 126.21 styles so resource-name normalization cannot drop styling. */
internal object DiscordSettingsUi {
    private fun styled(context: Context, style: Int) = ContextThemeWrapper(context, style)

    fun text(context: Context) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        setTextColor(color(context, "colorTextNormal"))
    }

    fun header(context: Context, value: String) =
        TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = value
            setTextColor(color(context, "colorHeaderSecondary"))
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 8))
            isAllCaps = true
            background = null
        }

    fun input(context: Context) = TextInputEditText(styled(context, R.i.UiKit_TextInputLayout_EditText)).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = true
        background = null
        setOnClickListener {
            requestFocus()
            post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun color(context: Context, name: String): Int = ColorCompat.getThemedColor(context, Utils.getResId(name, "attr"))

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(color(context, "colorBackgroundModifierAccent"))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
