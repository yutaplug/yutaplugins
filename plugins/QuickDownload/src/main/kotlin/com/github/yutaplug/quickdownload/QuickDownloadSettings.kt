package com.github.yutaplug.quickdownload

import android.view.View
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting

class QuickDownloadSettings(private val settings: SettingsAPI) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("QuickDownload")

        toggle(QuickDownload.KEY_MEDIA, "Media", "Show the download button on image and video attachments.")
        toggle(QuickDownload.KEY_GIFS, "GIFs", "Show the download button on GIFs, including Tenor and GIPHY links.")
    }

    private fun toggle(key: String, title: String, subtitle: String) {
        addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = settings.getBool(key, true)
                setOnCheckedListener { settings.setBool(key, it) }
            },
        )
    }
}
