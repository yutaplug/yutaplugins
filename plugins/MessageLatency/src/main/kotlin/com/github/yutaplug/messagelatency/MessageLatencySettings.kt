package com.github.yutaplug.messagelatency

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class MessageLatencySettings(private val plugin: MessageLatency) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("MessageLatency")
        setActionBarSubtitle("Plugin settings")

        section("Indicator")
        note(
            "Shows signal bars next to the time of messages that took a while to send, or before the name with IRC. " +
                "Tap them for details. Only works for messages received while the app is open. Without IRC, " +
                "only the first message of a group has a time to show it on.",
        )
        val input = TextInput(requireContext(), "Threshold in seconds", plugin.thresholdSeconds.toString())
        input.editText.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.toIntOrNull()?.takeIf { it > 0 } ?: return
                    plugin.settings.setInt(MessageLatency.KEY_THRESHOLD, value)
                }
            })
        }
        linearLayout.addView(
            input,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
        switch(
            MessageLatency.KEY_DETECT_KOTLIN,
            "Detect old Discord Android",
            "Flag users on the old Kotlin-based Discord Android app, which Aliucord is built on.",
            true,
        )
        switch(MessageLatency.KEY_SHOW_MILLIS, "Show milliseconds", "Include milliseconds in the delay.", false)
        switch(MessageLatency.KEY_IGNORE_SELF, "Ignore my messages", "Don't show the indicator on your own messages.", false)
        note("Changes apply to messages as they are shown again.")
    }

    private fun switch(key: String, title: String, subtitle: String, default: Boolean) {
        add(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = plugin.settings.getBool(key, default)
                setOnCheckedListener { plugin.settings.setBool(key, it) }
            },
        )
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                this.text = text
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            },
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }
}
