package com.github.yutaplug.serverdiscovery

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rest.RestAPI
import com.discord.views.SearchInputView
import com.discord.views.GuildView
import com.discord.views.directories.ServerDiscoveryHeader
import com.discord.views.guilds.ServerMemberCount
import com.facebook.drawee.view.SimpleDraweeView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** A native client-side view over Discord's discoverable-guilds API. */
internal class DiscoveryScreen(
    private val context: Context,
    private val onClosed: () -> Unit,
) {
    private data class Category(val id: Int?, val name: String)

    private data class Guild(
        val id: Long,
        val name: String,
        val description: String?,
        val icon: String?,
        val splash: String?,
        val verified: Boolean,
        val members: Int,
        val online: Int,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(context)
    private val adapter = GuildAdapter()
    private val primary = color("colorBackgroundPrimary", 0xFF313338.toInt())
    private val secondary = color("colorBackgroundSecondary", 0xFF2B2D31.toInt())
    private val normal = color("colorTextNormal", 0xFFF2F3F5.toInt())
    private val muted = color("colorTextMuted", 0xFFB5BAC1.toInt())
    private val brand = color("color_brand", 0xFF5865F2.toInt())
    private val categories = mutableListOf(Category(null, "Home"))
    private var selectedCategory: Int? = null
    private var query = ""
    private var offset = 0
    private var total = Int.MAX_VALUE
    private var loading = false
    private var generation = 0
    private var closed = false
    private var searchTask: Runnable? = null
    private var searchMode = false
    private lateinit var toolbar: Toolbar
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var searchInput: SearchInputView
    private lateinit var categoryStrip: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var results: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(createContent())
        dialog.setOnDismissListener {
            closed = true
            generation++
            searchTask?.let(main::removeCallbacks)
            worker.shutdownNow()
            results.adapter = null
            content.removeAllViews()
            onClosed()
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (searchMode && keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                closeSearch()
                true
            } else false
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(primary))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = primary
            navigationBarColor = primary
        }
        loadPage()
        loadCategories()
    }

    fun dismiss() = dialog.dismiss()

    private fun createContent(): View {
        content = FrameLayout(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }
        toolbar = Toolbar(context).apply {
            title = "Discover"
            setTitleTextAppearance(context, Utils.getResId("UiKit_TextAppearance_Toolbar_Title", "style"))
            setTitleTextColor(normal)
            val back = Utils.getResId("ic_arrow_back_white_24dp", "drawable")
            if (back != 0) setNavigationIcon(back)
            navigationIcon?.setTint(normal)
            navigationContentDescription = "Back"
            setNavigationOnClickListener { if (searchMode) closeSearch() else dismiss() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        categoryStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        categoryScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryStrip)
        }
        root.addView(categoryScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        renderCategories()

        searchInput = SearchInputView(context, null).apply {
            setHint("Search for communities")
            visibility = View.GONE
            val edit = editText as EditText
            edit.imeOptions = EditorInfo.IME_ACTION_SEARCH
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable?) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    b(s?.toString().orEmpty())
                    if (!searchMode) return
                    searchTask?.let(main::removeCallbacks)
                    val nextQuery = trimWhitespace(s?.toString().orEmpty())
                    searchTask = Runnable {
                        if (!closed && searchMode && nextQuery != query) {
                            query = nextQuery
                            resetResults()
                        }
                    }.also { main.postDelayed(it, 350) }
                }
            })
            edit.setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_SEARCH) false else {
                    searchTask?.let(main::removeCallbacks)
                    query = trimWhitespace(edit.text.toString())
                    resetResults()
                    hideKeyboard()
                    true
                }
            }
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            setMargins(dp(16), dp(8), dp(16), dp(16))
        })

        status = styledText("UiKit_TextView_Medium").apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(muted)
            visibility = View.GONE
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        root.addView(status)

        results = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@DiscoveryScreen.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as LinearLayoutManager
                    if (dy > 0 && manager.findLastVisibleItemPosition() >= this@DiscoveryScreen.adapter.itemCount - 4) loadPage()
                }
            })
        }
        root.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(context).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return content
    }

    private fun renderCategories() {
        categoryStrip.removeAllViews()
        categories.forEach { category ->
            val selected = category.id == selectedCategory
            categoryStrip.addView(styledText("UiKit_TextView_Medium").apply {
                text = category.name
                gravity = Gravity.CENTER
                setTextColor(if (selected) normal else muted)
                background = rounded(if (selected) color("colorBackgroundModifierSelected", primary) else secondary, 18)
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                setOnClickListener {
                    if (selectedCategory != category.id) {
                        selectedCategory = category.id
                        renderCategories()
                        resetResults()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun loadCategories() {
        worker.execute {
            val loaded = runCatching {
                val language = Uri.encode(Locale.getDefault().toLanguageTag())
                val response = getJson("/discovery/categories?primary_only=true&locale=$language")
                val array = when (response) {
                    is JSONArray -> response
                    is JSONObject -> response.optJSONArray("categories") ?: JSONArray()
                    else -> JSONArray()
                }
                mutableListOf<Category>().apply {
                    add(Category(null, "Home"))
                    var i = 0
                    while (i < array.length()) {
                        val item = array.optJSONObject(i++) ?: continue
                        val id = item.optInt("id", -1).takeIf { it >= 0 } ?: continue
                        val nameValue = item.opt("name")
                        val name = when (nameValue) {
                            is JSONObject -> nameValue.optString("default")
                            is String -> nameValue
                            else -> ""
                        }
                        if (hasText(name)) add(Category(id, name))
                    }
                }
            }.getOrNull() ?: return@execute
            main.post {
                if (!closed) {
                    categories.clear()
                    val order = listOf("Gaming", "Music", "Entertainment", "Education", "Science & Tech")
                    categories.addAll(loaded.sortedBy { category ->
                        if (category.id == null) -1 else order.indexOf(category.name).takeIf { it >= 0 } ?: order.size
                    })
                    renderCategories()
                }
            }
        }
    }

    private fun resetResults() {
        generation++
        offset = 0
        total = Int.MAX_VALUE
        loading = false
        adapter.clear()
        status.visibility = View.GONE
        results.scrollToPosition(0)
        if (searchMode && !hasText(query)) {
            progress.visibility = View.GONE
            status.text = "PROTIP: You can search for a server by name, category, or keyword. Try any shared interest or hobby, no matter how obscure!"
            status.setPadding(dp(32), dp(32), dp(32), dp(20))
            status.setOnClickListener(null)
            status.visibility = View.VISIBLE
        } else {
            status.setPadding(dp(24), dp(20), dp(24), dp(20))
            loadPage()
        }
    }

    private fun loadPage() {
        if (closed || loading || offset >= total || (searchMode && !hasText(query))) return
        loading = true
        progress.visibility = View.VISIBLE
        status.visibility = View.GONE
        val requestGeneration = generation
        val requestOffset = offset
        val requestQuery = query
        val requestCategory = if (searchMode) null else selectedCategory
        worker.execute {
            val result = runCatching {
                val route = if (!hasText(requestQuery)) {
                    "/discoverable-guilds?limit=$PAGE_SIZE&offset=$requestOffset" +
                        (requestCategory?.let { "&categories=$it" } ?: "")
                } else {
                    "/discoverable-guilds/search?query=${Uri.encode(requestQuery.take(100))}" +
                        "&limit=$PAGE_SIZE&offset=$requestOffset" +
                        (requestCategory?.let { "&category_id=$it" } ?: "")
                }
                val body = getJson(route) as JSONObject
                val array = body.optJSONArray("guilds") ?: JSONArray()
                val guilds = mutableListOf<Guild>().apply {
                    var i = 0
                    while (i < array.length()) {
                        val item = array.optJSONObject(i++) ?: continue
                        val id = item.optString("id").toLongOrNull() ?: continue
                        val name = item.optString("name").takeIf(::hasText) ?: continue
                        add(Guild(
                            id,
                            name,
                            item.optString("description").takeIf { hasText(it) && it != "null" },
                            item.optString("icon").takeIf { hasText(it) && it != "null" },
                            item.optString("discovery_splash").takeIf { hasText(it) && it != "null" },
                            item.optJSONArray("features")?.let { features ->
                                var index = 0
                                var verified = false
                                while (index < features.length()) {
                                    if (features.optString(index++) == "VERIFIED") {
                                        verified = true
                                        break
                                    }
                                }
                                verified
                            } ?: false,
                            item.optInt("approximate_member_count"),
                            item.optInt("approximate_presence_count"),
                        ))
                    }
                }
                guilds to body.optInt("total", -1)
            }
            main.post {
                if (closed || requestGeneration != generation) return@post
                loading = false
                progress.visibility = View.GONE
                result.onSuccess { (guilds, count) ->
                    adapter.append(guilds)
                    offset = requestOffset + guilds.size
                    total = if (count >= 0) count else if (guilds.size < PAGE_SIZE) offset else Int.MAX_VALUE
                    if (guilds.isEmpty() && adapter.guildCount == 0) showStatus("No servers found")
                    else if (offset < total && guilds.isNotEmpty()) {
                        results.post { if (!closed && !results.canScrollVertically(1)) loadPage() }
                    }
                }.onFailure { error ->
                    showStatus("Could not load servers. Tap to retry.\n${error.message.orEmpty().take(120)}")
                }
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        status.setOnClickListener { loadPage() }
    }

    private fun getJson(route: String): Any {
        val token = StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
            ?.takeIf(::hasText)
            ?: RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)
            ?: error("Sign in to Discord to browse servers")
        return Http.Request.newDiscordRequest(route).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", token)
            request.execute().use { response ->
                if (!response.ok()) error("HTTP ${response.statusCode}")
                val text = response.text()
                var index = 0
                while (index < text.length && Character.isWhitespace(text[index])) index++
                if (index < text.length && text[index] == '[') JSONArray(text) else JSONObject(text)
            }
        }
    }

    private fun openSearch() {
        if (searchMode || closed) return
        searchMode = true
        toolbar.title = "Search for communities"
        categoryScroll.visibility = View.GONE
        searchInput.visibility = View.VISIBLE
        query = ""
        searchInput.setText("")
        resetResults()
        searchInput.editText.requestFocus()
        searchInput.post {
            if (!closed && searchMode) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(searchInput.editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun closeSearch() {
        searchTask?.let(main::removeCallbacks)
        hideKeyboard()
        searchMode = false
        query = ""
        searchInput.clearFocus()
        searchInput.visibility = View.GONE
        categoryScroll.visibility = View.VISIBLE
        toolbar.title = "Discover"
        resetResults()
    }

    private fun openServer(guildId: Long) {
        // Lurking can open Discord's welcome sheet even when it has no channels to show.
        StoreStream.getGuildWelcomeScreens().markWelcomeScreenShown(guildId)
        dismiss()
        StoreStream.getLurking().startLurkingAndNavigate(guildId, null, context)
    }

    private fun color(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id != 0) ColorCompat.getThemedColor(context, id) else fallback
    }

    private fun hasText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
        }
        return false
    }

    private fun trimWhitespace(value: String): String {
        var first = 0
        var last = value.length
        while (first < last && Character.isWhitespace(value[first])) first++
        while (last > first && Character.isWhitespace(value[last - 1])) last--
        return value.substring(first, last)
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun styledText(style: String) = TextView(
        context, null, 0, Utils.getResId(style, "style"),
    )

    private fun createHero(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val hero = ServerDiscoveryHeader(context, null).apply {
            setTitle("Find your community on Discord")
            setDescription("From gaming, to music, to learning, there's a place for you.")
        }
        hero.findViewById<TextView>(Utils.getResId("server_discovery_header_title", "id"))?.apply {
            gravity = Gravity.CENTER
            layoutParams = (layoutParams as ConstraintLayout.LayoutParams).apply { width = 0 }
        }
        val searchLayout = hero.findViewById<FrameLayout>(Utils.getResId("server_discovery_header_search_layout", "id"))
        searchLayout?.let { surface ->
            var index = 0
            while (index < surface.childCount) {
                val child = surface.getChildAt(index++)
                if (child is TextView) child.text = "Explore servers"
                child.isFocusable = false
                child.isClickable = false
            }
            surface.contentDescription = "Search for communities"
            surface.isFocusable = true
            hero.setButtonOnClickListener { openSearch() }
        }
        header.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))
        header.addView(styledText("UiKit_TextView_H1").apply {
            text = "Featured Servers"
            setTextColor(normal)
            setPadding(dp(16), dp(20), dp(16), dp(12))
            tag = "discovery_section_title"
        })
        return header
    }

    private inner class GuildAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<Guild>()
        val guildCount get() = items.size
        private val headerCount get() = if (searchMode) 0 else 1

        inner class Holder(val root: LinearLayout, val banner: SimpleDraweeView, val icon: GuildView,
                           val name: TextView, val description: TextView, val stats: ServerMemberCount) : RecyclerView.ViewHolder(root)

        override fun getItemViewType(position: Int) = if (headerCount == 1 && position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) return object : RecyclerView.ViewHolder(createHero()) {}
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = RippleDrawable(ColorStateList.valueOf(rippleColor(context)), rounded(secondary, 12), null)
                clipToOutline = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(16), 0, dp(16), dp(16))
                }
            }
            val artwork = FrameLayout(context)
            val banner = SimpleDraweeView(context).apply { setBackgroundColor(brand) }
            artwork.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
            val icon = GuildView(context, null).apply { b() }
            val iconFrame = FrameLayout(context).apply {
                background = rounded(secondary, 18)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(icon, FrameLayout.LayoutParams(dp(56), dp(56)))
            }
            artwork.addView(iconFrame, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(12)
            })
            root.addView(artwork, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(144)))
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val name = styledText("UiKit_TextView_H2").apply {
                setTextColor(normal)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                compoundDrawablePadding = dp(8)
            }
            text.addView(name)
            val description = styledText("UiKit_TextView_Medium").apply {
                setTextColor(normal)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            }
            text.addView(description)
            val stats = ServerMemberCount(context, null).apply { setPadding(0, dp(16), 0, 0) }
            text.addView(stats)
            root.addView(text)
            return Holder(root, banner, icon, name, description, stats)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (headerCount == 1 && position == 0) {
                holder.itemView.findViewWithTag<TextView>("discovery_section_title")?.text = when {
                    hasText(query) -> "Search Results"
                    selectedCategory != null -> categories.firstOrNull { it.id == selectedCategory }?.name ?: "Servers"
                    else -> "Featured Servers"
                }
                return
            }
            holder as Holder
            val guild = items[position - headerCount]
            holder.name.text = guild.name
            val badge = if (guild.verified) Utils.getResId("ic_verified_badge", "drawable") else 0
            holder.name.setCompoundDrawablesRelativeWithIntrinsicBounds(badge, 0, 0, 0)
            holder.description.text = guild.description.orEmpty()
            holder.description.visibility = if (guild.description == null) View.GONE else View.VISIBLE
            val acronym = guild.name.split(' ').filter { it.isNotEmpty() }.take(3).joinToString("") { it.take(1) }
            holder.icon.a(guild.icon?.let { "https://cdn.discordapp.com/icons/${guild.id}/$it.png?size=128" }, acronym)
            holder.banner.setImageURI(guild.splash?.let { "https://cdn.discordapp.com/discovery-splashes/${guild.id}/$it.png?size=1024" })
            holder.stats.setMembers(guild.members)
            holder.stats.setOnline(guild.online)
            holder.root.setOnClickListener { openServer(guild.id) }
            holder.root.contentDescription = "${guild.name}, ${guild.online} online, ${guild.members} members"
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is Holder) {
                holder.banner.setImageURI(null as String?)
                holder.icon.a(null, "")
                holder.root.setOnClickListener(null)
            }
            super.onViewRecycled(holder)
        }

        override fun getItemCount() = items.size + headerCount

        fun clear() {
            items.clear()
            notifyDataSetChanged()
        }

        fun append(incoming: List<Guild>) {
            val start = items.size + headerCount
            items.addAll(incoming)
            if (incoming.isNotEmpty()) notifyItemRangeInserted(start, incoming.size)
        }
    }

    private companion object {
        const val PAGE_SIZE = 24
    }
}
