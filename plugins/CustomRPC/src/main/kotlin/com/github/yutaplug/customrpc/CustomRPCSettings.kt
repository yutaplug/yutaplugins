package com.github.yutaplug.customrpc

import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.api.activity.ActivityTimestamps
import com.discord.api.activity.ActivityType
import com.discord.app.AppActivity
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.time.TimeUtils
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.R
import java.util.regex.Pattern

/**
 * The settings page is a live copy of Discord's own rich presence card. Tapping a part of the card opens a
 * pop-up to change it, and every change applies right away.
 */
class CustomRPCSettings(private val plugin: CustomRPC) : SettingsPage() {
    private class Card(
        val header: TextView,
        val largeImage: ImageView,
        val smallImage: ImageView,
        val title: TextView,
        val details: TextView,
        val state: TextView,
        val time: TextView,
        val primaryButton: MaterialButton,
        val secondaryButton: MaterialButton,
    )

    private var card: Card? = null
    private var appIdSummary: TextView? = null
    private var partySummary: TextView? = null
    private var errorNote: TextView? = null
    private var timestamps: ActivityTimestamps? = null
    private var toggleId = 0
    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            Utils.mainThread.postDelayed(this, 1000)
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("CustomRPC")
        setActionBarSubtitle("Plugin settings")
        linearLayout.removeAllViews()
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(24))

        val enabled = plugin.config().enabled
        toggleId = addHeaderButton(toggleTitle(enabled), toggleIcon(enabled)) {
            toggleEnabled()
            true
        }

        note(
            "Tap any part of the card to change it. Changes apply right away. Tap the eye at the top to show or " +
                "hide your activity; showing it also turns on Discord's \"Display current activity\" setting.",
        )
        buildCard()
        errorNote = note("").apply { visibility = View.GONE }

        divider()
        section("Application")
        appIdSummary = action("Application ID", "") { applicationIdTapped() }

        divider()
        section("Party")
        partySummary = action("Party size", "") { askPartySize() }

        plugin.onPreviewChanged = { render() }
        render()
    }

    override fun onResume() {
        super.onResume()
        Utils.mainThread.removeCallbacks(ticker)
        Utils.mainThread.post(ticker)
    }

    override fun onPause() {
        Utils.mainThread.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroyView() {
        Utils.mainThread.removeCallbacks(ticker)
        plugin.onPreviewChanged = null
        card = null
        super.onDestroyView()
    }

    // region Card

    private fun buildCard() {
        val root = LayoutInflater.from(requireContext())
            .inflate(Utils.getResId("widget_user_rich_presence", "layout"), linearLayout, false)
        fun <T : View> find(name: String): T = root.findViewById(Utils.getResId(name, "id"))
        val c = Card(
            find("rich_presence_header"),
            find("rich_presence_image_large"),
            find("rich_presence_image_small"),
            find("rich_presence_title"),
            find("rich_presence_details"),
            find("rich_presence_state"),
            find("rich_presence_time"),
            find("rich_presence_primary_button"),
            find("rich_presence_secondary_button"),
        )
        card = c

        for (v in listOf(c.header, c.title, c.details, c.state, c.time)) {
            ripple(v)
            v.visibility = View.VISIBLE
        }
        c.header.setOnClickListener { pickType() }
        c.title.setOnClickListener { askName() }
        c.details.setOnClickListener { askOptional(Keys.DETAILS, "Details", "The first line under the name.") }
        c.state.setOnClickListener { askOptional(Keys.STATE, "State", "The second line under the name.") }
        c.time.setOnClickListener { pickTimestampMode() }
        c.largeImage.setOnClickListener { editImage(large = true) }
        c.smallImage.setOnClickListener { editImage(large = false) }
        c.primaryButton.setOnClickListener { editButton(0) }
        c.secondaryButton.setOnClickListener { editButton(1) }

        linearLayout.addView(
            root,
            LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
            },
        )
    }

    private fun render() {
        val c = card ?: return
        val context = c.header.context
        val config = plugin.config()
        val activity = plugin.preview()
        val hasApp = config.applicationId != null

        c.header.text = try {
            PresenceUtils.getActivityHeader(context, activity)
        } catch (_: Throwable) {
            RpcTypes.label(config.type)
        }

        c.title.text = plugin.text(Keys.NAME).takeIf { it.isNotEmpty() } ?: config.name
        setOrPlaceholder(c.details, config.details, "Add details")
        val party = config.partySizes?.let { " (${it[0]} of ${it[1]})" }.orEmpty()
        setOrPlaceholder(c.state, config.state?.let { it + party }, "Add a state")

        timestamps = activity.o()
        updateTime()

        // Images.
        val largeUrl = if (hasApp) plugin.previewImage(config.largeImage) else null
        if (largeUrl != null) {
            MGImages.setImage(c.largeImage, largeUrl)
        } else {
            c.largeImage.setImageResource(placeholder(context))
        }
        c.largeImage.alpha = if (largeUrl != null) 1f else 0.5f
        val showSmall = hasApp && config.largeImage != null
        c.smallImage.visibility = if (showSmall) View.VISIBLE else View.GONE
        if (showSmall) {
            val smallUrl = plugin.previewImage(config.smallImage)
            if (smallUrl != null) {
                MGImages.setImage(c.smallImage, smallUrl)
                c.smallImage.alpha = 1f
            } else {
                c.smallImage.setImageResource(Utils.getResId("ic_add_circle_outline_grey_a60_24dp", "drawable"))
                c.smallImage.alpha = 0.7f
            }
        }

        // Buttons: the saved ones, then one "Add button" slot while there's room.
        val buttons = config.buttons
        renderButton(c.primaryButton, buttons.getOrNull(0)?.label, visible = true)
        renderButton(c.secondaryButton, buttons.getOrNull(1)?.label, visible = buttons.isNotEmpty())

        appIdSummary?.text = config.applicationId?.toString()
            ?: "Not set. Needed for images and buttons; tap to see how to get one."
        partySummary?.text = config.partySizes?.let { "${it[0]} of ${it[1]}" } ?: "Off"

        val errors = listOfNotNull(
            config.largeImage?.let { plugin.imageError(it) }?.let { "Large image: $it" },
            config.smallImage?.let { plugin.imageError(it) }?.let { "Small image: $it" },
        )
        errorNote?.apply {
            text = errors.joinToString("\n")
            visibility = if (errors.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun updateTime() {
        val c = card ?: return
        val ts = timestamps
        val context = c.time.context
        val now = System.currentTimeMillis()
        val text: CharSequence? = try {
            when {
                ts == null -> null
                ts.b() > 0 -> TimeUtils.INSTANCE.toFriendlyString(
                    now,
                    ts.b(),
                    context.getString(Utils.getResId("user_activity_timestamp_end_simple", "string")),
                    "timeDelta",
                )
                ts.c() > 0 -> TimeUtils.INSTANCE.toFriendlyString(
                    ts.c(),
                    now,
                    context.getString(Utils.getResId("user_activity_timestamp_start_simple", "string")),
                    "timeDelta",
                )
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        setOrPlaceholder(c.time, text, "Time hidden")
    }

    private fun setOrPlaceholder(view: TextView, value: CharSequence?, placeholder: String) {
        val empty = value == null || value.isEmpty()
        view.text = if (empty) placeholder else value
        view.alpha = if (empty) 0.5f else 1f
    }

    private fun renderButton(button: MaterialButton, label: String?, visible: Boolean) {
        button.visibility = if (visible) View.VISIBLE else View.GONE
        button.text = label ?: "Add button"
        button.alpha = if (label != null) 1f else 0.5f
    }

    private fun placeholder(context: android.content.Context): Int {
        val value = TypedValue()
        val attr = Utils.getResId("ic_game_icon_placeholder", "attr")
        return if (attr != 0 && context.theme.resolveAttribute(attr, value, true)) {
            value.resourceId
        } else {
            Utils.getResId("ic_add_circle_outline_grey_a60_24dp", "drawable")
        }
    }

    // endregion

    // region Card actions

    private fun pickType() {
        val current = plugin.config().type
        select("Activity type", RpcTypes.all.map { if (it == current) "${RpcTypes.label(it)} ✓" else RpcTypes.label(it) }) {
            val type = RpcTypes.all[it]
            if (type == ActivityType.STREAMING) {
                ask(
                    title = "Stream link",
                    description = "Discord only shows Streaming with a Twitch or YouTube link.",
                    placeholder = "https://twitch.tv/…",
                    initial = plugin.text(Keys.STREAM_URL),
                    inputType = URL_INPUT,
                    maxLength = Validation.URL_MAX,
                    validate = { v -> if (Validation.isStreamUrl(v)) null else "Enter a Twitch or YouTube link" },
                ) { v -> plugin.setType(type, v) }
            } else {
                plugin.setType(type)
            }
        }
    }

    private fun askName() = ask(
        title = "Activity name",
        description = "The bold name on the card, shown after \"Playing\", \"Watching\" and so on.",
        placeholder = "Name",
        initial = plugin.text(Keys.NAME),
        validate = { v -> if (v.isEmpty()) "Enter a name" else null },
    ) { v -> plugin.setText(Keys.NAME, v) }

    private fun askOptional(key: String, title: String, description: String) = ask(
        title = title,
        description = "$description Leave empty to remove it.",
        placeholder = title,
        initial = plugin.text(key),
    ) { v -> plugin.setText(key, v) }

    private fun pickTimestampMode() {
        val modes = TimestampMode.values()
        val current = plugin.config().timestampMode
        select("Show time", modes.map { if (it == current) "${it.label} ✓" else it.label }) {
            val mode = modes[it]
            if (!mode.needsDuration) {
                plugin.setTimestamp(mode)
                return@select
            }
            val description = if (mode == TimestampMode.ELAPSED) {
                "Shows as if you started this long ago and keeps counting, e.g. 2h shows \"02:00:00 elapsed\"."
            } else {
                "Counts down from this long, then the time is hidden."
            }
            ask(
                title = mode.label,
                description = "$description Examples: 2h 30m, 45m, 1d 4h, 2:30:00.",
                placeholder = "Duration",
                initial = plugin.text(Keys.TIMESTAMP_DURATION),
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                maxLength = 24,
                validate = { v -> if (Validation.parseDuration(v) == null) "Enter a duration like 2h 30m or 45m" else null },
            ) { v -> plugin.setTimestamp(mode, v) }
        }
    }

    private fun editImage(large: Boolean) {
        if (plugin.config().applicationId == null) {
            showTutorial()
            return
        }
        val key = if (large) Keys.LARGE_IMAGE else Keys.SMALL_IMAGE
        val textKey = if (large) Keys.LARGE_TEXT else Keys.SMALL_TEXT
        val name = if (large) "Large image" else "Small image"
        if (plugin.text(key).isEmpty()) {
            askImage(key, name)
            return
        }
        select(
            name,
            listOf("Change image", "Hover text", "Remove image"),
        ) {
            when (it) {
                0 -> askImage(key, name)
                1 -> askOptional(textKey, "$name hover text", "Shown when someone hovers over the image on desktop.")
                else -> plugin.edit {
                    plugin.settings.setString(key, "")
                    plugin.settings.setString(textKey, "")
                }
            }
        }
    }

    private fun askImage(key: String, name: String) = ask(
        title = name,
        description = "Paste a link to an image or a Discord image link, or type the name of an art asset from " +
            "your application.",
        placeholder = "https://… or asset name",
        initial = plugin.text(key),
        inputType = URL_INPUT,
        maxLength = 2048,
        validate = { v ->
            when {
                v.isEmpty() -> "Enter an image link or asset name"
                Validation.looksLikeUrl(v) && !Validation.isWebUrl(if (v.startsWith("www.")) "https://$v" else v) ->
                    "Enter a full link starting with https://"
                !Validation.looksLikeUrl(v) && v.length > ImageResolver.MAX_ASSET_LENGTH -> "That asset name is too long"
                else -> null
            }
        },
    ) { v -> plugin.setText(key, v) }

    private fun editButton(index: Int) {
        if (plugin.config().applicationId == null) {
            showTutorial()
            return
        }
        val labelKey = if (index == 0) Keys.BUTTON1_LABEL else Keys.BUTTON2_LABEL
        val urlKey = if (index == 0) Keys.BUTTON1_URL else Keys.BUTTON2_URL
        if (plugin.config().buttons.getOrNull(index) == null) {
            // New button: ask for the text, then the link, and save both together.
            askButtonText(labelKey, "") { label -> askButtonUrl(urlKey, "") { url -> saveButton(labelKey, label, urlKey, url) } }
            return
        }
        select("Button ${index + 1}", listOf("Change text", "Change link", "Remove button")) {
            when (it) {
                0 -> askButtonText(labelKey, plugin.text(labelKey)) { v -> plugin.setText(labelKey, v) }
                1 -> askButtonUrl(urlKey, plugin.text(urlKey)) { v -> plugin.setText(urlKey, v) }
                else -> removeButton(index)
            }
        }
    }

    private fun askButtonText(key: String, initial: String, onSave: (String) -> Unit) = ask(
        title = "Button text",
        description = "Other people see this button on your activity and can tap it. Discord doesn't show your " +
            "own buttons to you.",
        placeholder = "Text",
        initial = initial,
        maxLength = Validation.BUTTON_LABEL_MAX,
        validate = { v -> if (v.isEmpty()) "Enter the button text" else null },
        onSave = onSave,
    )

    private fun askButtonUrl(key: String, initial: String, onSave: (String) -> Unit) = ask(
        title = "Button link",
        description = "The page the button opens.",
        placeholder = "https://…",
        initial = initial,
        inputType = URL_INPUT,
        maxLength = Validation.URL_MAX,
        validate = { v -> if (Validation.isWebUrl(v)) null else "Enter a full link starting with https://" },
        onSave = onSave,
    )

    private fun saveButton(labelKey: String, label: String, urlKey: String, url: String) = plugin.edit {
        plugin.settings.setString(labelKey, label)
        plugin.settings.setString(urlKey, url)
    }

    private fun removeButton(index: Int) = plugin.edit {
        val s = plugin.settings
        if (index == 0) {
            // Move the second button up.
            s.setString(Keys.BUTTON1_LABEL, plugin.text(Keys.BUTTON2_LABEL))
            s.setString(Keys.BUTTON1_URL, plugin.text(Keys.BUTTON2_URL))
        }
        s.setString(Keys.BUTTON2_LABEL, "")
        s.setString(Keys.BUTTON2_URL, "")
    }

    // endregion

    // region Other settings

    /** Without an ID yet, explain how to get one first; with one, edit it directly. */
    private fun applicationIdTapped() {
        if (plugin.config().applicationId == null) showTutorial() else askApplicationId()
    }

    private fun askApplicationId() = ask(
        title = "Application ID",
        description = "Paste the Application ID from the General Information page of your application in the " +
            "Discord Developer Portal. Leave empty to remove it.",
        placeholder = "Application ID",
        initial = plugin.text(Keys.APPLICATION_ID),
        inputType = InputType.TYPE_CLASS_NUMBER,
        maxLength = 20,
        validate = { v ->
            if (v.isEmpty() || Validation.applicationId(v) != null) null else "Application IDs are 17 to 20 digits"
        },
    ) { v -> plugin.setText(Keys.APPLICATION_ID, v) }

    private fun askPartySize() {
        val sizes = plugin.config().partySizes
        ask(
            title = "Party size",
            description = "Shown after the state, like \"In a match (2 of 4)\". Enter the current and max size, " +
                "e.g. 2/4. Leave empty to turn it off.",
            placeholder = "2/4",
            initial = sizes?.let { "${it[0]}/${it[1]}" }.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            maxLength = 20,
            validate = { v ->
                if (v.isEmpty()) {
                    null
                } else {
                    val m = PARTY.matcher(v.lowercase())
                    when {
                        !m.matches() -> "Enter it like 2/4"
                        m.group(2)!!.toLong() < 1 -> "The max size must be at least 1"
                        m.group(1)!!.toLong() > m.group(2)!!.toLong() -> "The current size can't be bigger than the max"
                        else -> null
                    }
                }
            },
        ) { v ->
            val m = PARTY.matcher(v.lowercase())
            val matched = v.isNotEmpty() && m.matches()
            plugin.edit {
                plugin.settings.setString(Keys.PARTY_SIZE, if (matched) m.group(1)!! else "")
                plugin.settings.setString(Keys.PARTY_MAX, if (matched) m.group(2)!! else "")
            }
        }
    }

    private fun toggleTitle(enabled: Boolean) = if (enabled) "Hide activity" else "Show activity"

    private fun toggleIcon(enabled: Boolean) =
        Utils.getResId(if (enabled) "ic_visibility_white_24dp" else "ic_visibility_off_white_a60_24dp", "drawable")

    private fun toggleEnabled() {
        val enable = !plugin.config().enabled
        plugin.setEnabled(enable, requireActivity() as? AppActivity)
        headerBar.menu.findItem(toggleId)?.apply {
            setIcon(toggleIcon(enable))
            title = toggleTitle(enable)
        }
        render()
        Utils.showToast(if (enable) "Activity shown" else "Activity hidden")
    }

    private fun showTutorial() {
        val dialog = ConfirmDialog()
            .setTitle("Get an application ID")
            .setDescription(tutorialText())
        dialog.setOnOkListener {
            dialog.dismiss()
            askApplicationId()
        }
        val manager = parentFragmentManager
        dialog.show(manager, "CustomRPCTutorial")
        // Rename the buttons, and keep long text scrollable on small screens.
        try {
            manager.executePendingTransactions()
            dialog.getOKButton().setText("Enter ID")
            dialog.getCancelButton().text = "Close"
            dialog.getBody().apply {
                maxHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
                isVerticalScrollBarEnabled = true
            }
        } catch (_: Exception) {
        }
    }

    private fun tutorialText(): CharSequence {
        val text = SpannableStringBuilder()
        fun bold(value: String) {
            val start = text.length
            text.append(value)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.append("Images and buttons need a Discord application. It's free and takes a minute.\n\n")
        bold("1. ")
        text.append("Open the ")
        val linkStart = text.length
        text.append("Discord Developer Portal")
        text.setSpan(URLSpan(PORTAL_URL), linkStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.append(" and log in.\n\n")
        bold("2. ")
        text.append("Tap New Application, type any name and create it.\n\n")
        bold("3. ")
        text.append("On General Information, copy the Application ID, then tap Enter ID below and paste it.\n\n")
        bold("4. ")
        text.append("Tap the large image on the card and enter one of these:\n")
        text.append("•  a link to any public image (https://…)\n")
        text.append("•  a Discord image link (cdn.discordapp.com or media.discordapp.net)\n")
        text.append("•  the name of an image you uploaded under Rich Presence → Art Assets\n\n")
        bold("5. ")
        text.append("Tap the eye at the top to show your activity. Images can take a few seconds to appear.\n\n")
        text.append(
            "Discord image links expire after about a day; CustomRPC renews them automatically while Discord is open. " +
                "The same application ID also enables buttons.",
        )
        return text
    }

    // endregion

    // region Dialogs

    private fun select(title: String, items: List<String>, onPick: (Int) -> Unit) {
        SelectDialog().apply {
            this.title = title
            this.items = items.toTypedArray()
            onResultListener = { onPick(it) }
        }.show(parentFragmentManager, "CustomRPCSelect")
    }

    /** A text pop-up. [validate] returns an error to keep it open, or null to save. */
    private fun ask(
        title: String,
        description: String,
        placeholder: String,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        maxLength: Int = Validation.TEXT_MAX,
        validate: (String) -> String? = { null },
        onSave: (String) -> Unit,
    ) {
        val dialog = InputDialog()
        dialog.setTitle(title)
            .setDescription(description)
            .setPlaceholderText(placeholder)
            .setInputType(inputType)
            .setOnOkListener {
                val value = dialog.input.trim()
                val error = validate(value)
                if (error != null) {
                    dialog.inputLayout.error = error
                } else {
                    dialog.dismiss()
                    onSave(value)
                }
            }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
                setSingleLine(true)
                setText(initial)
                setSelection(text.length)
            }
            dialog.okButton.text = "Save"
        }
        dialog.show(parentFragmentManager, "CustomRPCInput")
    }

    // endregion

    // region Views

    private fun ripple(view: View) {
        val value = TypedValue()
        if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            view.setBackgroundResource(value.resourceId)
        }
        view.isClickable = true
        view.isFocusable = true
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
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    /** A tappable row with a title and a subtitle. Returns the subtitle so it can be updated. */
    private fun action(title: String, subtitle: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            ripple(this)
            setOnClickListener { onClick() }
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

    // endregion

    companion object {
        private const val PORTAL_URL = "https://discord.com/developers/applications"
        private const val URL_INPUT =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        private val PARTY: Pattern = Pattern.compile("^(\\d{1,6})\\s*(?:/|of)\\s*(\\d{1,6})$")
    }
}
