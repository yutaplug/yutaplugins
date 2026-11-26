package com.github.yutaplug.fixes.updater

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.lytefast.flexinput.R
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Small builders for the updater screen, all on Discord 126.21's own styles and theme colors. */
internal object UpdaterUi {
    const val BRAND = 0xFF5865F2.toInt()
    const val GREEN = 0xFF23A55A.toInt()
    const val RED = 0xFFF23F43.toInt()
    const val YELLOW = 0xFFF0B232.toInt()

    private val LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)")
    private val FORMATTING = Pattern.compile("\\*\\*|__|`|~~")

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun color(context: Context, attr: String, fallback: Int): Int {
        val id = Utils.getResId(attr, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(context, id)
    }

    fun drawable(context: Context, name: String, tint: Int?): Drawable? {
        val id = Utils.getResId(name, "drawable")
        if (id == 0) return null
        return ContextCompat.getDrawable(context, id)?.mutate()?.also { if (tint != null) it.setTint(tint) }
    }

    fun card(context: Context): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(color(context, "colorBackgroundSecondary", Color.DKGRAY))
            radius = dp(context, 10).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(context, 16)
            setPadding(pad, pad, pad, pad)
        }
        card.addView(content, -1, -2)
        return card to content
    }

    fun cardParams(context: Context) = LinearLayout.LayoutParams(-1, -2).apply {
        val side = dp(context, 16)
        setMargins(side, dp(context, 4), side, dp(context, 8))
    }

    fun header(context: Context, text: CharSequence) = TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
        this.text = text
        background = null
    }

    fun title(context: Context, text: CharSequence) = TextView(context, null, 0, R.i.UiKit_TextView_H2_Bold).apply {
        this.text = text
        setTextColor(color(context, "colorHeaderPrimary", Color.WHITE))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    fun body(context: Context, text: CharSequence) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        this.text = text
        setTextColor(color(context, "colorTextNormal", Color.LTGRAY))
    }

    fun subtext(context: Context, text: CharSequence) = TextView(context, null, 0, R.i.UiKit_TextView_Subtext).apply {
        this.text = text
    }

    /** A small rounded label, e.g. the version change on an update. */
    fun pill(context: Context, text: CharSequence, tint: Int) = TextView(context, null, 0, R.i.UiKit_TextView_Semibold).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(tint)
        isSingleLine = true
        val h = dp(context, 8)
        val v = dp(context, 3)
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 12).toFloat()
            setColor((tint and 0x00FFFFFF) or 0x33000000)
        }
    }

    enum class Kind { PRIMARY, SECONDARY, GREEN, RED }

    fun button(context: Context, text: CharSequence, kind: Kind, onClick: (View) -> Unit): MaterialButton {
        val style = when (kind) {
            Kind.PRIMARY -> R.i.UiKit_Material_Button
            Kind.SECONDARY -> R.i.UiKit_Material_Button_Secondary
            Kind.GREEN -> R.i.UiKit_Material_Button_Green
            Kind.RED -> R.i.UiKit_Material_Button_Red
        }
        return MaterialButton(ContextThemeWrapper(context, style), null, 0).apply {
            this.text = text
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { onClick(it) }
        }
    }

    /** A row of buttons sharing the width equally. */
    fun buttonRow(context: Context, buttons: List<View>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((i, button) in buttons.withIndex()) {
            addView(
                button,
                LinearLayout.LayoutParams(0, dp(context, 40), 1f).apply {
                    if (i > 0) marginStart = dp(context, 8)
                },
            )
        }
    }

    fun icon(context: Context, drawable: Drawable?, size: Int) = ImageView(context).apply {
        setImageDrawable(drawable)
        layoutParams = LinearLayout.LayoutParams(dp(context, size), dp(context, size)).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    fun space(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, height))
    }

    /** Changelog markdown as plain text, for previews. */
    fun plain(markdown: String): String {
        var text = LINK.matcher(markdown).replaceAll("$1")
        text = FORMATTING.matcher(text).replaceAll("")
        return text
    }

    /** [String.replace] without Kotlin's stdlib version, which breaks on Discord's obfuscated stdlib. */
    fun replaceLiteral(text: String, old: String, new: String): String =
        Pattern.compile(Pattern.quote(old)).matcher(text).replaceAll(Matcher.quoteReplacement(new))
}
