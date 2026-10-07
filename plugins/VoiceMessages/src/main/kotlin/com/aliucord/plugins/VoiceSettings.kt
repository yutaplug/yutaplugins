package com.aliucord.plugins

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.Utils
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R
import java.util.Locale

class VoiceSettings(private val settings: SettingsAPI) : SettingsPage() {
    // FragmentManager recreates pages through their public empty constructor.
    constructor() : this(SettingsAPI("VoiceMessages"))

    private var backgroundRow: View? = null
    private var microphoneRow: View? = null
    private var colorDialog: DiscordDialog? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Voice messages")
        setActionBarSubtitle("Plugin settings")
        linearLayout.setPadding(0, 0, 0, dp(16))
        linearLayout.setBackgroundColor(themeColor(requireContext(), "colorBackgroundPrimary", Color.DKGRAY))

        val recording = section("Recording", first = true)
        toggle(recording, "disableSelectionPopup", "Hold to record", "Release to send, slide away to cancel.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toggle(recording, "legacyOgg", "Try Ogg / Opus", "Experimental. Falls back to M4A.")
        }

        val quality = section("Audio quality")
        qualityOptions(quality)

        val appearance = section("Appearance")
        toggle(appearance, "integratedButton", "Place inside the chatbox", "Grey microphone next to the emoji button.")
        backgroundRow = colorSetting(appearance, "buttonColor", "Button color", VoiceMessages.DEFAULT_BUTTON_COLOR)
        microphoneRow =
            colorSetting(appearance, "buttonIconColor", "Microphone color", VoiceMessages.DEFAULT_ICON_COLOR)
        toggle(appearance, "translucentButton", "Soft opacity", "Make the button semi-transparent.")
        updateColorAvailability()
    }

    private fun updateColorAvailability() {
        val integrated = settings.getBool("integratedButton", false)
        listOf(backgroundRow, microphoneRow).forEach { row ->
            row?.isEnabled = !integrated
            row?.alpha = if (integrated) 0.45f else 1f
        }
    }

    private fun section(title: String, first: Boolean = false): LinearLayout {
        if (!first) {
            linearLayout.addView(DiscordSettingsUi.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
        }
        linearLayout.addView(DiscordSettingsUi.header(requireContext(), title))
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            linearLayout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun toggle(parent: LinearLayout, key: String, title: String, description: String) {
        parent.addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, description).apply {
                isChecked = settings.getBool(key, false)
                setOnCheckedListener { value ->
                    settings.setBool(key, value)
                    if (key == "disableSelectionPopup" || key == "integratedButton") {
                        VoiceMessages.instance?.refreshSettings()
                    } else if (key == "translucentButton") {
                        VoiceMessages.instance?.refreshAppearance()
                    }
                    updateColorAvailability()
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private fun qualityOptions(parent: LinearLayout) {
        val selected = settings.getInt("audioQuality", 128).takeIf { it in listOf(64, 128, 192) } ?: 128
        val options = mutableListOf<Pair<CheckedSetting, Int>>()
        var updating = false
        val choices = listOf(
            Triple("High", "192 kbps", 192),
            Triple("Balanced", "128 kbps · Recommended", 128),
            Triple("Compact", "64 kbps · Smallest files", 64),
        )
        choices.forEach { (name, description, value) ->
            val option = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, name, description)
            option.isChecked = selected == value
            option.setOnCheckedListener { checked ->
                if (!updating) {
                    updating = true
                    if (checked) settings.setInt("audioQuality", value)
                    val quality = settings.getInt("audioQuality", 128)
                    options.forEach { (button, bitrate) -> button.isChecked = bitrate == quality }
                    updating = false
                }
            }
            options.add(option to value)
            parent.addView(option, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * A settings row built like Discord's Account settings: a `Compound_Left` label and a
     * `Compound_Right` value showing the color swatch and hex code.
     */
    private fun colorSetting(parent: LinearLayout, key: String, title: String, defaultColor: Int): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            selectable(this)
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Left).apply { text = title },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        // Wrap the value so its swatch stays next to the hex code instead of at the start of the free space.
        val value = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right).apply {
            compoundDrawablePadding = dp(8)
        }
        row.addView(value, LinearLayout.LayoutParams(-2, -2))

        fun update() {
            val color = savedColor(key, defaultColor)
            value.text = hex(color)
            value.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(color, 14), null, null, null)
            row.contentDescription = "$title, ${hex(color)}. Change color"
        }
        update()
        row.setOnClickListener {
            if (row.isEnabled) showColorDialog(key, title, defaultColor) { update() }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun showColorDialog(key: String, title: String, defaultColor: Int, update: () -> Unit) {
        if (settings.getBool("integratedButton", false) || colorDialog?.dialog?.isShowing == true) return
        val context = requireContext()
        val picker = ColorPickerView(context, savedColor(key, defaultColor))
        val presets = mutableListOf<Pair<View, Int>>()
        val field = DiscordSettingsUi.field(
            context,
            "Hex color",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        val input = field.editText!!.apply {
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(7))
        }
        var updating = false

        fun updatePresets(color: Int) {
            presets.forEach { (view, preset) -> view.background = swatch(preset, 32, selected = preset == color) }
        }

        fun setDraft(color: Int, updatePicker: Boolean = true) {
            updating = true
            input.setText(hex(color))
            // Keep the picker's HSV state when it emitted the change. Converting an
            // achromatic RGB color back to HSV would discard the selected hue.
            if (updatePicker) picker.color = color
            updatePresets(color)
            updating = false
        }

        fun hideKeyboard() {
            input.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(text: Editable?) {
                if (updating) return
                parseColor(text.toString())?.let { color ->
                    picker.color = color
                    updatePresets(color)
                }
            }
        })
        picker.onColorChanged = { color ->
            setDraft(color, updatePicker = false)
            hideKeyboard()
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val presetRow = LinearLayout(context)
        listOf(
            defaultColor,
            Color.rgb(88, 101, 242),
            Color.WHITE,
            Color.rgb(181, 186, 193),
            Color.rgb(235, 69, 158),
            Color.rgb(87, 242, 135),
            Color.rgb(254, 231, 92),
            Color.rgb(237, 66, 69),
        ).distinct().forEach { color ->
            val swatchView = View(context).apply {
                contentDescription = "Use ${hex(color)}"
                isFocusable = true
                setOnClickListener {
                    setDraft(color)
                    hideKeyboard()
                }
            }
            presets.add(swatchView to color)
            presetRow.addView(
                FrameLayout(context).apply {
                    addView(swatchView, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        }
        content.addView(presetRow, LinearLayout.LayoutParams(-1, -2))
        content.addView(
            picker,
            LinearLayout.LayoutParams(-1, minOf(dp(160), resources.displayMetrics.heightPixels / 3).coerceAtLeast(dp(96)))
                .apply { topMargin = dp(8) },
        )
        content.addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setDraft(savedColor(key, defaultColor))

        fun save(): Boolean {
            val selected = parseColor(input.text.toString())
            if (selected == null) {
                field.error = "Enter six hex digits, like #5865F2"
                input.requestFocus()
                return false
            }
            settings.setInt(key, selected)
            update()
            VoiceMessages.instance?.refreshAppearance()
            return true
        }

        val dialog = DiscordDialog(context, title)
            .content(content)
            .neutral("Default") {
                setDraft(defaultColor)
                hideKeyboard()
            }.negative("Cancel")
            .positive("Save") { save() }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                if (save()) dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.dialog.setOnDismissListener {
            hideKeyboard()
            if (colorDialog === dialog) colorDialog = null
        }
        colorDialog = dialog
        dialog.show(input = true)
        content.requestFocus()
    }

    override fun onDestroyView() {
        colorDialog?.dismiss()
        colorDialog = null
        backgroundRow = null
        microphoneRow = null
        super.onDestroyView()
    }

    private fun swatch(color: Int, size: Int, selected: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (selected) {
            setStroke(dp(3), themeColor(requireContext(), "colorHeaderPrimary", Color.WHITE))
        } else {
            setStroke(dp(1), themeColor(requireContext(), "colorBackgroundModifierAccent", Color.GRAY))
        }
        setSize(dp(size), dp(size))
    }

    private fun selectable(view: View) {
        val value = TypedValue()
        if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true) &&
            value.resourceId != 0
        ) {
            view.background = ContextCompat.getDrawable(view.context, value.resourceId)
        }
    }

    private fun savedColor(key: String, default: Int) =
        settings.getInt(key, default).let { if (Color.alpha(it) == 0) default else it }

    private fun parseColor(value: String): Int? {
        val raw = value.trim().removePrefix("#")
        return if (raw.matches(Regex("[0-9a-fA-F]{6}"))) Color.parseColor("#$raw") else null
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

internal fun themeColor(context: Context, name: String, fallback: Int): Int {
    val id = Utils.getResId(name, "attr")
    if (id == 0) return fallback
    val value = TypedValue()
    if (!context.theme.resolveAttribute(id, value, true)) return fallback
    if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
    return try {
        if (value.resourceId == 0) fallback else ContextCompat.getColor(context, value.resourceId)
    } catch (_: RuntimeException) {
        fallback
    }
}
