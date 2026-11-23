package com.github.yutaplug.playembeds

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

class PlayEmbedsSettings(private val plugin: PlayEmbeds) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("PlayEmbeds")
        setActionBarSubtitle("Plugin settings")

        section("Videos")
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Replace the whole embed card",
                "Play videos in place of the whole embed, sized to the video, instead of only in its preview image.",
            ).apply {
                isChecked = plugin.replaceWholeCard
                setOnCheckedListener { plugin.settings.setBool(PlayEmbeds.KEY_WHOLE_CARD, it) }
            },
        )
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Rotate fullscreen to landscape",
                "Rotate the screen to landscape when you open a landscape video in fullscreen. " +
                    "Discord reloads the screen when it rotates, so it blinks on the way in and out.",
            ).apply {
                isChecked = plugin.rotateFullscreen
                setOnCheckedListener { plugin.settings.setBool(PlayEmbeds.KEY_ROTATE_FULLSCREEN, it) }
            },
        )

        section("YouTube")
        val qualityRow = action("Default quality") { row -> pickQuality(row) }
        updateQualitySubtitle(qualityRow)
    }

    private fun pickQuality(row: View) {
        val current = plugin.youtubeQuality
        SelectDialog().apply {
            title = "Default YouTube quality"
            items = QUALITIES.map { if (it == current) "✓ ${qualityName(it)}" else qualityName(it) }.toTypedArray()
            onResultListener = { which ->
                QUALITIES.getOrNull(which)?.let {
                    plugin.settings.setInt(PlayEmbeds.KEY_YOUTUBE_QUALITY, it)
                    updateQualitySubtitle(row)
                }
            }
        }.show(parentFragmentManager, "PlayEmbedsDefaultQuality")
    }

    private fun updateQualitySubtitle(row: View) {
        ((row as? LinearLayout)?.getChildAt(1) as? TextView)?.text = qualityName(plugin.youtubeQuality)
    }

    private fun qualityName(quality: Int): String =
        if (quality == YoutubeResolver.AUTO_QUALITY) "Auto (up to 360p)" else "${quality}p"

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    /** A tappable settings row with a title and a subtitle (its second child). */
    private fun action(title: String, onClick: (View) -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { onClick(it) }
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
                background = null
                setPaddingRelative(paddingStart, 0, paddingEnd, DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        add(row)
        return row
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }

    private companion object {
        val QUALITIES = listOf(YoutubeResolver.AUTO_QUALITY, 144, 240, 360, 480, 720, 1080)
    }
}
