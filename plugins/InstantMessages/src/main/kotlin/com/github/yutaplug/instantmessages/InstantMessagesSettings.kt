package com.github.yutaplug.instantmessages

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class InstantMessagesSettings(private val plugin: InstantMessages) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()

        addView(Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Grey out sending messages",
            "Show messages in grey until Discord confirms they were sent.",
        ).apply {
            isChecked = plugin.greyPending
            setOnCheckedListener { plugin.setGreyPending(it) }
        })
        addView(Utils.createCheckedSetting(
            context,
            CheckedSetting.ViewType.SWITCH,
            "Remove animations",
            "Disable chat list and keyboard animations.",
        ).apply {
            isChecked = plugin.removeAnimations
            setOnCheckedListener { plugin.setRemoveAnimations(it) }
        })
    }
}
