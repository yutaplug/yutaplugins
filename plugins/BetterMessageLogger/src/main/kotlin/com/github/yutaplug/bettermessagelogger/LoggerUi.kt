package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting

/** Shared, theme-aware spacing and surfaces for settings, filters and history. */
internal class LoggerUi(val context: Context) {
    val primary get() = theme("colorHeaderPrimary", Color.WHITE)
    val normal get() = theme("colorTextNormal", Color.WHITE)
    val muted get() = theme("colorTextMuted", Color.LTGRAY)
    val icon get() = theme("colorInteractiveNormal", Color.LTGRAY)
    val brand get() = theme("colorBrand", Color.rgb(88, 101, 242))
    val surface get() = theme("colorBackgroundSecondary", Color.rgb(47, 49, 54))
    val background get() = theme("colorBackgroundPrimary", Color.rgb(54, 57, 63))
    val danger get() = theme("colorStatusDanger", Color.rgb(237, 66, 69))

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun theme(attribute: String, fallback: Int): Int = Utils.getResId(attribute, "attr").let {
        if (it == 0) fallback else ColorCompat.getThemedColor(context, it)
    }

    fun text(value: CharSequence, size: Float = 14f, color: Int = primary) = DiscordSettingsUi.text(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Body content for [DiscordDialog], padded like the native notice body. */
    fun dialogContent(horizontal: Int = 16) = column().apply {
        setPadding(dp(horizontal), dp(12), dp(horizontal), dp(12))
        isFocusableInTouchMode = true
    }

    fun input(placeholder: String) = DiscordSettingsUi.input(context).apply {
        hint = placeholder
        setTextColor(primary)
        setHintTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setSingleLine(true)
        setPadding(0, dp(10), 0, dp(10))
        minimumHeight = dp(48)
        background = null
    }

    fun scroll(content: View, fraction: Float = 0.35f): ScrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = (resources.displayMetrics.heightPixels * fraction).toInt()
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maximum
            } else {
                minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        }
    }.apply {
        addView(content)
        isFillViewport = false
    }

    fun card() = column().apply {
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(4).toFloat()
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** Discord's full-width settings divider. */
    fun divider(parent: LinearLayout) {
        parent.addView(DiscordSettingsUi.divider(context), LinearLayout.LayoutParams(-1, dp(1)))
    }

    fun header(parent: LinearLayout, title: String) {
        parent.addView(DiscordSettingsUi.header(context, title))
    }

    fun caption(value: CharSequence) = text(value, 12f, muted).apply {
        setPadding(dp(16), dp(2), dp(16), dp(8))
    }

    fun switch(title: String, subtitle: String?, checked: Boolean, changed: (Boolean) -> Unit): CheckedSetting =
        Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = checked
            setOnCheckedListener { changed(it) }
        }

    /**
     * A single-line settings row: icon, title and an optional trailing value, sized like Discord's
     * icon rows. Returns the value view so callers can update it.
     */
    fun row(
        parent: LinearLayout,
        title: String,
        value: CharSequence? = null,
        iconName: String? = null,
        color: Int = normal,
        click: () -> Unit,
    ): TextView {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            isFocusable = true
            selectable(this)
            setOnClickListener { click() }
        }
        iconName?.let { name ->
            val res = Utils.getResId(name, "drawable")
            if (res != 0) {
                row.addView(
                    ImageView(context).apply {
                        setImageDrawable(DrawableCompat.getDrawable(context, res, if (color == normal) icon else color))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(24) },
                )
            }
        }
        row.addView(
            text(title, 16f, color).apply {
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        val trailing = text(value ?: "", 14f, muted).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = dp(160)
            setPadding(dp(12), 0, 0, 0)
            visibility = if (value == null) View.GONE else View.VISIBLE
        }
        row.addView(trailing, LinearLayout.LayoutParams(-2, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return trailing
    }

    fun iconButton(iconName: String, description: String, color: Int = icon, click: () -> Unit) =
        ImageView(context).apply {
            val res = Utils.getResId(iconName, "drawable")
            if (res != 0) setImageDrawable(DrawableCompat.getDrawable(context, res, color))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            isFocusable = true
            minimumWidth = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            selectable(this, borderless = true)
            setOnClickListener { click() }
        }

    private fun selectable(view: View, borderless: Boolean = false) {
        // A selectable background works on Android 5, unlike View.foreground.
        val value = TypedValue()
        val attribute =
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
        if (context.theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            view.background = context.getDrawable(value.resourceId)
        }
    }
}
