package com.github.yutaplug.fixes

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting
import com.github.yutaplug.fixes.markdown.MarkdownAppearance
import com.github.yutaplug.fixes.markdown.MarkdownFix
import com.github.yutaplug.fixes.markdown.MarkdownFixSettings
import com.github.yutaplug.fixes.screenrefresh.RefreshLog
import com.github.yutaplug.fixes.screenrefresh.ScreenRefreshFix
import com.github.yutaplug.fixes.serversettings.ServerSettingsFix
import com.github.yutaplug.fixes.serversettings.ServerSettingsOptions
import com.github.yutaplug.fixes.updater.UpdaterFix
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
                fix.warning?.let { append("⚠ ").append(it).append("\n\n") }
                append(fix.description)
                fix.author?.let { append("\nMade by ").append(it) }
                if (fix.requiresRestart) append("\nToggling requires a restart.")
            }
            addView(
                Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, fix.title, subtext).apply {
                    isChecked = plugin.isFixEnabled(fix)
                    var reverting = false
                    setOnCheckedListener { checked ->
                        if (reverting) return@setOnCheckedListener
                        val warning = fix.warning
                        if (!checked || warning == null) {
                            plugin.setFixEnabled(fix, checked)
                            return@setOnCheckedListener
                        }
                        // Stays off until the warning is confirmed.
                        reverting = true
                        isChecked = false
                        reverting = false
                        val dialog = ConfirmDialog()
                        dialog
                            .setTitle("Turn on ${fix.title}?")
                            .setDescription(warning)
                            .setIsDangerous(true)
                            .setOnOkListener {
                                reverting = true
                                isChecked = true
                                reverting = false
                                plugin.setFixEnabled(fix, true)
                                dialog.dismiss()
                            }.show(parentFragmentManager, "FixWarning")
                    }
                },
            )
            if (fix is MarkdownFix) {
                row("Markdown appearance", "ic_theme_24dp") {
                    Utils.openPageWithProxy(requireContext(), MarkdownFixSettings(plugin.settings, plugin.markdown))
                }
            }
            if (fix is ScreenRefreshFix) {
                row("Save screen refresh log", "ic_file_download_white_24dp") { saveRefreshLog(fix) }
            }
            if (fix is UpdaterFix) {
                row("Open updater", "ic_file_download_white_24dp") { UpdaterFix.openUpdater(requireContext()) }
            }
            if (fix is ServerSettingsFix) {
                row("Server settings options", "ic_settings_grey_a60_24dp") {
                    Utils.openPageWithProxy(requireContext(), ServerSettingsOptions(plugin, fix))
                }
            }
        }
    }

    /** Temporary: saves [ScreenRefreshFix]'s diagnostics log to Downloads as a .txt file. */
    private fun saveRefreshLog(fix: ScreenRefreshFix) {
        val context = requireContext().applicationContext
        Utils.threadPool.execute {
            val result = try {
                "Saved to Downloads/" + RefreshLog.saveToDownloads(context, fix.exportText(context))
            } catch (e: Throwable) {
                "Could not save the log: ${e.message ?: e.javaClass.simpleName}"
            }
            Utils.mainThread.post { Utils.showToast(result, true) }
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
