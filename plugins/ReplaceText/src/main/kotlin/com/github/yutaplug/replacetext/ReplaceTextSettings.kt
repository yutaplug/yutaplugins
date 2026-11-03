package com.github.yutaplug.replacetext

import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R

class ReplaceTextSettings(private val plugin: ReplaceText) : SettingsPage() {
    private var list: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("ReplaceText")
        setActionBarSubtitle("Text replacements")

        linearLayout.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply { text = "Replacements" },
            LinearLayout.LayoutParams(-1, -2),
        )
        list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        linearLayout.addView(list, LinearLayout.LayoutParams(-1, -2))
        linearLayout.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = "Add replacement"
                val icon = ContextCompat.getDrawable(context, Utils.getResId("ic_add_24dp", "drawable"))?.mutate()
                icon?.setTint(ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")))
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                setOnClickListener { Utils.openPageWithProxy(context, RuleEditorPage(plugin, -1)) }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val container = list ?: return
        container.removeAllViews()
        val rules = plugin.rules
        if (rules.isEmpty()) {
            container.addView(
                TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                    text = "No replacements yet. Add one to replace text in messages you send, messages in chat, or embeds."
                    background = null
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            return
        }
        rules.forEachIndexed { index, rule -> container.addView(row(index, rule), LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun row(index: Int, rule: Rule): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { Utils.openPageWithProxy(context, RuleEditorPage(plugin, index)) }
        }
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = "${display(rule.find)}  →  ${display(rule.replace)}"
                background = null
                maxLines = 2
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        row.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = summary(rule)
                background = null
                setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
                if (rule.pattern() == null) {
                    setTextColor(ColorCompat.getThemedColor(context, Utils.getResId("colorTextDanger", "attr")))
                }
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        return row
    }

    private fun display(text: String) = if (text.isEmpty()) "(nothing)" else "\"$text\""

    private fun summary(rule: Rule): String {
        if (rule.find.isEmpty()) return "Nothing to find"
        if (rule.pattern() == null) return "Invalid regex"
        val scopes = listOfNotNull(
            "unsent messages".takeIf { rule.unsent },
            "sent messages".takeIf { rule.sent },
            "embeds".takeIf { rule.embeds },
        )
        val flags = listOfNotNull("Regex".takeIf { rule.regex }, "Case sensitive".takeIf { rule.caseSensitive })
        val where = if (scopes.isEmpty()) "Off" else "In " + scopes.joinToString(", ")
        return (flags + where).joinToString(" · ")
    }

    override fun onDestroyView() {
        list = null
        super.onDestroyView()
    }
}
