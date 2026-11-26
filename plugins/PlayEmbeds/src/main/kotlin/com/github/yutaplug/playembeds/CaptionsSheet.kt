package com.github.yutaplug.playembeds

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

/** The caption languages as Discord's radio rows in Aliucord's bottom sheet; [onPick] gets the row index. */
internal class CaptionsSheet(
    private val names: List<String>,
    private val selected: Int,
    private val onPick: (Int) -> Unit,
) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Captions"
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
        names.forEachIndexed { index, name ->
            val row = Utils.createCheckedSetting(context, CheckedSetting.ViewType.RADIO, name, null)
            row.isChecked = index == selected
            row.e {
                dismiss()
                onPick(index)
            }
            addView(row)
        }
    }
}
