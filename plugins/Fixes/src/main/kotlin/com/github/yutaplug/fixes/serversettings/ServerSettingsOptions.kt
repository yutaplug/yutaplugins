package com.github.yutaplug.fixes.serversettings

import android.graphics.Color
import android.view.View
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting
import com.github.yutaplug.fixes.Fixes
import com.github.yutaplug.fixes.markdown.MarkdownAppearance

internal class ServerSettingsOptions(private val plugin: Fixes, private val fix: ServerSettingsFix) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Server settings")
        setActionBarSubtitle("Fixes")
        val context = requireContext()
        linearLayout.setBackgroundColor(MarkdownAppearance.themedColor(context, "colorBackgroundPrimary", Color.DKGRAY))

        for (option in ServerSettingsFix.options) {
            addView(
                Utils
                    .createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, option.title, option.description)
                    .apply {
                        isChecked = fix.option(option)
                        setOnCheckedListener {
                            plugin.settings.setBool(option.key, it)
                            plugin.restartFix(fix)
                        }
                    },
            )
        }
    }
}
