package com.github.yutaplug.markdownfix

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog

/** A themed single-value editor, with an optional color preview and picker. */
internal object MarkdownEditDialog {
    fun show(
        context: Context,
        title: String,
        label: String,
        value: String,
        hint: String,
        inputType: Int,
        colorPicker: Boolean = false,
        validate: (String) -> String?,
        save: (String) -> Unit,
        defaultValue: String,
    ): AlertDialog {
        fun dp(value: Int) = MarkdownAppearance.dp(context, value)

        fun color(attribute: String, fallback: Int) = MarkdownAppearance.themedColor(context, attribute, fallback)

        val muted = color("colorTextMuted", Color.LTGRAY)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        val field = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val swatch = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), muted)
        }
        if (colorPicker) {
            field.addView(
                View(context).apply {
                    background = swatch
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) },
            )
        }
        val input = DiscordSettingsUi.input(context).apply {
            setSingleLine(true)
            setSelectAllOnFocus(true)
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(value)
            this.hint = hint
            textSize = 16f
            background = null
            minimumHeight = dp(48)
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(color("colorTextNormal", Color.WHITE))
            setHintTextColor(muted)
            contentDescription = label
        }
        field.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(field, LinearLayout.LayoutParams(-1, -2))
        content.addView(DiscordSettingsUi.divider(context), LinearLayout.LayoutParams(-1, dp(1)))
        val error = DiscordSettingsUi.text(context).apply {
            textSize = 12f
            setTextColor(color("colorTextDanger", Color.rgb(237, 66, 69)))
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(error)

        fun preview() {
            MarkdownAppearance.normalizeColor(input.text.toString())?.let { swatch.setColor(Color.parseColor(it)) }
        }
        val picker = if (colorPicker) {
            BulletColorPicker(context) { selected -> input.setText(selected) }.also {
                it.setColor(value)
                content.addView(it, LinearLayout.LayoutParams(-1, -2))
            }
        } else {
            null
        }
        preview()

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
            addView(content)
        }
        val dialog = AlertDialog
            .Builder(context)
            .setCustomTitle(DiscordSettingsUi.title(context, title))
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Default", null)
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
                if (colorPicker) preview()
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
        // Fills in the default instead of saving it, so Cancel still keeps the current value.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { input.setText(defaultValue) }
        dialog.window?.apply {
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
            )
        }
        dialog.show()
        return dialog
    }
}
