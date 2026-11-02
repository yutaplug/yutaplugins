package com.github.yutaplug.largeuploads

import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class LargeUploadsSettings(private val plugin: LargeUploads) : SettingsPage() {
    private val hostRows = mutableMapOf<Host, CheckedSetting>()
    private var hashSubtitle: TextView? = null
    private var timeSubtitle: TextView? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("LargeUploads")
        setActionBarSubtitle("Upload large files to a file host")
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))

        section("How it works")
        val own = UploadLimits.userMiB(StoreStream.getUsers().me)
        note(
            "Files over Discord's upload limit are uploaded to the file host below and sent as links in your " +
                "message. Your limit is $own MB, or more in boosted servers (50 MB at level 2, 100 MB at level 3). " +
                "Files too large for the host still show Discord's \"file too large\" message.",
        )

        divider()
        section("File host")
        for (host in Host.values()) hostRow(host)

        divider()
        section("catbox.moe")
        note(
            "Add your catbox.moe user hash to save uploads to your account, so you can manage or delete them later. " +
                "You can find it on catbox.moe after logging in. Without a hash, files are uploaded anonymously.",
        )
        hashSubtitle = action("User hash", hashStatus(), Host.CATBOX) { editHash() }
        action("Remove user hash", "Upload anonymously", Host.CATBOX) { removeHash() }

        divider()
        section("Litterbox")
        note("Litterbox deletes files after the time you choose, so their links stop working.")
        timeSubtitle = action("Keep files for", timeLabel(), Host.LITTERBOX) {
            SelectDialog().apply {
                title = "Keep Litterbox files for"
                items = FileHosts.LITTERBOX_TIME_LABELS
                onResultListener = { which ->
                    plugin.litterboxTime = FileHosts.LITTERBOX_TIMES[which]
                    timeSubtitle?.text = timeLabel()
                }
            }.show(parentFragmentManager, "LitterboxTime")
        }
    }

    /** A native radio row. Unavailable hosts are dimmed and tapping them shows why. */
    private fun hostRow(host: Host) {
        val reason = host.unavailableReason
        val subtitle = if (reason == null) host.description else "Unavailable: $reason"
        val row = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.RADIO, host.label, subtitle)
        row.isChecked = host == plugin.host
        if (reason == null) {
            row.e {
                plugin.host = host
                for ((other, otherRow) in hostRows) otherRow.isChecked = other == host
            }
        } else {
            row.c(reason)
        }
        hostRows[host] = row
        add(row)
    }

    private fun hashStatus(): String {
        val hash = plugin.catboxUserHash
        return if (hash.isEmpty()) "Not set, uploads are anonymous" else "Set, ending in ${hash.takeLast(4)}"
    }

    private fun timeLabel(): String =
        FileHosts.LITTERBOX_TIME_LABELS[FileHosts.LITTERBOX_TIMES.indexOf(plugin.litterboxTime)]

    private fun editHash() {
        val dialog = InputDialog()
            .setTitle("Catbox user hash")
            .setDescription("Paste the user hash from your catbox.moe account. Leave it empty to upload anonymously.")
            .setPlaceholderText("User hash")
            .setInputType(
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            )
        dialog.setOnOkListener {
            plugin.catboxUserHash = dialog.input
            hashSubtitle?.text = hashStatus()
            dialog.dismiss()
            Utils.showToast(if (plugin.catboxUserHash.isEmpty()) "User hash removed" else "User hash saved")
        }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                setSingleLine(true)
                setText(plugin.catboxUserHash)
                setSelection(text.length)
            }
            dialog.okButton.text = "Save"
        }
        dialog.show(parentFragmentManager, "CatboxUserHash")
    }

    private fun removeHash() {
        if (plugin.catboxUserHash.isEmpty()) {
            Utils.showToast("No user hash is set")
            return
        }
        val dialog = ConfirmDialog()
            .setTitle("Remove user hash")
            .setDescription("New uploads will no longer be saved to your catbox account.")
        dialog.setOnOkListener {
            plugin.catboxUserHash = ""
            hashSubtitle?.text = hashStatus()
            dialog.dismiss()
            Utils.showToast("User hash removed")
        }
        dialog.show(parentFragmentManager, "CatboxRemoveHash")
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
                this.text = text
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            },
        )
    }

    /**
     * A native settings row with a label and subtitle. Returns the subtitle so it can be updated. While [host]
     * is unavailable the row is dimmed like Discord's disabled settings and tapping it shows why.
     */
    private fun action(title: String, subtitle: String, host: Host, action: () -> Unit): TextView {
        val reason = host.unavailableReason
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            if (reason == null) {
                setOnClickListener { action() }
            } else {
                alpha = 0.3f
                setOnClickListener { Utils.showToast(reason) }
            }
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
        add(View(requireContext(), null, 0, R.i.UiKit_Settings_Divider), DimenUtils.dpToPx(1))
    }

    private fun add(view: View, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
    }

    override fun onDestroyView() {
        hostRows.clear()
        hashSubtitle = null
        timeSubtitle = null
        super.onDestroyView()
    }
}
