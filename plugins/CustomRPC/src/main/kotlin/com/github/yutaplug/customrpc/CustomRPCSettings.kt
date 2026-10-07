package com.github.yutaplug.customrpc

import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class CustomRPCSettings(private val settings: SettingsAPI, private val plugin: CustomRPC) : SettingsPage() {
    private val inputs = linkedMapOf<String, EditText>()
    private lateinit var enabled: CheckedSetting
    private var dirty = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("CustomRPC")
        setActionBarSubtitle("Plugin settings")
        inputs.clear()
        dirty = false
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(24))

        note("Create your profile activity. Save changes when you’re ready.")
        enabled = Utils.createCheckedSetting(
            requireContext(),
            CheckedSetting.ViewType.SWITCH,
            "Show activity",
            "Enabling also turns on Discord activity sharing.",
        ).apply {
            isChecked = plugin.isEnabled()
            setOnCheckedListener { checked ->
                if (checked && !save()) {
                    isChecked = false
                } else {
                    if (checked) plugin.enableActivitySharing(requireActivity())
                    plugin.setEnabled(checked)
                }
            }
        }
        add(enabled)

        divider()
        section("Activity")
        lateinit var typeSummary: TextView
        typeSummary = action("Activity type", CustomRPC.typeLabel(plugin.activityType())) {
            val current = plugin.activityType()
            SelectDialog().apply {
                title = "Activity type"
                items = CustomRPC.types.map { type ->
                    val label = CustomRPC.typeLabel(type)
                    if (type == current) "$label ✓" else label
                }.toTypedArray()
                onResultListener = { which ->
                    plugin.setType(CustomRPC.types[which])
                    typeSummary.text = CustomRPC.typeLabel(plugin.activityType())
                }
            }.show(Utils.appActivity.supportFragmentManager, "CustomRPCType")
        }
        input("Activity name", CustomRPC.NAME)
        input("Details", CustomRPC.DETAILS)
        input("State", CustomRPC.STATE)

        divider()
        section("Images")
        note("Public image URLs override asset keys. An application ID lets Discord proxy images for other clients.")
        input("Application ID (optional)", CustomRPC.APPLICATION_ID, InputType.TYPE_CLASS_NUMBER, 20)
        action("Developer Portal", "Manage applications and uploaded assets") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/developers/applications")))
            } catch (_: Exception) {
                Utils.showToast("Could not open Developer Portal")
            }
        }

        divider()
        section("Large image")
        input("Image URL", CustomRPC.LARGE_IMAGE_URL, URL_INPUT, 2048)
        input("Asset key (optional)", CustomRPC.LARGE_IMAGE)
        input("Hover text (optional)", CustomRPC.LARGE_IMAGE_TEXT)

        divider()
        section("Small image")
        input("Image URL", CustomRPC.SMALL_IMAGE_URL, URL_INPUT, 2048)
        input("Asset key (optional)", CustomRPC.SMALL_IMAGE)
        input("Hover text (optional)", CustomRPC.SMALL_IMAGE_TEXT)

        divider()
        section("Advanced")
        lateinit var flagsSummary: TextView
        flagsSummary = action("Activity flags", ActivityFlags.label(plugin.flags())) {
            ActivityFlagsSheet(plugin) { flagsSummary.text = ActivityFlags.label(plugin.flags()) }
                .show(Utils.appActivity.supportFragmentManager, "CustomRPCFlags")
        }
        note("Flags alone do not enable joining or spectating; these require activity secrets.")

        button("Save changes", true) {
            if (save()) Utils.showToast(if (plugin.isEnabled()) "Activity updated" else "Configuration saved")
        }
        button("Save and enable", true) {
            if (save()) {
                plugin.enableActivitySharing(requireActivity())
                plugin.setEnabled(true)
                enabled.isChecked = true
                Utils.showToast("CustomRPC enabled")
            }
        }
        button("Remove activity", false) {
            plugin.setEnabled(false)
            enabled.isChecked = false
            Utils.showToast("Activity removed")
        }
    }

    private fun input(
        label: String,
        key: String,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        maxLength: Int = 128,
    ) {
        val field = TextInput(requireContext(), label, plugin.value(key))
        field.editText.apply {
            inputType = type
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    dirty = true
                    error = null
                }

                override fun afterTextChanged(s: Editable?) {}
            })
            inputs[key] = this
        }
        linearLayout.addView(
            field,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
    }

    private fun save(): Boolean {
        val values = inputs.mapValues { it.value.text.toString().trim() }
        var valid = true
        val id = values[CustomRPC.APPLICATION_ID].orEmpty()
        if (id.isNotEmpty() && (id.toLongOrNull()?.takeIf { it > 0 } == null)) {
            inputs[CustomRPC.APPLICATION_ID]?.error = "Enter a valid positive application ID"
            valid = false
        }
        for (key in listOf(CustomRPC.LARGE_IMAGE_URL, CustomRPC.SMALL_IMAGE_URL)) {
            if (!values[key].isNullOrEmpty() && CustomRPC.publicImageUrl(values[key]) == null) {
                inputs[key]?.error = "Enter a complete HTTP or HTTPS URL"
                valid = false
            }
        }
        if (!valid) {
            inputs.values.firstOrNull { it.error != null }?.requestFocus()
            return false
        }
        plugin.save(values)
        dirty = false
        return true
    }

    override fun onDestroyView() {
        if (dirty) Utils.showToast("Unsaved CustomRPC changes discarded")
        inputs.clear()
        super.onDestroyView()
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
        row.contentDescription = "$title. $subtitle"
        add(row)
        return sub
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) {
        val button = DiscordSettingsUi.button(requireContext(), primary).apply {
            text = label
            setOnClickListener { action() }
        }
        linearLayout.addView(
            button,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), 0)
            },
        )
    }

    private fun divider() {
        add(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), DimenUtils.dpToPx(1))
    }

    private fun add(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
    }

    companion object {
        private const val URL_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
}
