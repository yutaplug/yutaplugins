package com.aliucord.plugins

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.utilities.drawable.DrawableCompat
import com.lytefast.flexinput.R

/** An action menu rather than a radio selector: each row starts a different flow. */
class VoiceModeSheet : BottomSheet() {
    internal var onSelect: ((Int) -> Unit)? = null
    private var selected = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // An Android-restored sheet has no live action callback. Close it so it cannot
        // block opening a new menu or leave the user with buttons that do nothing.
        if (onSelect == null) {
            dismissAllowingStateLoss()
            return
        }
        val context = requireContext()
        linearLayout.setPadding(0, 0, 0, dp(8))
        linearLayout.addView(
            TextView(context, null, 0, R.i.UiKit_TextView_H1_Bold).apply {
                text = "Send a voice message"
                setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        linearLayout.addView(DiscordSettingsUi.divider(context), LinearLayout.LayoutParams(-1, dp(1)))
        addAction(1, "Record a message", "ic_mic_grey_24dp")
        addAction(2, "Choose an audio file", "ic_file_upload_24dp")
    }

    /** Clones a row from Discord's channel action sheet so text, padding and touch feedback are native. */
    private fun addAction(choice: Int, title: String, iconName: String) {
        val context = requireContext()
        val template = LayoutInflater
            .from(context)
            .inflate(Utils.getResId("widget_channels_list_item_actions", "layout"), null)
        val row = template.findViewById<TextView>(Utils.getResId("text_action_mark_as_read", "id")) ?: return
        (row.parent as? ViewGroup)?.removeView(row)
        row.id = View.generateViewId()
        row.text = title
        val icon = Utils.getResId(iconName, "drawable")
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (icon == 0) {
                null
            } else {
                DrawableCompat.getDrawable(context, icon, themeColor(context, "colorInteractiveNormal", Color.LTGRAY))
            },
            null,
            null,
            null,
        )
        row.setOnClickListener {
            if (!selected) {
                val callback = onSelect ?: return@setOnClickListener
                selected = true
                callback(choice)
            }
        }
        linearLayout.addView(row)
    }

    override fun onDestroyView() {
        onSelect = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
