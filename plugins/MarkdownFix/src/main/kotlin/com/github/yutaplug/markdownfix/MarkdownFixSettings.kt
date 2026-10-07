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
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R
import java.util.Locale
import kotlin.math.roundToInt

class MarkdownFixSettings(private val settings: SettingsAPI, private val plugin: MarkdownFix) : SettingsPage() {
    private var activeDialog: DiscordDialog? = null
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
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = "Changes apply to visible messages immediately."
                background = null
                setPaddingRelative(paddingStart, dp(4), paddingEnd, dp(8))
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
            setPadding(dp(16), dp(4), dp(16), dp(12))
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

        activeDialog = DiscordDialog(context, size.title)
            .content(content)
            // Previews the default instead of saving it, so Cancel still keeps the current value.
            .neutral("Default") { select(size.default) }
            .negative("Cancel")
            .positive("Save") {
                settings.setString(size.key, selected.toString())
                changed()
                true
            }.show()
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
        activeDialog = null
        val confirm = ConfirmDialog()
        confirm
            .setTitle("Reset appearance?")
            .setDescription("All text sizes and the bullet color return to their defaults.")
            .setIsDangerous(true)
            .setOnOkListener {
                reset()
                confirm.dismiss()
            }.show(Utils.appActivity.supportFragmentManager, "MarkdownFixReset")
    }

    /**
     * A settings row built like Discord's own: a `UiKit_Settings_Item_Icon` label with an optional
     * icon, and a `UiKit_Settings_Item_Compound_Right` value, as in Account settings.
     */
    private fun row(title: String, icon: String? = null, action: () -> Unit): Pair<LinearLayout, TextView> {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            val attribute = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attribute, true) &&
                attribute.resourceId != 0
            ) {
                background = context.getDrawable(attribute.resourceId)
            }
            setOnClickListener { if (isEnabled) action() }
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = title
                background = null
                val res = icon?.let { Utils.getResId(it, "drawable") } ?: 0
                if (res != 0) {
                    val drawable = DrawableCompat.getDrawable(context, res, color("colorInteractiveNormal", Color.LTGRAY))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        // Wrap the value so the bullet swatch stays next to its text instead of at the start of the free space.
        val value = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right)
        row.addView(value, LinearLayout.LayoutParams(-2, -2))
        addView(row)
        return row to value
    }

    private fun header(title: String, first: Boolean = false) {
        if (!first) divider()
        addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun divider() {
        linearLayout.addView(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), LinearLayout.LayoutParams(-1, dp(1)))
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
