package com.github.yutaplug.swipetoreplyandedit

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting

class SwipeToReplyAndEditSettings(private val plugin: SwipeToReplyAndEdit) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()

        val holdInput = TextInput(context, "Hold duration to edit (ms)", plugin.holdMs.toString())
        holdInput.editText.apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.toIntOrNull()?.takeIf { it >= 0 } ?: return
                    plugin.holdMs = value
                }
            })
        }
        holdInput.visibility = if (plugin.editOwn) View.GONE else View.VISIBLE

        addView(
            Utils.createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Instantly edit own messages",
                "Swiping one of your own messages edits it right away. When off, swiping replies and holding the swipe for a moment edits.",
            ).apply {
                isChecked = plugin.editOwn
                setOnCheckedListener {
                    plugin.editOwn = it
                    holdInput.visibility = if (it) View.GONE else View.VISIBLE
                }
            },
        )
        holdInput.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        addView(holdInput)
    }
}
