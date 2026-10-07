package com.github.yutaplug.fallbackfont

import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class FallbackFontSettings(private val plugin: FallbackFont) : SettingsPage() {
    private val subtitles = HashMap<FontSlot, TextView>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("FallbackFont")
        setActionBarSubtitle("Custom fonts")

        section("Replace fonts")
        note("These fonts replace Discord's fonts. Text already on screen updates when it is shown again.")
        fontRow(FontSlot.TEXT)
        fontRow(FontSlot.EMOJI)
        fontRow(FontSlot.EMOJI_FALLBACK)
        note(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "The fallback emoji font is used for emoji the emoji font doesn't have."
            } else {
                "The fallback emoji font is used for emoji the emoji font doesn't have. On this Android " +
                    "version, combined emoji (like families or flags) use whichever font has their first part."
            },
        )
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Show emoji as text",
                "Draw emoji in messages and the emoji picker with the emoji font, or your device's, instead of " +
                    "Discord's Twemoji images. Emoji-only messages stay large. Applies to newly loaded messages.",
            ).apply {
                isChecked = plugin.emojiAsText
                setOnCheckedListener { plugin.emojiAsText = it }
            },
        )

        divider()
        section("Fallback fonts")
        note(
            "Characters your device fonts cannot display are drawn with these fonts instead of empty boxes. " +
                "The second font is used for characters the first one is missing.",
        )
        fontRow(FontSlot.FALLBACK)
        fontRow(FontSlot.FALLBACK_2)

        plugin.onFontChanged = { refresh() }
    }

    private fun refresh() {
        for ((slot, subtitle) in subtitles) subtitle.text = plugin.fontName(slot) ?: "Not set"
    }

    private fun fontRow(slot: FontSlot) {
        subtitles[slot] = action(slot.title, plugin.fontName(slot) ?: "Not set") { pick(slot) }
    }

    private fun pick(slot: FontSlot) {
        val hasFont = plugin.fontName(slot) != null
        SelectDialog().apply {
            title = slot.title
            items = if (hasFont) arrayOf("Choose font file", "Remove font") else arrayOf("Choose font file")
            onResultListener = { which ->
                if (which == 0) {
                    FontPickerFragment.open(Utils.appActivity, slot)
                } else {
                    plugin.removeFont(Utils.appContext, slot)
                    refresh()
                    Utils.showToast("${slot.title} removed")
                }
            }
        }.show(Utils.appActivity.supportFragmentManager, "FallbackFontSlot")
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

    /** Returns the subtitle view so it can be updated. */
    private fun action(title: String, subtitle: String, action: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
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
        val sub = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = subtitle
            background = null
            setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        row.addView(sub, LinearLayout.LayoutParams(-1, -2))
        add(row)
        return sub
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(1)),
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }

    override fun onDestroyView() {
        subtitles.clear()
        plugin.onFontChanged = null
        super.onDestroyView()
    }
}
