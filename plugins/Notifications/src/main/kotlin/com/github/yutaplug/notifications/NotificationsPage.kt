package com.github.yutaplug.notifications

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R
import rx.Subscription

internal class NotificationsPage(
    private val preferences: NotificationPreferences,
    private val badges: BadgePreferences,
) : AppFragment(Utils.getResId("widget_settings_account", "layout")) {
    private val controls = linkedMapOf<NotificationOption, CheckedSetting>()
    private val badgeControls = linkedMapOf<Int, CheckedSetting>()
    private val deviceControls = linkedMapOf<CheckedSetting, () -> Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private var deviceSubscription: Subscription? = null
    private var badgeStatus: TextView? = null
    private var boundView: View? = null
    private var statusView: TextView? = null
    private var closed = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        if (closed) {
            activity?.finish()
            return
        }
        // FragmentProxy forwards lifecycle callbacks without assigning this fragment's
        // mView. Track the supplied view instead of relying on Fragment.getView().
        boundView = view
        setActionBarTitle("Notifications")
        setActionBarSubtitle("User Settings")
        setActionBarDisplayHomeAsUpEnabled()
        val scroll = view.findViewById<ViewGroup>(Utils.getResId("settings_account_scroll", "id"))
        val body = scroll.getChildAt(0) as LinearLayout
        body.removeAllViews()
        body.setPadding(0, 0, 0, dp(16))
        controls.clear()
        badgeControls.clear()
        deviceControls.clear()
        deviceSubscription?.unsubscribe()
        // Only shown while loading or after a failure; tapping retries.
        statusView = caption("Loading notification settings…")
            .apply {
                setOnClickListener { preferences.refresh() }
            }.also { body.addView(it) }
        val native = StoreStream.getNotifications()
        heading(body, "In-app notifications", first = true)
        device(body, "Get notifications within Discord", { DeviceNotifications.current().isEnabledInApp }) {
            native.setEnabledInApp(it, true)
        }
        heading(body, "System notifications")
        device(body, "Get notifications outside of Discord", { DeviceNotifications.current().isEnabled }) {
            native.setEnabled(it)
        }
        device(body, "Disable notifications light", { DeviceNotifications.current().isDisableBlink }) {
            native.setNotificationLightDisabled(it)
        }
        device(body, "Disable notifications vibration", { DeviceNotifications.current().isDisableVibrate }) {
            native.setNotificationsVibrateDisabled(it)
        }
        device(
            body,
            "Wake screen for notifications",
            { DeviceNotifications.current().isWake },
            DeviceNotifications::wake,
        )
        device(body, "Disable sounds", { DeviceNotifications.current().isDisableSound }) {
            native.setNotificationSoundDisabled(it)
        }
        heading(body, "Reaction notifications")
        body.addView(caption("When your messages get reactions."))
        for (option in NotificationOption.reactions()) accountControl(body, option)
        heading(body, "Other notifications")
        for (option in NotificationOption.other()) accountControl(body, option)
        heading(body, "What friends are told")
        accountControl(body, NotificationOption.SHARE_ONLINE)
        accountControl(body, NotificationOption.SHARE_PROFILE)
        heading(body, "Badges")
        badgeStatus = caption("").also { body.addView(it) }
        badge(
            body,
            16,
            "Experimental Unreads",
            "Pick which channels are most important in a server.",
        )
        badge(
            body,
            32,
            "Mention on all messages",
            "Count every message in channels set to All Messages as a mention.",
        )
        renderDevice()
        deviceSubscription = native.settings.W({
            main.post { if (boundView != null && !closed) renderDevice() }
        }, { error ->
            main.post { if (!closed) statusView?.text = "Could not load device settings: ${error.message?.take(120)}" }
        })
        preferences.attach(this)
        badges.attach(this)
    }

    private fun accountControl(body: LinearLayout, option: NotificationOption) {
        val type = if (option.scalarValue == null) CheckedSetting.ViewType.SWITCH else CheckedSetting.ViewType.RADIO
        val description = option.description.takeIf { it.isNotEmpty() }
        val control = Utils.createCheckedSetting(requireContext(), type, option.title, description).apply {
            setOnCheckedListener { checked ->
                if (option.scalarValue == null || checked) {
                    preferences.choose(option, checked)
                } else {
                    isChecked = true
                }
            }
        }
        controls[option] = control
        body.addView(control)
    }

    private fun device(body: LinearLayout, title: String, read: () -> Boolean, write: (Boolean) -> Unit) {
        val control = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, null).apply {
            setOnCheckedListener {
                write(it)
                renderDevice()
            }
        }
        deviceControls[control] = read
        body.addView(control)
    }

    private fun renderDevice() {
        for ((control, read) in deviceControls) control.isChecked = read()
    }

    private fun badge(body: LinearLayout, bit: Int, title: String, description: String) {
        val control = Utils
            .createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                title,
                description,
            ).apply {
                setLabelTagText(Utils.getResId("beta", "string"))
                setLabelTagVisibility(true)
                setOnCheckedListener { badges.choose(bit, it) }
            }
        badgeControls[bit] = control
        body.addView(control)
    }

    fun renderBadges(flags: Int?, status: String) {
        if (boundView == null || closed) return
        badgeStatus?.text = status
        badgeStatus?.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        for ((bit, control) in badgeControls) {
            control.isChecked = flags != null && flags and bit != 0
            enableTree(control, flags != null)
        }
    }

    /** Discord's settings section header, preceded by the native divider. */
    private fun heading(body: LinearLayout, title: String, first: Boolean = false) {
        if (!first) {
            body.addView(
                View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }
        body.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    fun render(values: Map<NotificationOption, Boolean>?, status: String) {
        if (boundView == null || closed) return
        statusView?.text = status
        statusView?.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
        for ((option, control) in controls) {
            control.isChecked = values?.get(option) ?: false
            // CheckedSetting's child switch must also be disabled before the account loads.
            enableTree(control, values != null)
        }
    }

    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            var index = 0
            while (index < view.childCount) enableTree(view.getChildAt(index++), enabled)
        }
    }

    /** Discord's settings sub-text. */
    private fun caption(value: String): TextView = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
        text = value
        background = null
        setPaddingRelative(paddingStart, dp(4), paddingEnd, dp(8))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (!closed && boundView != null) preferences.refresh()
    }

    override fun onDestroyView() {
        preferences.detach(this)
        badges.detach(this)
        deviceSubscription?.unsubscribe()
        deviceSubscription = null
        boundView = null
        controls.clear()
        badgeControls.clear()
        deviceControls.clear()
        badgeStatus = null
        statusView = null
        super.onDestroyView()
    }

    fun close() {
        closed = true
        if (boundView != null) activity?.finish()
    }
}
