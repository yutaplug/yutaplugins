package com.aliucord.plugins

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.google.android.material.textfield.TextInputLayout
import com.lytefast.flexinput.R

/** Use compiled Discord 126.21 styles so resource-name normalization cannot drop styling. */
internal object DiscordSettingsUi {
    fun text(context: Context) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        setTextColor(color(context, "colorTextNormal"))
    }

    fun header(context: Context, value: String) =
        TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
            text = value
            background = null
            setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
        }

    /**
     * Discord's dialog text field: the `view_input_modal_text_no_suggestions` layout used by
     * WidgetNoticeDialog's input modals. Editing clears a shown error.
     */
    fun field(context: Context, hint: String, inputType: Int): TextInputLayout {
        val layout = LayoutInflater
            .from(context)
            .inflate(Utils.getResId("view_input_modal_text_no_suggestions", "layout"), null) as TextInputLayout
        layout.hint = hint
        layout.editText?.apply {
            this.inputType = inputType
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    if (layout.error != null) layout.error = null
                }

                override fun afterTextChanged(text: Editable?) {}
            })
        }
        return layout
    }

    fun color(context: Context, name: String): Int = ColorCompat.getThemedColor(context, Utils.getResId(name, "attr"))

    fun divider(context: Context) = View(context, null, 0, R.i.UiKit_Settings_Divider)

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
