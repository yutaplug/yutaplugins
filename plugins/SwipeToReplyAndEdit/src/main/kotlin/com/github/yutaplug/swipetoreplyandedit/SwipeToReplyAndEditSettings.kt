package com.github.yutaplug.swipetoreplyandedit

import android.os.Bundle
import android.view.View
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class SwipeToReplyAndEditSettings(private val plugin: SwipeToReplyAndEdit) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        addView(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Swipe to edit own messages",
                "Swiping one of your own messages edits it instead of replying to it.",
            ).apply {
                isChecked = plugin.editOwn
                setOnCheckedListener { plugin.editOwn = it }
            },
        )
    }
}
