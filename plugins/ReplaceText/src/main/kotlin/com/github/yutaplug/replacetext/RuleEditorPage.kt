package com.github.yutaplug.replacetext

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

/** Edits the rule at [index] in the plugin's list, or a new one when [index] is -1. Changes are saved on leaving. */
class RuleEditorPage(private val plugin: ReplaceText, private var index: Int) : SettingsPage() {
    private val rule = plugin.rules.getOrNull(index)?.copy() ?: Rule()
    private var deleted = false
    private var regexError: TextView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(if (index < 0) "Add replacement" else "Edit replacement")
        setActionBarSubtitle("ReplaceText")

        input("Find", rule.find) {
            rule.find = it
            updateRegexError()
        }
        regexError = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            background = null
            setTextColor(ColorCompat.getThemedColor(context, Utils.getResId("colorTextDanger", "attr")))
            setPaddingRelative(paddingStart, 0, paddingEnd, DimenUtils.dpToPx(8))
        }
        add(regexError!!)
        input("Replace with", rule.replace) { rule.replace = it }

        switch("Regex", "Treat Find as a regular expression. Use \$1, \$2 in Replace with for captured groups.", rule.regex) {
            rule.regex = it
            updateRegexError()
        }
        switch("Case sensitive", "Only match text with the same upper and lower case letters", rule.caseSensitive) {
            rule.caseSensitive = it
        }

        divider()
        section("Apply to")
        switch("Unsent messages", "Replace text in messages you send or edit, before they are sent", rule.unsent) {
            rule.unsent = it
        }
        switch("Sent messages", "Show the replacement in chat messages. Only changes what you see.", rule.sent) {
            rule.sent = it
        }
        switch("Embeds", "Show the replacement in embed titles, descriptions, fields and footers", rule.embeds) {
            rule.embeds = it
        }

        if (index >= 0) {
            divider()
            add(
                TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item).apply {
                    text = "Delete replacement"
                    setTextColor(ColorCompat.getThemedColor(context, Utils.getResId("colorTextDanger", "attr")))
                    setOnClickListener { confirmDelete() }
                },
            )
        }
        updateRegexError()
    }

    private fun updateRegexError() {
        val view = regexError ?: return
        val message = if (rule.regex && rule.find.isNotEmpty()) {
            try {
                rule.compile()
                null
            } catch (e: Throwable) {
                "Invalid regex: ${e.message?.substringBefore('\n') ?:"unknown error"}"
            }
        } else {
            null
        }
        view.text = message
        view.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun confirmDelete() {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Delete replacement?")
            .setDescription("This replacement will stop applying everywhere.")
            .setIsDangerous(true)
            .setOnOkListener {
                deleted = true
                plugin.saveRules(plugin.rules.filterIndexed { i, _ -> i != index })
                dialog.dismiss()
                activity?.onBackPressed()
            }.show(parentFragmentManager, "ReplaceTextDelete")
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        if (deleted) return
        val rules = plugin.rules.toMutableList()
        if (index >= 0 && index < rules.size) {
            rules[index] = rule
        } else {
            // A new rule is only kept once it has something to find.
            if (rule.find.isEmpty()) return
            rules.add(rule)
            index = rules.size - 1
        }
        plugin.saveRules(rules)
    }

    private fun input(hint: String, value: String, onChange: (String) -> Unit) {
        // Created empty: TextInput reuses Discord's nickname field, whose 128 character limit also cuts off
        // text set from code, so the saved text is only filled in once the limit is removed.
        val input = TextInput(requireContext(), hint)
        input.editText.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf()
            setSingleLine(false)
            maxLines = 8
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            setText(value)
            // The page itself scrolls, so keep it from taking the swipe while this box has more text to scroll.
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val scrollable = v.canScrollVertically(1) || v.canScrollVertically(-1)
                        v.parent?.requestDisallowInterceptTouchEvent(scrollable)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
                },
            )
        }
        linearLayout.addView(
            input,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
    }

    private fun switch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        add(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = checked
                setOnCheckedListener { onChange(it) }
            },
        )
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(1)),
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onDestroyView() {
        regexError = null
        super.onDestroyView()
    }
}
