package com.github.yutaplug.fixes.markdown

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.drawable.DrawableCompat
import com.lytefast.flexinput.R
import java.util.Locale

internal class MarkdownFixSettings(private val settings: SettingsAPI, private val plugin: MarkdownFix) :
    SettingsPage() {
    private val updaters = ArrayList<() -> Unit>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Markdown appearance")
        setActionBarSubtitle("Fixes")
        updaters.clear()
        linearLayout.setPadding(0, 0, 0, dp(16))
        linearLayout.setBackgroundColor(color("colorBackgroundPrimary", Color.DKGRAY))

        header("Headings", first = true)
        for (index in 0 until 3) {
            sizeRow(MarkdownAppearance.sizes[index])
            colorRow(MarkdownAppearance.headingColors[index])
        }
        sizeRow(MarkdownAppearance.sizes[3])

        header("Lists")
        colorRow(MarkdownAppearance.bullet)

        divider()
        row("Reset appearance", "ic_refresh_white_a60_24dp", ::confirmReset)
        subtext("Sizes and colors apply to visible messages right away.")

        header("What this fixes")
        for ((title, description) in FEATURES) feature(title, description)
    }

    private fun changed() {
        updaters.forEach { it() }
        plugin.refreshAppearance()
    }

    private fun sizeRow(size: MarkdownAppearance.TextSize) {
        val value = row(if (size.key == MarkdownAppearance.SUBTEXT_SCALE) "Subtext size" else "${size.title} size") {
            showSizeDialog(size)
        }
        track { value.text = "${format(MarkdownAppearance.scale(settings, size))}×" }
    }

    /** Shows a row's current value now and again after every change. */
    private fun track(update: () -> Unit) {
        updaters.add(update)
        update()
    }

    private fun colorRow(setting: MarkdownAppearance.ColorSetting) {
        val value = row(setting.title) { showColorDialog(setting) }
        track {
            val custom = setting.color(settings)
            value.text = if (custom == null) "Theme" else setting.hex(settings)
            value.setCompoundDrawablesRelativeWithIntrinsicBounds(custom?.let(::swatch), null, null, null)
            value.compoundDrawablePadding = dp(8)
        }
    }

    private fun showSizeDialog(size: MarkdownAppearance.TextSize) {
        val current = MarkdownAppearance.scale(settings, size)
        val presets = (SIZE_PRESETS + size.default).distinct().sorted()
        val labels = presets.map { scale ->
            (if (scale == current) "✓ " else "") +
                "${format(scale)}×" +
                (if (scale == size.default) " (default)" else "")
        } +
            "Custom…"
        SelectDialog()
            .apply {
                title = "${size.title} size"
                items = labels.toTypedArray()
                onResultListener = { which ->
                    if (which < presets.size) {
                        saveSize(size, presets[which])
                    } else {
                        showCustomSizeDialog(size)
                    }
                }
            }.show(parentFragmentManager, "MarkdownFixSize")
    }

    private fun showCustomSizeDialog(size: MarkdownAppearance.TextSize) {
        val dialog = InputDialog()
        dialog
            .setTitle("${size.title} size")
            .setDescription("A size from 0.10 to 3.00, where 1.00 is normal message text. Example: ${size.example}")
            .setPlaceholderText("Size")
            .setInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            .setOnOkListener {
                val scale = dialog.input.trim().replace(',', '.').toFloatOrNull()
                if (scale == null || !scale.isFinite() || scale < 0.1f || scale > 3f) {
                    dialog.inputLayout.error = "Enter a number from 0.10 to 3.00"
                } else {
                    saveSize(size, scale)
                    dialog.dismiss()
                }
            }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                setText(format(MarkdownAppearance.scale(settings, size)))
                setSelection(text.length)
            }
            dialog.okButton.text = "Save"
        }
        dialog.show(parentFragmentManager, "MarkdownFixCustomSize")
    }

    private fun saveSize(size: MarkdownAppearance.TextSize, scale: Float) {
        settings.setString(size.key, scale.toString())
        changed()
    }

    private fun showColorDialog(setting: MarkdownAppearance.ColorSetting) {
        val custom = setting.isCustom(settings)
        val currentHex = setting.hex(settings)
        val labels = listOf((if (custom) "" else "✓ ") + "Theme color") +
            MarkdownAppearance.presets.map { (name, hex) ->
                (if (custom && hex == currentHex) "✓ " else "") + "$name  $hex"
            } +
            "Custom hex…"
        SelectDialog()
            .apply {
                title = setting.title
                items = labels.toTypedArray()
                onResultListener = { which ->
                    when {
                        which == 0 -> saveColor(setting, null)

                        which <= MarkdownAppearance.presets.size -> saveColor(
                            setting,
                            MarkdownAppearance.presets[which - 1].second,
                        )

                        else -> showCustomColorDialog(setting)
                    }
                }
            }.show(parentFragmentManager, "MarkdownFixColor")
    }

    private fun showCustomColorDialog(setting: MarkdownAppearance.ColorSetting) {
        val dialog = InputDialog()
        dialog
            .setTitle(setting.title)
            .setDescription("A hex color such as #5865F2, or #AARRGGBB with transparency.")
            .setPlaceholderText("Hex color")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
            .setOnOkListener {
                val hex = MarkdownAppearance.normalizeColor(dialog.input)
                if (hex == null) {
                    dialog.inputLayout.error = "Enter #RRGGBB or #AARRGGBB"
                } else {
                    saveColor(setting, hex)
                    dialog.dismiss()
                }
            }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                setText(setting.hex(settings))
                setSelection(text.length)
            }
            dialog.okButton.text = "Save"
        }
        dialog.show(parentFragmentManager, "MarkdownFixCustomColor")
    }

    /** A null [hex] goes back to the theme color but remembers the last custom one. */
    private fun saveColor(setting: MarkdownAppearance.ColorSetting, hex: String?) {
        if (hex != null) settings.setString(setting.key, hex)
        settings.setBool(setting.customKey, hex != null)
        changed()
    }

    private fun confirmReset() {
        val confirm = ConfirmDialog()
        confirm
            .setTitle("Reset appearance?")
            .setDescription("All text sizes return to their defaults, and headings and bullets use the theme colors.")
            .setIsDangerous(true)
            .setOnOkListener {
                MarkdownAppearance.sizes.forEach { settings.setString(it.key, it.default.toString()) }
                for (setting in MarkdownAppearance.headingColors + MarkdownAppearance.bullet) {
                    settings.setString(setting.key, setting.default)
                    settings.setBool(setting.customKey, false)
                }
                changed()
                confirm.dismiss()
            }.show(parentFragmentManager, "MarkdownFixReset")
    }

    /**
     * A settings row built like Discord's own: a `UiKit_Settings_Item_Icon` label with an optional
     * icon, and a `UiKit_Settings_Item_Compound_Right` value, as in Account settings.
     */
    private fun row(title: String, icon: String? = null, action: () -> Unit): TextView {
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
            setOnClickListener { action() }
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = title
                background = null
                val res = icon?.let { Utils.getResId(it, "drawable") } ?: 0
                if (res != 0) {
                    val drawable = DrawableCompat.getDrawable(
                        context,
                        res,
                        color("colorInteractiveNormal", Color.LTGRAY),
                    )
                    setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
                }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        // Wrapped, so a color swatch stays next to its text instead of at the start of the free space.
        val value = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right)
        row.addView(value, LinearLayout.LayoutParams(-2, -2))
        addView(row)
        return value
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

    private fun subtext(text: CharSequence): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText)
            .apply {
                this.text = text
                background = null
                setPaddingRelative(paddingStart, dp(4), paddingEnd, dp(8))
            }.also(::addView)

    /** A feature's name in the header color, then what it does in the muted subtext color. */
    private fun feature(title: String, description: String) {
        val text = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(color("colorHeaderPrimary", Color.WHITE)),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            append('\n').append(description)
        }
        subtext(text)
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, dp(1)),
        )
    }

    private fun swatch(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(1), color("colorTextMuted", Color.LTGRAY))
        setSize(dp(14), dp(14))
    }

    override fun onDestroyView() {
        updaters.clear()
        super.onDestroyView()
    }

    private fun color(attribute: String, fallback: Int): Int =
        MarkdownAppearance.themedColor(requireContext(), attribute, fallback)

    private fun dp(value: Int): Int = MarkdownAppearance.dp(requireContext(), value)

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)

    private companion object {
        val SIZE_PRESETS = listOf(0.75f, 1.0f, 1.2f, 1.35f, 1.5f, 1.75f, 2.0f)

        val FEATURES = listOf(
            "Headings   #   ##   ###" to
                "Start a line with #, ## or ### and a space for a big, bold title. Sizes and colors can be changed above.",
            "Subtext   -#" to "Start a line with -# and a space for small grey text.",
            "Lists   -   *   1." to
                "Start lines with - or * for bullets, or 1. for numbers. Indent with two spaces to nest a list. " +
                "A - right after a numbered item keeps counting, like on current Discord.",
            "Block quotes   >   >>>" to
                "They look like Discord's own. The fix draws them itself only so bullets, numbers, headings and " +
                "code blocks inside a quote line up to the right of its stripe.",
            "Code blocks   ```" to
                "The box no longer disappears after an edit or at the end of a message, also shows for one-line " +
                "```code```, and fits inside quotes and lists. ```ansi blocks show their colors.",
            "Masked links   [text](https://…)" to "Show the text as a link in every message.",
            "Game mentions   <@\$id>" to "Show the game's name and icon. Tap one to see the game's details.",
            "Command mentions   </name:id>" to "Show the command. Tap one to put it in the message box.",
            "Hex colors   #5865F2" to "Shown in their own color.",
            "Timestamps   <t:…:s>   <t:…:S>" to "Show a short date with the time, which Discord 126.21 didn't know.",
            "Replies" to "Reply previews show a heading's text without its # instead of a big heading.",
            "Forum posts" to
                "**bold** at the start of a line no longer turns into a bullet, and posts get quotes, masked " +
                "links and the rest of the Markdown messages have.",
        )
    }
}
