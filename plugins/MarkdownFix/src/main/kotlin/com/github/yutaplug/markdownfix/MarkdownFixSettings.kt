package com.github.yutaplug.markdownfix

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting
import java.util.Locale
import kotlin.math.roundToInt

class MarkdownFixSettings(private val settings: SettingsAPI, private val plugin: MarkdownFix) : SettingsPage() {
    private var activeDialog: AlertDialog? = null
    private lateinit var customColor: CheckedSetting
    private lateinit var colorRow: LinearLayout
    private lateinit var colorValue: TextView
    private val sizeValues = mutableMapOf<MarkdownAppearance.TextSize, TextView>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("MarkdownFix")
        setActionBarSubtitle("Plugin settings")
        sizeValues.clear()
        linearLayout.setPadding(0, 0, 0, dp(16))
        linearLayout.setBackgroundColor(color("colorBackgroundPrimary", Color.DKGRAY))

        header("Text sizes", first = true)
        for (size in MarkdownAppearance.sizes) {
            val (_, value) = row(size.title) { showScaleDialog(size) }
            sizeValues[size] = value
        }

        header("Bullet lists")
        customColor = Utils
            .createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Custom bullet color",
                "Replace the theme color for list bullets.",
            ).apply {
                isChecked = settings.getBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, false)
                setOnCheckedListener {
                    settings.setBool(MarkdownAppearance.CUSTOM_BULLET_COLOR, it)
                    changed()
                }
            }
        addView(customColor)
        val (row, value) = row("Bullet color", action = ::showColorDialog)
        colorRow = row
        colorValue = value

        divider()
        row("Reset appearance", "ic_refresh_white_a60_24dp", ::confirmReset)
        addView(
            text("Changes apply to visible messages immediately.", 12f, "colorTextMuted").apply {
                setPadding(dp(16), dp(4), dp(16), 0)
            },
        )
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
        colorValue.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(Color.parseColor(currentColor())), null, null, null)
        colorValue.compoundDrawablePadding = dp(8)
    }

    private fun showScaleDialog(size: MarkdownAppearance.TextSize) {
        activeDialog?.dismiss()
        val context = requireContext()
        var selected = MarkdownAppearance.scale(settings, size)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        // Preview at the chosen scale, relative to Discord's normal message text size.
        val preview = text(size.example.substringAfter(' '), BASE_TEXT_SIZE, "colorHeaderPrimary").apply {
            if (size.key != MarkdownAppearance.SUBTEXT_SCALE) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(64)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = text("", 14f, "colorTextMuted").apply {
            gravity = Gravity.END
            minWidth = dp(52)
        }

        fun update() {
            value.text = "${format(selected)}×"
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SIZE * selected)
        }
        val bar = SeekBar(context, null, 0, com.lytefast.flexinput.R.i.UiKit_SeekBar).apply {
            max = 290
            contentDescription = "${size.title} scale"
            // The UiKit style has no horizontal padding, which clips half the thumb at either end.
            val inset = maxOf(thumb?.intrinsicWidth ?: 0, dp(20)) / 2
            setPadding(inset, paddingTop, inset, paddingBottom)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    selected = (progress + 10) / 100f
                    update()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}

                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }

        fun select(scale: Float) {
            selected = scale
            bar.progress = ((scale - 0.1f) * 100).roundToInt().coerceIn(0, bar.max)
            update()
        }
        controls.addView(bar, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(value)
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
        content.addView(text("${size.example} · normal is 1.00×", 12f, "colorTextMuted"))
        select(selected)

        val dialog = AlertDialog
            .Builder(context)
            .setCustomTitle(DiscordSettingsUi.title(context, size.title))
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Default", null)
            .setPositiveButton("Save") { _, _ ->
                settings.setString(size.key, selected.toString())
                changed()
            }.create()
        DiscordSettingsUi.styleDialog(dialog, context)
        // Previews the default instead of saving it, so Cancel still keeps the current value.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { select(size.default) }
        activeDialog = dialog
        dialog.show()
    }

    private fun showColorDialog() {
        activeDialog?.dismiss()
        activeDialog = MarkdownEditDialog.show(
            context = requireContext(),
            title = "Bullet color",
            label = "Hex color",
            value = currentColor(),
            hint = "#RRGGBB or #AARRGGBB",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            colorPicker = true,
            validate = { raw ->
                if (MarkdownAppearance.normalizeColor(raw) == null) "Enter #RRGGBB or #AARRGGBB" else null
            },
            save = { raw ->
                settings.setString(MarkdownAppearance.BULLET_COLOR, MarkdownAppearance.normalizeColor(raw)!!)
                changed()
            },
            defaultValue = MarkdownAppearance.DEFAULT_BULLET_COLOR,
        )
    }

    private fun confirmReset() {
        activeDialog?.dismiss()
        val context = requireContext()
        val dialog = AlertDialog
            .Builder(context)
            .setCustomTitle(DiscordSettingsUi.title(context, "Reset appearance?"))
            .setView(
                text("All text sizes and the bullet color return to their defaults.", 15f, "colorTextNormal").apply {
                    setPadding(dp(16), 0, dp(16), dp(12))
                },
            ).setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ -> reset() }
            .create()
        DiscordSettingsUi.styleDialog(dialog, context)
        activeDialog = dialog
        dialog.show()
    }

    /** A single-line row sized like Discord's settings rows, with a trailing value. */
    private fun row(title: String, icon: String? = null, action: () -> Unit): Pair<LinearLayout, TextView> {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            isFocusable = true
            val attribute = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                attribute.resourceId != 0
            ) {
                background = context.getDrawable(attribute.resourceId)
            }
            setOnClickListener { if (isEnabled) action() }
        }
        icon?.let { Utils.getResId(it, "drawable") }?.takeIf { it != 0 }?.let { res ->
            row.addView(
                ImageView(context).apply {
                    setImageDrawable(
                        DrawableCompat.getDrawable(context, res, color("colorInteractiveNormal", Color.LTGRAY)),
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(24) },
            )
        }
        row.addView(
            text(title, 16f, "colorTextNormal").apply { setSingleLine(true) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val value = text("", 14f, "colorTextMuted").apply {
            setSingleLine(true)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(value)
        addView(row)
        return row to value
    }

    private fun header(title: String, first: Boolean = false) {
        if (!first) divider()
        addView(DiscordSettingsUi.header(requireContext(), title))
    }

    private fun divider() {
        linearLayout.addView(DiscordSettingsUi.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun swatch(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(1), color("colorTextMuted", Color.LTGRAY))
        setSize(dp(14), dp(14))
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

    private fun text(value: String, size: Float, attribute: String): TextView =
        DiscordSettingsUi.text(requireContext()).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color(attribute, Color.LTGRAY))
        }

    private fun color(attribute: String, fallback: Int): Int =
        MarkdownAppearance.themedColor(requireContext(), attribute, fallback)

    private fun dp(value: Int): Int = MarkdownAppearance.dp(requireContext(), value)

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    companion object {
        private const val BASE_TEXT_SIZE = 16f
    }
}
