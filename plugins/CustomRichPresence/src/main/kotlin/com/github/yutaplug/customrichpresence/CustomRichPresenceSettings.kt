package com.github.yutaplug.customrichpresence

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.google.android.material.button.MaterialButton

class CustomRichPresenceSettings(private val pluginSettings: SettingsAPI) : BottomSheet() {
    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        getLinearLayout().setPadding(20, 20, 20, 20)

        val enabled = Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Enable custom Rich Presence",
            "Uses the activity configured below while Aliucord is connected.",
        )
        enabled.isChecked = pluginSettings.getBool(KEY_ENABLED, false)
        enabled.setOnCheckedListener { checked ->
            pluginSettings.setBool(KEY_ENABLED, checked)
            if (checked) {
                PresenceController.startTimerIfNeeded()
                PresenceController.apply()
            } else {
                PresenceController.clear()
            }
        }
        addView(enabled)

        addView(
            textInput(context, "Application ID (optional)", KEY_APPLICATION_ID).apply {
                getEditText().inputType = InputType.TYPE_CLASS_NUMBER
            },
        )
        addView(textInput(context, "Activity name", KEY_NAME))

        val typeLabels = listOf("Playing", "Streaming", "Listening", "Watching", "Competing")
        val typeValues = listOf(0, 1, 2, 3, 5)
        val radios = typeLabels.map {
            Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, it, null)
        }
        val radioManager = RadioManager(radios)
        val selectedIndex = typeValues.indexOf(pluginSettings.getInt(KEY_TYPE, 0)).coerceAtLeast(0)
        radios[selectedIndex].isChecked = true
        radioManager.a(radios[selectedIndex])
        radios.forEachIndexed { index, radio ->
            radio.e {
                pluginSettings.setInt(KEY_TYPE, typeValues[index])
                radioManager.a(radio)
            }
            addView(radio)
        }

        addView(textInput(context, "Details (optional)", KEY_DETAILS))
        addView(textInput(context, "State (optional)", KEY_STATE))

        val elapsed = Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Show elapsed time",
            "Adds a running start timestamp to the activity.",
        )
        elapsed.isChecked = pluginSettings.getBool(KEY_ELAPSED, true)
        elapsed.setOnCheckedListener { checked ->
            pluginSettings.setBool(KEY_ELAPSED, checked)
            if (checked) PresenceController.startTimerIfNeeded()
        }
        addView(elapsed)

        addView(textInput(context, "Large image key or URL (optional)", KEY_LARGE_IMAGE))
        addView(textInput(context, "Large image text (optional)", KEY_LARGE_TEXT))
        addView(textInput(context, "Small image key or URL (optional)", KEY_SMALL_IMAGE))
        addView(textInput(context, "Small image text (optional)", KEY_SMALL_TEXT))

        MaterialButton(context).also { button ->
            button.text = "Apply presence"
            button.setOnClickListener {
                PresenceController.startTimerIfNeeded()
                PresenceController.apply(showErrors = true)
            }
            addView(button)
        }

        MaterialButton(context).also { button ->
            button.text = "Clear presence"
            button.setOnClickListener { PresenceController.clear(showToast = true) }
            addView(button)
        }
    }

    private fun textInput(context: android.content.Context, hint: String, key: String): TextInput {
        return TextInput(
            context,
            hint,
            pluginSettings.getString(key, ""),
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    pluginSettings.setString(key, s?.toString().orEmpty())
                }
            },
        )
    }
}
