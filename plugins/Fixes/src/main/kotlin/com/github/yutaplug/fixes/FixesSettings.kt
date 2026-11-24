package com.github.yutaplug.fixes

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting
import com.github.yutaplug.fixes.markdown.MarkdownAppearance
import com.github.yutaplug.fixes.markdown.MarkdownFix
import com.github.yutaplug.fixes.markdown.MarkdownFixSettings
import com.github.yutaplug.fixes.serversettings.ServerSettingsFix
import com.github.yutaplug.fixes.serversettings.ServerSettingsOptions
import com.lytefast.flexinput.R

class FixesSettings(private val plugin: Fixes) : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Fixes")
        setActionBarSubtitle("Plugin settings")
        val context = requireContext()
        linearLayout.setPadding(0, 0, 0, MarkdownAppearance.dp(context, 16))
        linearLayout.setBackgroundColor(MarkdownAppearance.themedColor(context, "colorBackgroundPrimary", Color.DKGRAY))

        header("Fixes")
        for (fix in plugin.fixes) {
            val subtext = buildString {
                append(fix.description)
                fix.author?.let { append("\nMade by ").append(it) }
                if (fix.requiresRestart) append("\nToggling requires a restart.")
            }
            addView(
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, fix.title, subtext).apply {
                    isChecked = plugin.isFixEnabled(fix)
                    setOnCheckedListener { plugin.setFixEnabled(fix, it) }
                },
            )
            if (fix is MarkdownFix) {
                row("Markdown appearance", "ic_theme_24dp") {
                    Utils.openPageWithProxy(requireContext(), MarkdownFixSettings(plugin.settings, plugin.markdown))
                }
            }
            if (fix is ServerSettingsFix) {
                row("Server settings options", "ic_settings_grey_a60_24dp") {
                    Utils.openPageWithProxy(requireContext(), ServerSettingsOptions(plugin, fix))
                }
            }
        }
    }

    private fun header(title: String) {
        addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                background = null
            },
        )
    }

    /** A native settings row, as in Discord's own settings: a `UiKit_Settings_Item_Icon` label. */
    private fun row(title: String, icon: String, action: () -> Unit) {
        val context = requireContext()
        linearLayout.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = title
                val attribute = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                    attribute.resourceId != 0
                ) {
                    background = context.getDrawable(attribute.resourceId)
                }
                val res = Utils.getResId(icon, "drawable")
                if (res != 0) {
                    val tint = MarkdownAppearance.themedColor(context, "colorInteractiveNormal", Color.LTGRAY)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        DrawableCompat.getDrawable(context, res, tint),
                        null,
                        null,
                        null,
                    )
                }
                isClickable = true
                setOnClickListener { action() }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
    }
}
