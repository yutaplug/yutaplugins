package com.github.yutaplug.translatemessages

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class TranslateMessagesSettings(private val plugin: TranslateMessages) : SettingsPage() {
    private var languageSubtitle: TextView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("TranslateMessages")
        setActionBarSubtitle("Message translation")

        section("Translation")
        languageSubtitle = action("Translate to", Languages.name(plugin.targetLanguage)) { pickLanguage() }
        add(
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Auto-translate messages",
                    "Translate messages from others that are in another language",
                ).apply {
                    isChecked = plugin.autoTranslate
                    setOnCheckedListener { plugin.autoTranslate = it }
                },
        )
        add(
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Auto-translate my own messages",
                    "Also translate messages you sent when auto-translate is on",
                ).apply {
                    isChecked = plugin.autoTranslateOwn
                    setOnCheckedListener { plugin.autoTranslateOwn = it }
                },
        )

        divider()
        section("Custom API")
        note(
            "Optional. Google Translate is used when this is empty. " +
                "Enter a LibreTranslate-compatible endpoint, such as https://libretranslate.com/translate.",
        )
        input("API URL", plugin.apiUrl, InputType.TYPE_TEXT_VARIATION_URI) { plugin.apiUrl = it }
        input("API key (optional)", plugin.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) { plugin.apiKey = it }
    }

    private fun pickLanguage() {
        val languages = Languages.all
        val current = plugin.targetLanguage
        // Aliucord's SelectDialog is built from Discord's themed dialog, unlike a plain AlertDialog.
        SelectDialog().apply {
            title = "Translate to"
            items = languages.map { (code, name) -> if (code == current) "$name ✓" else name }.toTypedArray()
            onResultListener = { which ->
                plugin.targetLanguage = languages[which].first
                languageSubtitle?.text = languages[which].second
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateLanguage")
    }

    private fun input(hint: String, value: String, variation: Int, onChange: (String) -> Unit) {
        val input = TextInput(requireContext(), hint, value)
        input.editText.apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or variation
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
                setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
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

    private fun note(text: String): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            this.text = text
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    /** Returns the subtitle view so it can be updated. */
    private fun action(title: String, subtitle: String, action: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val sub = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = subtitle
            background = null
            setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        row.addView(sub, LinearLayout.LayoutParams(-1, -2))
        add(row)
        return sub
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
        languageSubtitle = null
        super.onDestroyView()
    }
}
