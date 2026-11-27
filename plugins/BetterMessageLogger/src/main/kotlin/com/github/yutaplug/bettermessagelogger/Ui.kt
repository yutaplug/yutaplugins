package com.github.yutaplug.bettermessagelogger

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.discord.views.CheckedSetting
import com.discord.views.SearchInputView
import com.lytefast.flexinput.R

/**
 * Builds plugin screens out of Discord's own settings styles (the ones its Account and Voice settings use), so
 * text, spacing and touch feedback match the app.
 */
internal class Ui(val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int) = (value * density + 0.5f).toInt()

    fun attr(name: String, fallback: Int): Int {
        val id = Utils.getResId(name, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(context, id)
    }

    val headerColor get() = attr("colorHeaderPrimary", Color.WHITE)
    val textColor get() = attr("colorTextNormal", Color.WHITE)
    val mutedColor get() = attr("colorTextMuted", Color.GRAY)
    val iconColor get() = attr("colorInteractiveNormal", Color.GRAY)
    val brandColor get() = attr("colorBrand", Color.rgb(88, 101, 242))
    val dangerColor get() = attr("colorStatusDanger", Color.rgb(237, 66, 69))
    val pageColor get() = attr("colorBackgroundPrimary", Color.rgb(54, 57, 63))
    val cardColor get() = attr("colorBackgroundSecondary", Color.rgb(47, 49, 54))

    fun icon(name: String, tint: Int = iconColor): Drawable? =
        Utils.getResId(name, "drawable").takeIf { it != 0 }?.let { DrawableCompat.getDrawable(context, it, tint) }

    /** A vertical container, like Discord's settings sections. */
    fun column(parent: ViewGroup? = null): LinearLayout =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.also { parent?.addView(it, matchWidth()) }

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun header(parent: ViewGroup, title: String) {
        parent.addView(TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply { text = title }, matchWidth())
    }

    fun divider(parent: ViewGroup) {
        parent.addView(View(context, null, 0, R.i.UiKit_Settings_Divider), LinearLayout.LayoutParams(-1, dp(1)))
    }

    fun note(parent: ViewGroup, value: CharSequence): TextView =
        TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply { text = value }.also {
            parent.addView(it, matchWidth())
        }

    fun text(value: CharSequence, style: Int = R.i.UiKit_TextView, color: Int? = null, sizeSp: Float? = null) =
        TextView(context, null, 0, style).apply {
            text = value
            color?.let(::setTextColor)
            sizeSp?.let { setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
        }

    /** A label with an icon, like Discord's "Log Out" or "Voice & Video" settings rows. */
    fun action(parent: ViewGroup, title: String, iconName: String?, tint: Int? = null, click: () -> Unit): TextView {
        val view = TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = title
            tint?.let(::setTextColor)
            iconName?.let { icon(it, tint ?: iconColor) }?.let { setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null) }
            clickable(this, click)
        }
        parent.addView(view, matchWidth())
        return view
    }

    /**
     * Discord's two-part account row: a label on the left and a value with a chevron on the right, like
     * "Username" in My Account. Returns the value view so callers can update it.
     */
    fun value(parent: ViewGroup, title: CharSequence, value: CharSequence, click: () -> Unit): TextView {
        val row = LinearLayout(context, null, 0, R.i.UiKit_ViewGroup_LinearLayout_Horizontal)
        clickable(row, click)
        val label = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Left).apply { text = title }
        val trailing = TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right).apply {
            text = value
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron(), null)
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row, matchWidth())
        return trailing
    }

    /** A setting shown as its name with the current choice under it, which opens a pop-up when tapped. */
    fun option(parent: ViewGroup, title: String, summary: CharSequence, click: () -> Unit): TextView {
        val row = column()
        clickable(row, click)
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, dp(8), paddingEnd, 0)
            },
            matchWidth(),
        )
        val sub = TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = summary
            background = null
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        row.addView(sub, matchWidth())
        parent.addView(row, matchWidth())
        return sub
    }

    /** A row with a title, a smaller line under it and a value on the right. */
    fun entry(parent: ViewGroup, title: CharSequence, detail: CharSequence?, value: CharSequence?, click: () -> Unit): TextView {
        val row = LinearLayout(context, null, 0, R.i.UiKit_ViewGroup_LinearLayout_Horizontal)
        clickable(row, click)
        row.minimumHeight = dp(56)
        row.setPadding(dp(16), dp(8), dp(16), dp(8))
        val texts = column()
        texts.addView(text(title, R.i.UiKit_TextView_Medium_SingleLine, headerColor))
        if (detail != null && detail.isNotEmpty()) texts.addView(text(detail, R.i.UiKit_TextView_Subtext_SingleLine))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val trailing = text(value ?: "", R.i.UiKit_TextView_Subtext_SingleLine).apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron(), null)
            compoundDrawablePadding = dp(4)
        }
        row.addView(trailing)
        parent.addView(row, matchWidth())
        return trailing
    }

    fun switch(parent: ViewGroup, title: String, subtitle: String?, checked: Boolean, changed: (Boolean) -> Unit) =
        Utils.createCheckedSetting(context, CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
            isChecked = checked
            setOnCheckedListener { changed(it) }
        }.also { parent.addView(it, matchWidth()) }

    fun iconButton(iconName: String, description: String, click: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon(iconName))
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        background(this, android.R.attr.selectableItemBackgroundBorderless)
        setOnClickListener { click() }
    }

    /**
     * Discord's search bar (the one in "Add members" and channel pickers). [search] runs on the keyboard's search key
     * and when the field is cleared.
     */
    fun search(parent: ViewGroup, hint: String, initial: String?, search: (String?) -> Unit): EditText {
        val bar = SearchInputView(context, null)
        bar.setHint(hint)
        val input = bar.editText as EditText
        input.setText(initial.orEmpty())
        bar.b(initial.orEmpty())
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setSingleLine(true)
        // Whether results are currently narrowed by a search, so clearing the field shows everything again.
        var searching = initial != null && initial.isNotEmpty()
        input.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            val value = input.text.toString().trim().takeIf { it.isNotEmpty() }
            searching = value != null
            search(value)
            true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                bar.b(value)
                if (value.isEmpty() && searching) {
                    searching = false
                    search(null)
                }
            }
        })
        val params = matchWidth().apply { setMargins(dp(16), dp(12), dp(16), dp(4)) }
        parent.addView(bar, params)
        return input
    }

    /** A text field styled like Discord's dialog inputs. */
    fun field(parent: ViewGroup, hint: String, inputType: Int): com.google.android.material.textfield.TextInputLayout {
        val layout = LayoutInflater.from(context)
            .inflate(Utils.getResId("view_input_modal_text_no_suggestions", "layout"), parent, false)
            as com.google.android.material.textfield.TextInputLayout
        layout.hint = hint
        layout.editText?.inputType = inputType
        parent.addView(layout, matchWidth().apply { setMargins(dp(16), dp(12), dp(16), 0) })
        return layout
    }

    fun clickable(view: View, click: () -> Unit) {
        view.isClickable = true
        view.isFocusable = true
        background(view, android.R.attr.selectableItemBackground)
        view.setOnClickListener { click() }
    }

    private fun background(view: View, attribute: Int) {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            view.background = context.getDrawable(value.resourceId)
        }
    }

    private fun chevron(): Drawable? {
        val value = TypedValue()
        val attribute = Utils.getResId("ic_navigate_next", "attr")
        if (attribute == 0 || !context.theme.resolveAttribute(attribute, value, true) || value.resourceId == 0) return null
        return context.getDrawable(value.resourceId)
    }
}

/** Dates in the user's format: CustomTimestamps' when that plugin is on, otherwise Android's relative format. */
internal object Dates {
    private fun customTimestamps() = try {
        PluginManager.plugins.containsKey("CustomTimestamps") && PluginManager.isPluginEnabled("CustomTimestamps")
    } catch (_: Throwable) {
        false
    }

    fun format(context: Context, time: Long, resolution: Long = DateUtils.MINUTE_IN_MILLIS): CharSequence {
        if (customTimestamps()) {
            // CustomTimestamps hooks this method to apply its format.
            try {
                return TimeUtils.toReadableTimeString(context, time, ClockFactory.get())
            } catch (_: Throwable) {
            }
        }
        return DateUtils.getRelativeDateTimeString(
            context,
            time,
            resolution,
            DateUtils.WEEK_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_ALL,
        )
    }

    /** A full date for exports, where relative times make no sense. */
    fun absolute(time: Long): String {
        if (customTimestamps()) {
            try {
                return TimeUtils.toReadableTimeString(Utils.appContext, time, ClockFactory.get()).toString()
            } catch (_: Throwable) {
            }
        }
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
            .format(java.util.Date(time))
    }
}
