package com.github.yutaplug.fakedecor

import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class FakeDecorSettings(
    @Suppress("UNUSED_PARAMETER") settings: SettingsAPI,
    private val plugin: FakeDecor,
) : SettingsPage() {
    private var assetInput: EditText? = null
    private var authorizationStatus: TextView? = null
    private var boundSettingsView: View? = null

    // Aliucord's FragmentProxy owns Fragment attachment; track the view supplied to this page instead.
    internal val isSettingsViewActive: Boolean
        get() = boundSettingsView?.isAttachedToWindow == true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        boundSettingsView = view
        setActionBarTitle("FakeDecor")
        setActionBarSubtitle("Avatar decorations")
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))

        section("Local decoration")
        note("Choose a custom avatar decoration. Your selection is saved separately for each Discord account.")
        val input = TextInput(requireContext(), "Decoration hash or asset", plugin.getSelectedAsset())
        assetInput = input.editText.apply { setSingleLine(true) }
        val inputParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        linearLayout.addView(input, inputParams)
        action("Apply locally", "Show this decoration in messages, lists, and profiles") {
            val asset = assetInput?.text?.toString().orEmpty()
            plugin.setSelectedAsset(asset)
            val selected = plugin.getSelectedAsset()
            assetInput?.setText(selected)
            Utils.showToast(if (selected.isEmpty()) "Decoration removed" else "Decoration applied")
        }
        action("Browse Decor presets", "Choose an available preset decoration") {
            Utils.showToast("Loading Decor presets…")
            plugin.fetchPresets(this)
        }

        divider()
        section("Display")
        add(
            Utils
                .createCheckedSetting(
                    requireContext(),
                    CheckedSetting.ViewType.SWITCH,
                    "Preserve official Discord decorations",
                    "Show the official decoration when one is available",
                ).apply {
                    setPaddingRelative(paddingStart, 0, paddingEnd, paddingBottom)
                    findViewById<View>(Utils.getResId("setting_container", "id"))?.apply {
                        setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, paddingBottom)
                    }
                    isChecked = plugin.isPreserveOriginalDecor()
                    setOnCheckedListener { plugin.setPreserveOriginalDecor(it) }
                },
        )

        divider()
        section("Decor account")
        authorizationStatus = note("")
        refreshAuthState()
        action("Authorize with Discord", "Connect this Discord account in your browser") {
            plugin.authorizeDecor(requireContext())
        }
        action("Finish authorization", "Copy the returned token, then tap here") {
            plugin.finishBrowserAuthorization(requireContext(), this)
        }
        action("Disconnect Decor", "Remove access for this Discord account") {
            plugin.disconnectDecor()
            refreshAuthState()
        }

        divider()
        section("Cloud decorations")
        action("My Decor decorations", "Select or delete decorations from your account") {
            plugin.fetchOwnDecorations(this)
        }
        action("Sync from Decor", "Load your current Decor selection") { plugin.refreshOwnDecoration() }
        action("Sync selection to Decor", "Save your local selection to Decor") { plugin.applyToDecorService() }
        action("Upload custom decoration", "Submit a PNG or APNG for review") {
            if (!plugin.isAuthorized()) {
                Utils.showToast("Authorize Decor before uploading")
            } else {
                DecorationPickerFragment.open(requireActivity())
            }
        }

        divider()
        section("About Decor")
        note(
            "Decor is a separate service. Presets and local selection work without an account. " +
                "Cloud actions require authorization, and uploaded decorations may need review.",
        )
    }

    fun refreshAuthState() {
        authorizationStatus?.text = if (plugin.isAuthorized()) {
            "Connected to Decor for this Discord account"
        } else {
            "Not connected to Decor"
        }
    }

    fun showPresets(presets: List<*>) {
        val items = mutableListOf<PresetsDialog.Preset>()
        for (raw in presets) {
            val preset = raw as? Map<*, *> ?: continue
            val decorations = preset["decorations"] as? List<*> ?: continue
            for (item in decorations) {
                val decoration = item as? Map<*, *> ?: continue
                val asset = FakeDecor.decorationAsset(decoration).takeIf { it.isNotEmpty() } ?: continue
                items +=
                    PresetsDialog.Preset(asset, decoration["alt"]?.toString() ?: asset, preset["name"]?.toString().orEmpty())
            }
        }
        if (items.isEmpty()) {
            Utils.showToast("No Decor presets found")
            return
        }
        PresetsDialog().apply {
            this.presets = items
            onPicked = { preset ->
                assetInput?.setText(preset.asset)
                assetInput?.let { it.setSelection(it.length()) }
            }
        }.show(Utils.appActivity.supportFragmentManager, "FakeDecorPresets")
    }

    fun showOwnDecorations(decorations: List<*>) {
        val valid = decorations.filterIsInstance<Map<*, *>>().filter {
            FakeDecor.decorationAsset(it).isNotEmpty()
        }
        if (valid.isEmpty()) {
            Utils.showToast("No Decor decorations found")
            return
        }
        val selected = plugin.getSelectedAsset()
        val labels = valid.map {
            val asset = FakeDecor.decorationAsset(it)
            (if (asset == selected) "✓ " else "") +
                (it["alt"] ?: asset) +
                (if (it["reviewed"] == false) " (pending review)" else "")
        }
        // Aliucord's dialogs are built from Discord's themed dialogs, unlike a plain AlertDialog.
        SelectDialog().apply {
            title = "My Decor decorations"
            items = labels.toTypedArray()
            onResultListener = { which -> showDecorationActions(valid[which], labels[which]) }
        }.show(Utils.appActivity.supportFragmentManager, "FakeDecorOwn")
    }

    private fun showDecorationActions(decoration: Map<*, *>, label: String) {
        SelectDialog().apply {
            title = label
            items = arrayOf("Use", "Delete")
            onResultListener = { which ->
                if (which == 0) {
                    if (decoration["reviewed"] == false) {
                        Utils.showToast("This decoration is still pending review")
                    } else {
                        plugin.setSelectedAsset(FakeDecor.decorationAsset(decoration))
                        assetInput?.setText(plugin.getSelectedAsset())
                        Utils.showToast("Decoration applied")
                    }
                } else {
                    confirmDelete(decoration["hash"]?.toString())
                }
            }
        }.show(Utils.appActivity.supportFragmentManager, "FakeDecorOwnActions")
    }

    private fun confirmDelete(hash: String?) {
        if (hash == null) return
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Delete decoration?")
            .setDescription("This removes the decoration from your Decor account.")
            .setIsDangerous(true)
            .setOnOkListener {
                plugin.deleteDecoration(hash)
                dialog.dismiss()
            }.show(Utils.appActivity.supportFragmentManager, "FakeDecorDelete")
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
            // Notes are descriptive, so they do not inherit the settings-row ripple.
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    private fun action(title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            contentDescription = "$title. $subtitle"
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
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                text = subtitle
                background = null
                setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        add(row)
    }

    private fun divider() {
        add(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), DimenUtils.dpToPx(1))
    }

    private fun add(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
    }

    override fun onDestroyView() {
        boundSettingsView = null
        assetInput = null
        authorizationStatus = null
        super.onDestroyView()
    }
}
