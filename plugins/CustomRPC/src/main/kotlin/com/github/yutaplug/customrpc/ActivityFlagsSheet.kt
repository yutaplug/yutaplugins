package com.github.yutaplug.customrpc

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import android.view.ContextThemeWrapper
import com.lytefast.flexinput.R

/** Multi-select for activity flags. Aliucord's SelectDialog only picks one item, so this uses its themed bottom sheet. */
class ActivityFlagsSheet(private val plugin: CustomRPC, private val onChanged: () -> Unit) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = requireContext()
        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = "Activity flags"
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
        addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Configure activity capabilities. Joining and spectating also require secrets."
                background = null
            },
        )
        val options = ArrayList<CheckedSetting>()
        ActivityFlags.labels.forEachIndexed { index, label ->
            val flag = ActivityFlags.values[index]
            options += Utils.createCheckedSetting(context, CheckedSetting.ViewType.CHECK, label, null).apply {
                isChecked = plugin.flags() and flag != 0
                setOnCheckedListener { checked ->
                    plugin.setFlags(if (checked) plugin.flags() or flag else plugin.flags() and flag.inv())
                    onChanged()
                }
            }
            addView(options.last())
        }
        addView(
            MaterialButton(ContextThemeWrapper(context, R.i.UiKit_Material_Button_Secondary), null, 0).apply {
                text = "Reset to defaults"
                setOnClickListener {
                    plugin.setFlags(DEFAULT_FLAGS)
                    options.forEachIndexed { index, option ->
                        option.isChecked = DEFAULT_FLAGS and ActivityFlags.values[index] != 0
                    }
                    onChanged()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    val margin = DiscordSettingsUi.dp(context, 16)
                    setMargins(margin, DiscordSettingsUi.dp(context, 8), margin, margin)
                }
            },
        )
    }

    companion object {
        const val DEFAULT_FLAGS = 257
    }
}
