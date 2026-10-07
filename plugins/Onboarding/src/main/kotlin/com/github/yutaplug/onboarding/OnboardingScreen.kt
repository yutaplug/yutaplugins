package com.github.yutaplug.onboarding

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.CompoundButton
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.Toolbar
import androidx.core.widget.NestedScrollView
import com.discord.views.SearchInputView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.button.MaterialButton
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SelectDialog
import com.discord.stores.StoreStream
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.facebook.drawee.view.SimpleDraweeView
import java.util.concurrent.Executors

/** Member-facing community onboarding and Channels & Roles for the 126.21 view system. */
internal class OnboardingScreen(
    private val activity: Activity,
    private val guildId: Long,
    private val firstRun: Boolean,
    personalized: Boolean,
    private val onConfigChanged: (OnboardingConfig) -> Unit,
    private val onInitialComplete: () -> Unit,
    private val onPersonalizedChanged: (Boolean) -> Unit,
    private val isChannelHidden: (Long) -> Boolean,
    private val onChannelsChanged: (Map<Long, Boolean>) -> Unit,
    private val onClosed: () -> Unit,
) {
    private enum class Tab { CUSTOMIZE, BROWSE }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(activity)
    private val primary = color("colorBackgroundPrimary", 0xff313338.toInt())
    private val secondary = color("colorBackgroundSecondary", 0xff2b2d31.toInt())
    private val normal = color("colorTextNormal", 0xfff2f3f5.toInt())
    private val muted = color("colorTextMuted", 0xffb5bac1.toInt())
    private val brand = color("color_brand", 0xff5865f2.toInt())
    private val guildName = StoreStream.getGuilds().getGuild(guildId)?.name ?: "Server"
    private var config: OnboardingConfig? = null
    private var saved: Set<String> = emptySet()
    private val selected = linkedSetOf<String>()
    private var channels: List<BrowseChannel> = emptyList()
    private val flags = mutableMapOf<Long, Int>()
    private var tab = Tab.CUSTOMIZE
    private var initial = firstRun
    private var personalized = personalized
    private var promptIndex = 0
    private var searchQuery = ""
    private var loading = true
    private var browseError: String? = null
    private var saving = false
    private var closed = false
    private var generation = 0
    private lateinit var toolbar: Toolbar
    private lateinit var tabs: TabLayout
    private lateinit var search: SearchInputView
    private var searchWatcher: TextWatcher? = null
    private lateinit var scroll: NestedScrollView
    private lateinit var content: LinearLayout
    private lateinit var footer: LinearLayout

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(createView())
        dialog.setOnDismissListener {
            closed = true
            generation++
            worker.shutdownNow()
            main.removeCallbacksAndMessages(null)
            searchWatcher?.let { (search.editText as? EditText)?.removeTextChangedListener(it) }
            searchWatcher = null
            tabs.clearOnTabSelectedListeners()
            onClosed()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (initial && promptIndex > 0) {
                    promptIndex--
                    render()
                } else closeWithConfirmation()
                true
            } else false
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(primary))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = color("colorBackgroundTertiary", secondary)
            navigationBarColor = color("colorBackgroundTertiary", secondary)
        }
        load()
    }

    fun dismiss() = dialog.dismiss()

    private fun createView(): View {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }
        // Reuse the toolbar and tab XML from 126.21's native thread browser.
        val nativePage = LayoutInflater.from(activity).inflate(
            Utils.getResId("widget_thread_browser", "layout"), null, false,
        )
        toolbar = nativePage.findViewById(Utils.getResId("action_bar_toolbar", "id"))
        (toolbar.parent as ViewGroup).removeView(toolbar)
        toolbar.title = "Channels & Roles"
        val backIcon = Utils.getResId("ic_arrow_back_white_24dp", "drawable")
        if (backIcon != 0) {
            toolbar.setNavigationIcon(backIcon)
            toolbar.navigationIcon?.setTint(color("colorInteractiveNormal", muted))
        }
        toolbar.navigationContentDescription = "Back"
        toolbar.setNavigationOnClickListener {
            if (initial && promptIndex > 0) {
                promptIndex--
                render()
            } else closeWithConfirmation()
        }
        page.addView(toolbar)
        tabs = nativePage.findViewById(Utils.getResId("action_bar_tabs", "id"))
        (tabs.parent as ViewGroup).removeView(tabs)
        tabs.addTab(tabs.newTab().setText("Customize"))
        tabs.addTab(tabs.newTab().setText("Browse Channels"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selectedTab: TabLayout.Tab) {
                val next = if (selectedTab.position == 0) Tab.CUSTOMIZE else Tab.BROWSE
                if (tab == next) return
                tab = next
                render()
                scroll.scrollTo(0, 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        page.addView(tabs)
        search = SearchInputView(activity, null).apply { setHint("Search") }
        page.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), dp(14), dp(16), dp(14))
        })
        searchWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                search.b(searchQuery)
                config?.let {
                    if (tab == Tab.BROWSE && !loading) {
                        content.removeAllViews()
                        renderBrowse(it)
                        scroll.scrollTo(0, 0)
                    }
                }
            }
        }
        (search.editText as? EditText)?.addTextChangedListener(searchWatcher)
        search.onClearClicked = { search.setText("") }
        scroll = NestedScrollView(activity).apply { isFillViewport = true }
        content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(secondary)
        }
        page.addView(footer)
        render()
        return page
    }

    private fun load() {
        val task = ++generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                Triple(runCatching { api.getConfig(guildId) }, runCatching { api.getChannels(guildId) }, api)
            }
            main.post {
                if (closed || task != generation) return@post
                loading = false
                result.onSuccess { (configResult, browseResult, api) ->
                    if (configResult.isFailure && browseResult.isFailure) {
                        renderError(browseResult.exceptionOrNull() ?: configResult.exceptionOrNull()!!)
                        return@onSuccess
                    }
                    val loaded = configResult.getOrNull()
                        ?: OnboardingConfig(guildId, emptyList(), emptySet(), emptySet())
                    config = loaded
                    val valid = loaded.prompts.flatMap { prompt -> prompt.options.map { it.id } }.toSet()
                    saved = loaded.responses.filterTo(linkedSetOf()) { it in valid }
                    selected.clear()
                    selected.addAll(saved)
                    channels = browseResult.getOrDefault(emptyList())
                    browseError = browseResult.exceptionOrNull()?.message
                    channels.forEach { channel -> flags[channel.id] = api.getChannelFlags(guildId, channel.id) }
                    if (loaded.prompts.isEmpty()) tab = Tab.BROWSE
                    if (initial && loaded.prompts.none { it.inOnboarding }) initial = false
                    render()
                }.onFailure { error ->
                    renderError(error)
                }
            }
        }
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        footer.removeAllViews()
        search.visibility = if (!initial && !loading && tab == Tab.BROWSE) View.VISIBLE else View.GONE
        footer.visibility = if (initial || tab == Tab.CUSTOMIZE) View.VISIBLE else View.GONE
        val data = config
        if (loading) {
            toolbar.title = "Channels & Roles"
            tabs.visibility = View.GONE
            content.addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            })
            return
        }
        if (data == null) return
        if (initial) {
            renderInitial(data)
            return
        }
        toolbar.title = "Channels & Roles"
        tabs.visibility = View.VISIBLE
        tabs.getTabAt(if (tab == Tab.CUSTOMIZE) 0 else 1)?.select()
        toolbar.title = if (tab == Tab.BROWSE) "Browse Channels" else "Channels & Roles"
        when (tab) {
            Tab.CUSTOMIZE -> renderCustomize(data)
            Tab.BROWSE -> renderBrowse(data)
        }
    }

    private fun renderInitial(data: OnboardingConfig) {
        tabs.visibility = View.GONE
        val prompts = data.prompts.filter { it.inOnboarding }
        if (prompts.isEmpty()) {
            initial = false
            render()
            return
        }
        promptIndex = promptIndex.coerceIn(0, prompts.lastIndex)
        toolbar.title = "Welcome to $guildName"
        label("QUESTION ${promptIndex + 1} OF ${prompts.size}", 12f, brand)
        renderPrompt(prompts[promptIndex])
        if (promptIndex > 0) {
            footer.addView(actionButton("Back", false) {
                promptIndex--
                render()
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        footer.addView(actionButton(if (promptIndex == prompts.lastIndex) "Finish" else "Continue", true) {
            val prompt = prompts[promptIndex]
            if (prompt.required && prompt.options.none { it.id in selected }) {
                Utils.showToast("Choose an answer to continue")
                return@actionButton
            }
            if (promptIndex < prompts.lastIndex) {
                promptIndex++
                render()
                scroll.scrollTo(0, 0)
            } else save(true)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (promptIndex > 0) marginStart = dp(8) })
    }

    private fun renderCustomize(data: OnboardingConfig) {
        label("Choose your channels and roles in $guildName", 16f, normal)
        if (data.prompts.isEmpty()) {
            label("This server has no customization questions.", 14f, muted)
            return
        }
        data.prompts.forEach { renderPrompt(it) }
        footer.addView(actionButton(if (saving) "Saving…" else "Save Changes", true) { save(false) },
            LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun renderPrompt(prompt: OnboardingPrompt) {
        label(prompt.title.takeIf(String::hasVisibleText) ?: "Choose an answer", 18f, normal, top = 22)
        if (prompt.required) label("Required", 12f, brand)
        if (prompt.options.isEmpty()) label("No choices available", 14f, muted)
        if (prompt.type == 1 && prompt.options.isNotEmpty()) {
            renderDropdown(prompt)
            return
        }
        prompt.options.forEach { option ->
            val setting = createSetting(
                activity,
                if (prompt.singleSelect) CheckedSetting.ViewType.RADIO else CheckedSetting.ViewType.CHECK,
                option.title,
                option.description,
            )
            setting.isChecked = option.id in selected
            setting.setOnCheckedListener { checked ->
                if (checked && prompt.singleSelect) {
                    prompt.options.forEach { selected.remove(it.id) }
                }
                if (checked) selected += option.id else selected -= option.id
                if (prompt.singleSelect) {
                    val y = scroll.scrollY
                    scroll.post {
                        render()
                        scroll.scrollTo(0, y)
                    }
                }
            }
            val emoji = createEmojiView(option)
            if (emoji == null) {
                content.addView(setting, LinearLayout.LayoutParams(-1, -2))
            } else {
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(emoji, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                })
                emoji.setOnClickListener { setting.isChecked = !setting.isChecked }
                row.addView(setting, LinearLayout.LayoutParams(0, -2, 1f))
                content.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun createEmojiView(option: PromptOption): View? {
        val id = option.emojiId
        if (id != null) {
            val uri = runCatching { ModelEmojiCustom.getImageUri(id, option.emojiAnimated, 64) }
                .getOrElse {
                    val extension = if (option.emojiAnimated) "gif" else "png"
                    "https://cdn.discordapp.com/emojis/$id.$extension?size=64&quality=lossless"
                }
            return SimpleDraweeView(activity).apply {
                setImageURI(uri)
                contentDescription = option.emojiName
            }
        }
        if (!option.emojiName.hasVisibleText()) return null
        val name = option.emojiName
        val unicode = StoreStream.getEmojis().unicodeEmojiSurrogateMap[name]
            ?: StoreStream.getEmojis().unicodeEmojisNamesMap[name.trim(':')]
        val surrogates = unicode?.surrogates ?: name
        val codePoints = mutableListOf<String>()
        var index = 0
        while (index < surrogates.length) {
            val codePoint = Character.codePointAt(surrogates, index)
            codePoints += Integer.toHexString(codePoint)
            index += Character.charCount(codePoint)
        }
        val nativeCodePoints = unicode?.codePoints ?: codePoints.joinToString("_")
        val nativeUri = ModelEmojiUnicode.getImageUri(nativeCodePoints, activity)
        // Newer emoji may not exist in 126.21's bundled Twemoji resources.
        val uri = if (nativeUri != "res:///0") {
            nativeUri
        } else {
            val assetCodePoints = if ("200d" in codePoints) codePoints else codePoints.filter { it != "fe0f" }
            "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.3/assets/72x72/${assetCodePoints.joinToString("-")}.png"
        }
        return SimpleDraweeView(activity).apply {
            setImageURI(uri)
            contentDescription = option.emojiName
        }
    }

    private fun renderDropdown(prompt: OnboardingPrompt) {
        val chosen = prompt.options.filter { it.id in selected }
        val summary = chosen.joinToString(", ") { it.title.takeIf(String::hasVisibleText) ?: "Answer" }
            .takeIf(String::hasVisibleText)
            ?: if (prompt.singleSelect) "Select an answer" else "Select answers"
        content.addView(actionButton(summary, false) {
            showDropdownChoices(prompt)
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun showDropdownChoices(prompt: OnboardingPrompt) {
        if (prompt.singleSelect) {
            SelectDialog().apply {
                title = prompt.title
                items = prompt.options.map { option ->
                    // SelectDialog rows are plain text, so only unicode emoji can be shown.
                    val emoji = option.emojiName.takeIf { option.emojiId == null && it.hasVisibleText() }
                    val label = option.title.takeIf(String::hasVisibleText) ?: "Answer"
                    val text = if (emoji != null) "$emoji $label" else label
                    if (option.id in selected) "$text ✓" else text
                }.toTypedArray()
                onResultListener = { which ->
                    prompt.options.forEach { selected.remove(it.id) }
                    selected += prompt.options[which].id
                    render()
                }
            }.show(Utils.appActivity.supportFragmentManager, "OnboardingDropdown")
            return
        }
        val choices = BooleanArray(prompt.options.size) { prompt.options[it].id in selected }
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        prompt.options.forEachIndexed { index, option ->
            val setting = createSetting(activity, CheckedSetting.ViewType.CHECK, option.title, option.description)
            setting.isChecked = choices[index]
            setting.setOnCheckedListener { checked -> choices[index] = checked }
            val emoji = createEmojiView(option)
            if (emoji == null) {
                list.addView(setting, LinearLayout.LayoutParams(-1, -2))
            } else {
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(emoji, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(4)
                })
                emoji.setOnClickListener { setting.isChecked = !setting.isChecked }
                row.addView(setting, LinearLayout.LayoutParams(0, -2, 1f))
                list.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        lateinit var sheet: ChoicesSheet
        // Closing the sheet without Done keeps the previous answers, like the old Cancel button.
        val done = actionButton("Done", true) {
            prompt.options.forEach { selected.remove(it.id) }
            prompt.options.forEachIndexed { index, option ->
                if (choices[index]) selected += option.id
            }
            render()
            sheet.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) }
        }
        sheet = ChoicesSheet(prompt.title, listOf(list, done))
        sheet.show(Utils.appActivity.supportFragmentManager, "OnboardingDropdown")
    }

    private fun renderBrowse(data: OnboardingConfig) {
        if (!searchQuery.hasVisibleText()) {
            content.addView(createSetting(
                activity,
                CheckedSetting.ViewType.SWITCH,
                "Show all channels",
                "Turn off to show only the channels you follow.",
            ).apply {
                isChecked = !personalized
                setOnCheckedListener { showAll ->
                    personalized = !showAll
                    onPersonalizedChanged(personalized)
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        browseError?.let { error ->
            label(error, 13f, muted)
            content.addView(actionButton("Retry loading channels", false) { loadChannels() },
                LinearLayout.LayoutParams(-1, dp(44)))
            return
        }
        val categories = channels.filter { it.type == 4 }.associateBy(BrowseChannel::id)
        val groups = channels.filter { it.type != 4 }.groupBy(BrowseChannel::parentId)
        val groupIds = groups.keys.sortedWith(compareBy(
            { categories[it]?.position ?: Int.MIN_VALUE }, { categories[it]?.name.orEmpty() },
        ))
        var visibleGroups = 0
        groupIds.forEach { categoryId ->
            val members = groups[categoryId].orEmpty().sortedWith(compareBy(BrowseChannel::position, BrowseChannel::name))
            val heading = categories[categoryId]?.name ?: "Other Channels"
            val visible = members.filter {
                !searchQuery.hasVisibleText() || heading.contains(searchQuery, true) ||
                    it.name.contains(searchQuery, true) || it.topic.contains(searchQuery, true)
            }
            if (visible.isEmpty()) return@forEach
            visibleGroups++
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(styledText("UiKit_TextView_Semibold").apply {
                text = heading
                setTextColor(color("colorHeaderSecondary", muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // Following a category applies to every child, including filtered search results.
            row.addView(com.google.android.material.checkbox.MaterialCheckBox(activity).apply {
                isChecked = members.all { isChannelSelected(data, it.id) }
                text = "Follow Category"
                val appearance = Utils.getResId("App_TabLayout_Text", "style")
                if (appearance != 0) setTextAppearance(activity, appearance)
                setTextColor(muted)
                buttonTintList = compoundTint()
                contentDescription = "Follow category $heading"
                setOnCheckedChangeListener { _, enabled ->
                    updateCategory(categoryId, members, enabled, this)
                }
            }, LinearLayout.LayoutParams(-2, dp(48)))
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            val group = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val backgroundId = Utils.getResId("drawable_rect_rounded_bg_secondary", "drawable")
                if (backgroundId != 0) setBackgroundResource(backgroundId) else setBackgroundColor(secondary)
            }
            visible.forEachIndexed { index, channel ->
                if (index > 0) group.addView(View(activity).apply {
                    setBackgroundColor(color("colorBackgroundModifierAccent", secondary))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(56) })
                renderChannel(data, channel, group)
            }
            content.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
        if (visibleGroups == 0) {
            label(if (!searchQuery.hasVisibleText()) "No channels available to browse." else "No channels match your search.",
                14f, muted, top = 24)
        }
    }
    private fun isChannelSelected(data: OnboardingConfig, channelId: Long): Boolean {
        if (isChannelHidden(channelId)) return false
        val addedByOnboarding = channelId in data.defaultChannelIds || data.prompts.any { prompt ->
            prompt.options.any { it.id in selected && channelId in it.channelIds }
        }
        return addedByOnboarding || flags[channelId]?.and(OnboardingApi.OPTED_IN_FLAG) != 0
    }

    private fun updateCategory(
        categoryId: Long,
        members: List<BrowseChannel>,
        enabled: Boolean,
        checkBox: CheckBox,
    ) {
        checkBox.isEnabled = false
        val ids = members.map(BrowseChannel::id).toMutableList()
        if (categoryId != 0L) ids += categoryId
        val oldFlags = ids.associateWith { flags[it] ?: 0 }
        val task = generation
        worker.execute {
            val result = runCatching {
                OnboardingApi(OnboardingApi.currentToken()).setChannelOptIns(guildId, oldFlags, enabled)
            }
            main.post {
                if (closed || generation != task) return@post
                result.onSuccess { updated ->
                    flags.putAll(updated)
                    personalized = true
                    onPersonalizedChanged(true)
                    onChannelsChanged(updated.keys.associateWith { enabled })
                    val y = scroll.scrollY
                    render()
                    scroll.post { scroll.scrollTo(0, y) }
                }.onFailure { error ->
                    val y = scroll.scrollY
                    render()
                    scroll.post { scroll.scrollTo(0, y) }
                    Utils.showToast(error.message ?: "Could not update category")
                }
            }
        }
    }

    private fun renderChannel(data: OnboardingConfig, channel: BrowseChannel, parent: LinearLayout) {
        val isDefault = channel.id in data.defaultChannelIds
        val assignedByPrompt = data.prompts.any { prompt ->
            prompt.options.any { it.id in selected && channel.id in it.channelIds }
        }
        val setting = createSetting(
            activity,
            CheckedSetting.ViewType.CHECK,
            channel.name,
            when {
                channel.topic.hasVisibleText() -> channel.topic
                channel.lastMessageId != null -> "Active " + DateUtils.getRelativeTimeSpanString(
                    (channel.lastMessageId shr 22) + 1420070400000L,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                )
                isDefault -> "Default channel"
                assignedByPrompt -> "Added by a customization answer"
                else -> ""
            },
        )
        val iconId = Utils.getResId(when (channel.type) {
            2, 13 -> "ic_channel_voice_16dp"
            5 -> "ic_channel_announcements"
            15 -> "ic_channel_forum_post"
            else -> "ic_channel_text_16dp"
        }, "drawable")
        if (iconId != 0) setting.findViewById<ImageView>(Utils.getResId("setting_drawable_left", "id"))?.apply {
            visibility = View.VISIBLE
            setImageResource(iconId)
            imageTintList = ColorStateList.valueOf(muted)
            layoutParams = layoutParams.apply { width = dp(24); height = dp(24) }
        }
        setting.findViewById<TextView>(Utils.getResId("setting_label", "id"))?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        setting.findViewById<TextView>(Utils.getResId("setting_subtext", "id"))?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        setting.isChecked = isChannelSelected(data, channel.id)
        var updating = false
        setting.setOnCheckedListener { enabled ->
            if (updating) return@setOnCheckedListener
            updating = true
            setting.isEnabled = false
            val oldFlags = flags[channel.id] ?: 0
            val task = generation
            worker.execute {
                val result = runCatching {
                    OnboardingApi(OnboardingApi.currentToken()).setChannelOptIn(guildId, channel.id, oldFlags, enabled)
                }
                main.post {
                    if (closed || generation != task) return@post
                    result.onSuccess { newFlags ->
                        flags[channel.id] = newFlags
                        updating = false
                        personalized = true
                        onPersonalizedChanged(true)
                        onChannelsChanged(mapOf(channel.id to enabled))
                        val y = scroll.scrollY
                        render()
                        scroll.post { scroll.scrollTo(0, y) }
                    }.onFailure { error ->
                        setting.isChecked = !enabled
                        setting.isEnabled = true
                        updating = false
                        Utils.showToast(error.message ?: "Could not update channel")
                    }
                }
            }
        }
        parent.addView(setting, LinearLayout.LayoutParams(-1, -2))
    }

    private fun save(isInitial: Boolean) {
        if (saving) return
        val data = config ?: return
        val missing = data.prompts.firstOrNull { prompt ->
            prompt.required && (!isInitial || prompt.inOnboarding) && prompt.options.none { it.id in selected }
        }
        if (missing != null) {
            Utils.showToast("Answer required question: ${missing.title}")
            return
        }
        if (data.prompts.any { prompt -> prompt.singleSelect && prompt.options.count { it.id in selected } > 1 }) {
            Utils.showToast("Choose one answer per single-choice question")
            return
        }
        if (!isInitial && selected == saved) {
            Utils.showToast("No changes to save")
            return
        }
        saving = true
        render()
        val choices = selected.toSet()
        val task = generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                api.saveResponses(data, choices, isInitial)
                runCatching { api.getConfig(guildId) }.getOrNull() ?: data.copy(responses = choices)
            }
            main.post {
                if (closed || generation != task) return@post
                saving = false
                result.onSuccess { updated ->
                    val current = updated.copy(responses = choices)
                    config = current
                    saved = choices
                    onConfigChanged(current)
                    if (isInitial) {
                        onInitialComplete()
                        Utils.showToast("You're all set in $guildName")
                        dismiss()
                    } else {
                        Utils.showToast("Channels & Roles updated")
                        render()
                    }
                }.onFailure { error ->
                    render()
                    showSaveError(error, data)
                }
            }
        }
    }

    private fun showSaveError(error: Throwable, data: OnboardingConfig) {
        val attempts = error.suppressed.filterIsInstance<OnboardingHttpError>() +
            listOfNotNull(error as? OnboardingHttpError)
        val details = if (attempts.isEmpty()) {
            error.message ?: "Discord did not save your choices."
        } else attempts.joinToString("\n\n") { attempt ->
            val code = if (attempt.code != 0) ", code ${attempt.code}" else ""
            val reason = attempt.discordMessage.takeIf(String::hasVisibleText) ?: "No reason returned"
            "${attempt.method}: HTTP ${attempt.status}$code — $reason"
        }
        val state = "Discord reports onboarding enabled: ${data.enabled}" +
            if (data.belowRequirements) "\nServer is below onboarding requirements." else ""
        val error = ConfirmDialog()
        error
            .setTitle("Could not save onboarding choices")
            .setDescription("$details\n\n$state")
            .setOnOkListener { error.dismiss() }
            .show(Utils.appActivity.supportFragmentManager, "OnboardingSaveError")
    }

    private fun renderError(error: Throwable) {
        content.removeAllViews()
        footer.visibility = View.VISIBLE
        label(error.message ?: "Could not load Channels & Roles", 15f, normal)
        footer.removeAllViews()
        footer.addView(actionButton("Retry", true) {
            loading = true
            render()
            load()
        }, LinearLayout.LayoutParams(-1, dp(44)))
    }

    private fun loadChannels() {
        browseError = null
        val task = generation
        worker.execute {
            val result = runCatching {
                val api = OnboardingApi(OnboardingApi.currentToken())
                api.getChannels(guildId) to api
            }
            main.post {
                if (closed || generation != task) return@post
                result.onSuccess { (loaded, api) ->
                    channels = loaded
                    channels.forEach { flags[it.id] = api.getChannelFlags(guildId, it.id) }
                    render()
                }.onFailure {
                    browseError = it.message ?: "Could not load channels"
                    render()
                }
            }
        }
    }

    private fun closeWithConfirmation() {
        if (!initial && selected != saved && !saving) {
            val confirm = ConfirmDialog()
            confirm
                .setTitle("Discard your changes?")
                .setDescription("Your onboarding choices haven't been saved.")
                .setIsDangerous(true)
                .setOnOkListener {
                    confirm.dismiss()
                    dismiss()
                }.show(Utils.appActivity.supportFragmentManager, "OnboardingDiscard")
        } else dismiss()
    }

    private fun label(text: String, size: Float, color: Int, top: Int = 8) {
        content.addView(styledText(if (size >= 18f) "UiKit_TextView_H2" else "UiKit_TextView").apply {
            this.text = text
            setTextColor(color)
            setPadding(0, dp(top), 0, dp(8))
        })
    }

    private fun actionButton(text: String, primaryAction: Boolean, onClick: () -> Unit): TextView {
        // Notice buttons require Discord's dialog theme, not the activity's button styles.
        val dialogTheme = TypedValue()
        val themeAttr = Utils.getResId("dialogTheme", "attr")
        val context = if (themeAttr != 0 &&
            activity.theme.resolveAttribute(themeAttr, dialogTheme, true) && dialogTheme.resourceId != 0
        ) ContextThemeWrapper(activity, dialogTheme.resourceId) else activity
        val buttonStyle = Utils.getResId(
            if (primaryAction) "buttonBarPositiveButtonStyle" else "buttonBarNegativeButtonStyle", "attr",
        )
        return MaterialButton(context, null, buttonStyle).apply {
            this.text = text
            isEnabled = !saving
            setOnClickListener { onClick() }
        }
    }

    private fun styledText(style: String) = TextView(activity, null, 0, Utils.getResId(style, "style"))

    private fun compoundTint() = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(color("colorInteractiveMuted", muted), brand, color("colorInteractiveNormal", muted)),
    )

    private fun createSetting(context: Context, type: CheckedSetting.ViewType, text: String, subtext: String): CheckedSetting =
        Utils.createCheckedSetting(context, type, text, subtext).apply {
            // Utils creates the native layout; apply the text appearance used by 126.21 settings.
            val appearance = Utils.getResId("UiKit_TextAppearance", "style")
            findViewById<TextView>(Utils.getResId("setting_label", "id"))?.apply {
                if (appearance != 0) setTextAppearance(context, appearance)
                val size = Utils.getResId("uikit_textsize_large", "dimen")
                if (size != 0) setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
                setTextColor(normal)
            }
            findViewById<TextView>(Utils.getResId("setting_subtext", "id"))?.apply {
                if (appearance != 0) setTextAppearance(context, appearance)
                setTextColor(muted)
            }
            if (type == CheckedSetting.ViewType.CHECK) {
                findViewById<CompoundButton>(Utils.getResId("setting_button", "id"))?.buttonTintList = compoundTint()
            }
        }

    private fun color(name: String, fallback: Int): Int {
        val id = Utils.getResId(name, "attr")
        return if (id == 0) fallback else ColorCompat.getThemedColor(activity, id)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
