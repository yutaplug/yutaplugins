package com.github.yutaplug.markdownfix

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.lytefast.flexinput.R

/** Use compiled Discord 126.21 styles so resource-name normalization cannot drop styling. */
internal object DiscordSettingsUi {
    private fun styled(context: Context, style: Int) = ContextThemeWrapper(context, style)

    fun text(context: Context) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        setTextColor(color(context, "colorTextNormal"))
    }

    fun title(context: Context, value: String) = TextView(context, null, 0, R.i.UiKit_TextView_H1_Bold).apply {
        text = value
        setTextColor(color(context, "colorHeaderPrimary"))
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
    }

    fun header(context: Context, value: String) = TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
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

    fun button(context: Context, primary: Boolean = true) = MaterialButton(
        styled(context, if (primary) R.i.UiKit_Material_Button else R.i.UiKit_Material_Button_Transparent_Fit),
        null,
        0,
    ).apply {
        setTextColor(if (primary) Color.WHITE else color(context, "colorTextNormal"))
    }

    fun color(context: Context, name: String): Int = ColorCompat.getThemedColor(context, Utils.getResId(name, "attr"))

    fun divider(context: Context) = View(context).apply {
        setBackgroundColor(color(context, "colorBackgroundModifierAccent"))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun styleDialog(dialog: AlertDialog, context: Context) {
        val brand = ContextCompat.getColor(context, Utils.getResId("brand_500", "color"))
        dialog.create()
        dialog.window?.apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(color(context, "colorBackgroundPrimary"))
                    cornerRadius = dp(context, 4).toFloat()
                },
            )
            setLayout(minOf(dp(context, 440), context.resources.displayMetrics.widthPixels - dp(context, 32)), -2)
        }
        val titleId = Utils.getResId("alertTitle", "id")
        dialog.findViewById<TextView>(titleId)?.apply {
            setTextAppearance(context, R.i.UiKit_TextView_H1_Bold)
            setTextColor(color(context, "colorHeaderPrimary"))
        }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                setTextAppearance(context, R.i.UiKit_TextAppearance_Button)
                isAllCaps = false
                minimumHeight = dp(context, 48)
                setTextColor(
                    if (which ==
                        AlertDialog.BUTTON_POSITIVE
                    ) {
                        Color.WHITE
                    } else {
                        color(context, "colorTextNormal")
                    },
                )
                backgroundTintList = ColorStateList.valueOf(
                    if (which == AlertDialog.BUTTON_POSITIVE) brand else Color.TRANSPARENT,
                )
            }
        }
        (
            dialog
                .getButton(
                    AlertDialog.BUTTON_POSITIVE,
                )?.parent as? View
        )?.setBackgroundColor(color(context, "primary_630"))
    }

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
