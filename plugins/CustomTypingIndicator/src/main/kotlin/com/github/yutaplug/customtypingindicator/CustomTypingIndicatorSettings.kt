package com.github.yutaplug.customtypingindicator

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

/** Aliucord's plugin settings. Your own typing indicator is edited from Discord's User Settings instead. */
class CustomTypingIndicatorSettings(private val plugin: CustomTypingIndicator) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("CustomTypingIndicator")
        setActionBarSubtitle("Plugin settings")

        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply { text = "Typing bar" },
        )
        switch(
            CustomTypingIndicator.KEY_SHOW_AVATARS,
            "Show avatars",
            "Show each typing person's avatar next to their name.",
        )
        switch(
            CustomTypingIndicator.KEY_CLICKABLE_PROFILES,
            "Clickable profiles",
            "Tap a name in the typing bar to open that person's profile.",
        )
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Changing these asks you to restart Discord so they apply everywhere. " +
                    "To set your own typing indicator, go to User Settings > Typing Indicator."
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            },
        )
    }

    private fun switch(key: String, title: String, subtitle: String) {
        add(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = plugin.settings.getBool(key, false)
                setOnCheckedListener {
                    plugin.settings.setBool(key, it)
                    Utils.promptRestart()
                }
            },
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }
}
