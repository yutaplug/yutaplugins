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
    private var serviceSubtitle: TextView? = null
    private var serviceOptions: LinearLayout? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("TranslateMessages")
        setActionBarSubtitle("Message translation")

        section("Translation")
        languageSubtitle = action("Translate to", Languages.label(plugin.targetLanguage)) { pickLanguage() }
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
        section("Translation service")
        serviceSubtitle = action("Service", serviceName(plugin.serviceId)) { pickService() }
        serviceOptions = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        add(serviceOptions!!)
        showServiceOptions()
    }

    private fun serviceName(id: String) = services.firstOrNull { it.first == id }?.second ?: services[0].second

    private fun pickService() {
        val current = plugin.serviceId
        SelectDialog().apply {
            title = "Translation service"
            items = services.map { (id, name) -> if (id == current) "$name ✓" else name }.toTypedArray()
            onResultListener = { which ->
                plugin.serviceId = services[which].first
                serviceSubtitle?.text = services[which].second
                showServiceOptions()
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateService")
    }

    /** Shows only the fields the selected service uses. */
    private fun showServiceOptions() {
        val container = serviceOptions ?: return
        container.removeAllViews()
        when (plugin.serviceId) {
            TranslateMessages.SERVICE_DEEPL -> {
                note(container, "Find your key in your DeepL account under API Keys & Limits.")
                input(container, "DeepL API key", plugin.deeplKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    plugin.deeplKey = it
                }
            }

            TranslateMessages.SERVICE_LIBRE -> {
                note(container, "Any LibreTranslate-compatible endpoint, such as https://libretranslate.com/translate.")
                input(container, "API URL", plugin.apiUrl, InputType.TYPE_TEXT_VARIATION_URI) { plugin.apiUrl = it }
                input(container, "API key (optional)", plugin.apiKey, InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                    plugin.apiKey = it
                }
            }

            else -> note(container, "Free and needs no API key.")
        }
    }

    private fun pickLanguage() {
        val languages = Languages.all
        val current = plugin.targetLanguage
        // Aliucord's SelectDialog is built from Discord's themed dialog, unlike a plain AlertDialog.
        SelectDialog().apply {
            title = "Translate to"
            items = languages.map { (code, _) ->
                val label = Languages.label(code)
                if (code == current) "$label ✓" else label
            }.toTypedArray()
            onResultListener = { which ->
                val code = languages[which].first
                plugin.targetLanguage = code
                languageSubtitle?.text = Languages.label(code)
            }
        }.show(Utils.appActivity.supportFragmentManager, "TranslateLanguage")
    }

    private fun input(parent: LinearLayout, hint: String, value: String, variation: Int, onChange: (String) -> Unit) {
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
        parent.addView(
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

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                this.text = text
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
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
        serviceSubtitle = null
        serviceOptions = null
        super.onDestroyView()
    }

    companion object {
        private val services = listOf(
            TranslateMessages.SERVICE_GOOGLE to "Google Translate (default)",
            TranslateMessages.SERVICE_DEEPL to "DeepL",
            TranslateMessages.SERVICE_LIBRE to "LibreTranslate",
        )
    }
}
