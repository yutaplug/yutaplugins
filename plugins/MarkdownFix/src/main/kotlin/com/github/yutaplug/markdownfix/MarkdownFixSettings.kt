package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.views.CheckedSetting
import java.util.Locale

class MarkdownFixSettings(private val settings: SettingsAPI, private val plugin: MarkdownFix) : SettingsPage() {
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var customColor: CheckedSetting
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private val sizeValues = mutableMapOf<MarkdownAppearance.TextSize, TextView>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("MarkdownFix")
        setActionBarSubtitle("Plugin settings")
        val context = requireContext()
        sizeValues.clear()
        linearLayout.setPadding(0, dp(16), 0, dp(24))
        linearLayout.setBackgroundColor(color("colorBackgroundPrimary", Color.DKGRAY))
        addView(
            text("Changes apply to visible messages immediately.", 14, "colorTextMuted").apply {
                setPadding(dp(16), 0, dp(16), dp(16))
            },
        )
        section("Text sizes")
        for (size in MarkdownAppearance.sizes) {
            val (row, value) = actionRow(size.title, size.example, { showScaleDialog(size) })
            sizeValues[size] = value
            addView(row)
        }

        section("Bullet lists")
        customColor = Utils
            .createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Custom bullet color",
                "Use your own color instead of the theme color.",
            ).apply {
                isChecked = settings.getBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
                setOnCheckedListener {
                    settings.setBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, it)
                    changed()
                }
            }
        addView(customColor)
        val (row, value) = actionRow("Bullet color", "#RRGGBB or #AARRGGBB", ::showColorDialog)
        colorRow = row
        colorValue = value
        addView(colorRow)
        section("Defaults")
        addView(actionRow("Reset appearance", "Restore all text sizes and the theme bullet color.", ::reset).first)
        updateUi()
    }

    private fun changed() {
        updateUi()
        plugin.refreshAppearance()
    }

    private fun updateUi() {
        for ((size, value) in sizeValues) value.text = "${format(MarkdownAppearance.scale(settings, size))}×"
        val custom = settings.getBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
        colorRow.isEnabled = custom
        colorRow.alpha = if (custom) 1f else 0.45f
        colorValue.text = currentColor()
    }

    private fun showScaleDialog(size: MarkdownAppearance.TextSize) {
        activeDialog?.dismiss()
        var selected = MarkdownAppearance.scale(settings, size)
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        content.addView(text("Choose a scale from 0.1 to 3.0. Use 1.0 for normal text size.", 14, "colorTextMuted"))
        val value = text("${format(selected)}×", 16, "colorHeaderPrimary").apply {
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(value)
        content.addView(
            android.widget.SeekBar(requireContext(), null, 0, com.lytefast.flexinput.R.i.UiKit_SeekBar).apply {
                max = 290
                progress = kotlin.math.round((selected - 0.1f) * 100).toInt().coerceIn(0, max)
                contentDescription = "${size.title} scale"
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        selected = (progress + 10) / 100f
                        value.text = "${format(selected)}×"
                    }

                    override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}

                    override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
                })
            },
            LinearLayout.LayoutParams(-1, dp(48)),
        )
        activeDialog = androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setCustomTitle(DiscordSettingsUi.title(requireContext(), size.title))
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ ->
                settings.setString(size.key, size.default.toString())
                changed()
            }.setPositiveButton("Save") { _, _ ->
                settings.setString(size.key, selected.toString())
                changed()
            }.create()
            .also {
                DiscordSettingsUi.styleDialog(it, requireContext())
                it.show()
            }
    }

    private fun showColorDialog() {
        activeDialog?.dismiss()
        activeDialog = MarkdownEditDialog.show(
            context = requireContext(),
            title = "Bullet color",
            description = "Pick a color with the sliders, or enter #RRGGBB or #AARRGGBB. Changes apply when you save.",
            label = "Hex color",
            value = currentColor(),
            hint = "#5865F2",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            colorPicker = true,
            validate = { raw ->
                if (MarkdownAppearance.normalizeColor(raw) == null) "Enter #RRGGBB or #AARRGGBB" else null
            },
            save = { raw ->
                settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.normalizeColor(raw)!!)
                changed()
            },
            reset = {
                settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR)
                changed()
            },
        )
    }

    private fun actionRow(title: String, subtitle: String, action: () -> Unit): Pair<LinearLayout, TextView> {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusable = true
            val attribute = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                attribute.resourceId != 0
            ) {
                background = context.getDrawable(attribute.resourceId)
            }
            setOnClickListener { action() }
        }
        val labels = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16, "colorHeaderPrimary"))
            addView(text(subtitle, 13, "colorTextMuted").apply { setPadding(0, dp(3), 0, 0) })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val value = text("", 14, "colorTextMuted").apply {
            setPadding(dp(12), 0, 0, 0)
            gravity = Gravity.END
        }
        row.addView(value)
        return row to value
    }

    private fun section(title: String) {
        linearLayout.addView(DiscordSettingsUi.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
        addView(DiscordSettingsUi.header(requireContext(), title))
    }

    private fun reset() {
        MarkdownAppearance.sizes.forEach { settings.setString(it.key, it.default.toString()) }
        settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR)
        settings.setBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
        customColor.isChecked = false
        changed()
    }

    override fun onDestroyView() {
        activeDialog?.dismiss()
        activeDialog = null
        sizeValues.clear()
        super.onDestroyView()
    }

    private fun currentColor(): String = MarkdownAppearance.normalizeColor(
        settings.getString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.DEFAULT_BULLET_COLOR),
    )
        ?: MarkdownAppearance.DEFAULT_BULLET_COLOR

    private fun text(value: String, size: Int, attribute: String): TextView =
        DiscordSettingsUi.text(requireContext()).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            setTextColor(color(attribute, Color.LTGRAY))
        }

    private fun color(attribute: String, fallback: Int): Int =
        MarkdownAppearance.themedColor(requireContext(), attribute, fallback)

    private fun dp(value: Int): Int = MarkdownAppearance.dp(requireContext(), value)

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
}
