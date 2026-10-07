package com.github.yutaplug.fallbackfont

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.lytefast.flexinput.R

class FallbackFontSettings(private val plugin: FallbackFont) : SettingsPage() {
    private var status: TextView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("FallbackFont")
        setActionBarSubtitle("Fallback font")

        section("Fallback font")
        note(
            "Characters your device fonts cannot display are drawn with this font instead of empty boxes. " +
                "Text already on screen updates when it is shown again.",
        )
        status = note("")
        refreshStatus()
        plugin.onFontChanged = { refreshStatus() }
        action("Choose font file", "Select a TTF or OTF file") {
            FontPickerFragment.open(requireActivity())
        }
        action("Remove font", "Stop using a fallback font") {
            plugin.removeFont(requireContext())
            refreshStatus()
            Utils.showToast("Fallback font removed")
        }
    }

    private fun refreshStatus() {
        status?.text = plugin.fontName?.let { "Current font: $it" } ?: "No font selected"
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            this.text = text
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    private fun action(title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            contentDescription = "$title. $subtitle"
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = subtitle
                background = null
                setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        add(row)
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }

    override fun onDestroyView() {
        status = null
        plugin.onFontChanged = null
        super.onDestroyView()
    }
}
