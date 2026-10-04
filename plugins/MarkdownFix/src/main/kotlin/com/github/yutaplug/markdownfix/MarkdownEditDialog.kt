package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog

/** A themed editor shared by the scale and bullet-color settings. */
internal object MarkdownEditDialog {
    fun show(
        context: Context,
        title: String,
        description: String,
        label: String,
        value: String,
        hint: String,
        inputType: Int,
        colorPicker: Boolean = false,
        validate: (String) -> String?,
        save: (String) -> Unit,
        reset: () -> Unit,
    ): AlertDialog {
        fun dp(value: Int) = MarkdownAppearance.dp(context, value)

        fun color(attribute: String, fallback: Int) = MarkdownAppearance.themedColor(context, attribute, fallback)

        fun shape(fill: Int, radius: Int = 4) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
        }

        fun text(value: String, size: Float, tint: Int) = DiscordSettingsUi.text(context).apply {
            text = value
            textSize = size
            setTextColor(tint)
        }

        val primary = color("colorHeaderPrimary", Color.WHITE)
        val muted = color("colorTextMuted", Color.LTGRAY)
        val danger = color("colorTextDanger", Color.rgb(237, 66, 69))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        content.addView(text(description, 14f, muted).apply { setPadding(0, dp(8), 0, dp(20)) })
        content.addView(text(label, 13f, primary).apply { setPadding(0, 0, 0, dp(8)) })

        val input = DiscordSettingsUi.input(context).apply {
            setSingleLine(true)
            setSelectAllOnFocus(true)
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(value)
            this.hint = hint
            textSize = 17f
            background = null
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(color("colorTextNormal", Color.WHITE))
            setHintTextColor(muted)
            contentDescription = label
        }
        content.addView(input, LinearLayout.LayoutParams(-1, -2))
        val error = text("", 13f, danger).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(error)
        val picker = if (colorPicker) {
            BulletColorPicker(context) { selected -> input.setText(selected) }.also {
                it.setColor(value)
                content.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            null
        }

        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = resources.displayMetrics.heightPixels * 3 / 5
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    maximum
                } else {
                    minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            background = shape(color("colorBackgroundPrimary", Color.rgb(54, 57, 63)), 4)
            clipToOutline = true
            addView(content)
        }
        val dialog = AlertDialog
            .Builder(context)
            .setCustomTitle(DiscordSettingsUi.title(context, title))
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ -> reset() }
            .setPositiveButton("Save", null)
            .create()

        fun submit() {
            val current = input.text.toString().trim()
            val problem = validate(current)
            if (problem != null) {
                error.text = problem
                error.visibility = View.VISIBLE
                input.requestFocus()
                return
            }
            save(current)
            dialog.dismiss()
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                error.visibility = View.GONE
                picker?.setColor(input.text.toString())
            }

            override fun afterTextChanged(text: Editable?) {}
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        // Inflate AlertDialog's content before sizing: its onCreate installs the default window width.
        // Configure the final bounds before the window is attached to avoid a second visible layout.
        DiscordSettingsUi.styleDialog(dialog, context)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
        }
        input.requestFocus()
        dialog.show()
        return dialog
    }
}
