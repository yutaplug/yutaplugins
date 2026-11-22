package com.github.yutaplug.typingnotifications

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SettingsPage
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.drawable.DrawableCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R
import com.github.yutaplug.typingnotifications.TypingNotifications.Companion as Keys

class TypingNotificationsSettings : SettingsPage() {
    /**
     * Always the running plugin's own SettingsAPI. Each SettingsAPI keeps an in-memory copy of the
     * settings, so writing through a second instance would only reach the plugin after a restart.
     */
    private val settings: SettingsAPI
        get() = TypingNotifications.instance?.settings ?: SettingsAPI("TypingNotifications")

    /** The rendered entries of each list, rebuilt when it changes. */
    private val lists = HashMap<String, LinearLayout>()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Typing Notifications")
        setActionBarSubtitle("Plugin settings")
        lists.clear()

        val notify = section("Notifications", first = true)
        toggle(notify, Keys.KEY_IN_APP, "In-app notifications", "Show a banner inside Discord.", true)
        toggle(
            notify,
            Keys.KEY_COMPACT_BANNER,
            "Compact banner",
            "Show a small banner styled like the chat typing bar instead of Discord's message banner.",
            false,
        )
        toggle(notify, Keys.KEY_PUSH, "Push notifications", "Post an Android notification.", true)
        toggle(
            notify,
            Keys.KEY_PUSH_BACKGROUND_ONLY,
            "Push only in the background",
            "Skip push notifications while Discord is open.",
            false,
        )
        toggle(
            notify,
            Keys.KEY_IGNORE_CURRENT,
            "Ignore the open channel",
            "Don't notify for the channel you're looking at.",
            false,
        )
        toggle(notify, Keys.KEY_IGNORE_BOTS, "Ignore bots", null, false)

        val users = section("Users")
        users.addView(note("Blacklisted users never notify. If the whitelist has entries, only those users notify."))
        list(users, Filters.BLOCKED_USERS, "Blacklisted users", "user")
        list(users, Filters.ALLOWED_USERS, "Whitelisted users", "user")

        val places = section("Servers, channels and DMs")
        places.addView(
            note(
                "Every server, channel and DM notifies by default. Long-press one to blacklist or whitelist it. " +
                    "If any whitelist has entries, only whitelisted places notify; blacklists always win.",
            ),
        )
        list(places, Filters.BLOCKED_SERVERS, "Blacklisted servers", "server")
        list(places, Filters.ALLOWED_SERVERS, "Whitelisted servers", "server")
        list(places, Filters.BLOCKED_CHANNELS, "Blacklisted channels", "channel")
        list(places, Filters.ALLOWED_CHANNELS, "Whitelisted channels", "channel")
        list(places, Filters.BLOCKED_DMS, "Blacklisted DMs", "DM channel")
        list(places, Filters.ALLOWED_DMS, "Whitelisted DMs", "DM channel")
    }

    override fun onResume() {
        super.onResume()
        // Long-press menu changes happen while this page is in the back stack.
        lists.keys.toList().forEach(::render)
    }

    override fun onDestroyView() {
        lists.clear()
        super.onDestroyView()
    }

    private fun section(title: String, first: Boolean = false): LinearLayout {
        val context = requireContext()
        if (!first) {
            linearLayout.addView(View(context, null, 0, R.i.UiKit_Settings_Divider), LinearLayout.LayoutParams(-1, dp(1)))
        }
        linearLayout.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                background = null
            },
        )
        return column().also { linearLayout.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun toggle(parent: LinearLayout, key: String, title: String, subtitle: String?, default: Boolean) {
        parent.addView(
            Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, title, subtitle).apply {
                isChecked = settings.getBool(key, default)
                setOnCheckedListener { settings.setBool(key, it) }
            },
        )
    }

    private fun note(value: String) = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
        text = value
        background = null
        setPaddingRelative(paddingStart, dp(4), paddingEnd, dp(8))
    }

    /** A list heading with an "Add" action, followed by its entries. */
    private fun list(parent: LinearLayout, key: String, title: String, noun: String) {
        row(parent, title, "ic_add_24dp", "Add") { add(key, title, noun) }
        val entries = column()
        parent.addView(entries, LinearLayout.LayoutParams(-1, -2))
        lists[key] = entries
        render(key)
    }

    private fun render(key: String) {
        val entries = lists[key] ?: return
        val context = entries.context
        entries.removeAllViews()
        Filters.read(settings, key).forEach { id ->
            val name = Filters.describe(context, key, id)
            row(entries, name ?: id.toString(), null, if (name == null) null else id.toString(), indent = true) {
                remove(key, id, name ?: id.toString())
            }
        }
    }

    private fun add(key: String, title: String, noun: String) {
        val dialog = InputDialog()
        dialog
            .setTitle(title)
            .setDescription("Enter the $noun ID. Turn on Developer Mode and long-press to copy IDs.")
            .setPlaceholderText("ID")
            .setInputType(InputType.TYPE_CLASS_NUMBER)
            .setOnOkListener {
                val id = dialog.input.trim().toLongOrNull()?.takeIf { it > 0 }
                when {
                    id == null -> dialog.inputLayout.error = "Enter a numeric ID"
                    Filters.contains(settings, key, id) -> dialog.inputLayout.error = "Already added"
                    else -> {
                        Filters.set(settings, key, id, true)
                        render(key)
                        dialog.dismiss()
                    }
                }
            }
        dialog.show(parentFragmentManager, "TypingNotificationsAdd")
    }

    private fun remove(key: String, id: Long, label: String) {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Remove $label?")
            .setDescription("It will go back to the default behavior.")
            .setIsDangerous(true)
            .setOnOkListener {
                Filters.set(settings, key, id, false)
                render(key)
                dialog.dismiss()
            }
        dialog.show(parentFragmentManager, "TypingNotificationsRemove")
    }

    /** A Discord settings row: a `UiKit_Settings_Item_Icon` label with an optional trailing value. */
    private fun row(
        parent: LinearLayout,
        title: String,
        iconName: String?,
        value: String?,
        indent: Boolean = false,
        click: () -> Unit,
    ) {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            val ripple = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                background = context.getDrawable(ripple.resourceId)
            }
            setOnClickListener { click() }
        }
        val label = TextView(context, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            text = title
            background = null
            isSingleLine = true
            if (indent) setPaddingRelative(paddingStart + dp(16), paddingTop, paddingEnd, paddingBottom)
            val res = iconName?.let { Utils.getResId(it, "drawable") } ?: 0
            val tint = Utils.getResId("colorInteractiveNormal", "attr")
            if (res != 0 && tint != 0) {
                val drawable = DrawableCompat.getDrawable(context, res, ColorCompat.getThemedColor(context, tint))
                setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) {
            row.addView(
                TextView(context, null, 0, R.i.UiKit_Settings_Item_Compound_Right).apply { text = value },
                LinearLayout.LayoutParams(-2, -2),
            )
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun column() = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
