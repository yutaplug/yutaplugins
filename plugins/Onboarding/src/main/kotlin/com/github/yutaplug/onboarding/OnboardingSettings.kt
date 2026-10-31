package com.github.yutaplug.onboarding

import android.view.View
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class OnboardingSettings(private val plugin: Onboarding) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Onboarding")
        addView(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Show Channels & Roles in channel list",
                "Show the Channels & Roles or Browse Channels row at the top of the server channel list.",
            ).apply {
                isChecked = plugin.showChannelsRow
                setOnCheckedListener { plugin.showChannelsRow = it }
            },
        )
    }
}
