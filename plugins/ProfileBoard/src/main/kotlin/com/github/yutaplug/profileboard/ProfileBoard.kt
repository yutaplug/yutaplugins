package com.github.yutaplug.profileboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.app.AppBottomSheet
import com.discord.utilities.images.MGImages
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import java.lang.ref.WeakReference
import java.util.IdentityHashMap

@AliucordPlugin
class ProfileBoard : Plugin() {
    private companion object {
        const val TAB_MARKER = "ProfileBoard:tabs"
        const val CONTENT_MARKER = "ProfileBoard:content"
        const val WISHLIST_CONTENT_MARKER = "ProfileBoard:wishlist"
    }

    private class Binding(
        val root: View,
        val content: LinearLayout,
        val header: View,
        val headerElevation: Float,
        val editActions: View,
        val editActionsElevation: Float,
        val actionsDivider: View,
        var actionsDividerVisibility: Int,
        val actions: View,
        val actionsElevation: Float,
        val tabs: TabLayout,
        val boardContent: LinearLayout,
        val wishlistContent: LinearLayout,
        val originals: List<View>,
    ) {
        var userId = 0L
        var generation = 0
        var selected = 0
        var data: BoardData? = null
        var syncingTabs = false
        val visibilities = IdentityHashMap<View, Int>()
    }

    private val main = Handler(Looper.getMainLooper())
    private val bindings = IdentityHashMap<WidgetUserSheet, Binding>()
    private var repository: BoardRepository? = null

    override fun start(context: Context) {
        repository = BoardRepository()
        GameProfileSheet.pluginResources = resources
        patcher.patch(
            WidgetUserSheet::class.java,
            "configureUI",
            arrayOf(WidgetUserSheetViewModel.ViewState::class.java),
            PreHook { call ->
                val sheet = call.thisObject as WidgetUserSheet
                bindings[sheet]?.let { restore(it) }
            },
        )
        patcher.patch(
            WidgetUserSheet::class.java,
            "configureUI",
            arrayOf(WidgetUserSheetViewModel.ViewState::class.java),
            Hook { call ->
                val sheet = call.thisObject as WidgetUserSheet
                val state = call.args[0] as? WidgetUserSheetViewModel.ViewState.Loaded ?: return@Hook
                val root = sheet.view ?: return@Hook
                var binding = bindings[sheet]
                val existing = binding != null && binding.root === root
                if (binding == null || binding.root !== root) {
                    binding?.let(::remove)
                    binding = install(root) ?: return@Hook
                    bindings[sheet] = binding
                }
                val current = binding
                current.header.elevation = 0f
                current.editActions.elevation = 0f
                if (existing) current.actionsDividerVisibility = current.actionsDivider.visibility
                current.actionsDivider.visibility = View.GONE
                current.actions.elevation = 0f
                root.post {
                    if (bindings[sheet] === current && sheet.view === root) {
                        removeOldRows(current.content, current.tabs, current.boardContent, current.wishlistContent)
                        select(current, current.selected)
                    }
                }
                if (binding.userId != state.user.id) {
                    binding.userId = state.user.id
                    binding.generation++
                    binding.data = null
                    binding.selected = 0
                    binding.tabs.visibility = View.GONE
                    setTabs(binding, hasBoard = false, hasWishlist = false)
                    renderStatus(binding, "Loading Board…")
                    select(binding, 0)
                    load(sheet, binding)
                } else if (binding.selected != 0) {
                    select(binding, binding.selected)
                }
            },
        )
        patcher.patch(
            AppBottomSheet::class.java,
            "onDestroyView",
            emptyArray(),
            PreHook { call ->
                val sheet = call.thisObject as? WidgetUserSheet ?: return@PreHook
                bindings.remove(sheet)?.let(::remove)
            },
        )
    }

    private fun install(root: View): Binding? {
        val content = root.findViewById<LinearLayout>(Utils.getResId("user_sheet_content", "id")) ?: return null
        val header = root.findViewById<View>(Utils.getResId("user_sheet_profile_header_view", "id"))?.parent as? View ?: return null
        val editActions = root.findViewById<View>(Utils.getResId("user_sheet_profile_edit_container", "id")) ?: return null
        val actionsDivider = root.findViewById<View>(Utils.getResId("user_sheet_profile_actions_divider", "id")) ?: return null
        val actions = root.findViewById<View>(Utils.getResId("user_sheet_profile_actions_container", "id")) ?: return null
        removeOldRows(content)
        val index = content.indexOfChild(actions)
        if (index < 0) return null
        val context = root.context
        val tabs = nativeTabs(context).apply {
            tag = TAB_MARKER
            visibility = View.GONE
        }
        content.addView(tabs, index + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val originals = ArrayList<View>()
        var originalIndex = index + 2
        while (originalIndex < content.childCount) {
            originals.add(content.getChildAt(originalIndex))
            originalIndex++
        }
        val boardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = CONTENT_MARKER
        }
        content.addView(boardContent, index + 2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val wishlistContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = WISHLIST_CONTENT_MARKER
        }
        content.addView(wishlistContent, index + 3, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val binding = Binding(root, content, header, header.elevation, editActions, editActions.elevation,
            actionsDivider, actionsDivider.visibility,
            actions, actions.elevation, tabs, boardContent, wishlistContent, originals)
        header.elevation = 0f
        editActions.elevation = 0f
        actionsDivider.visibility = View.GONE
        actions.elevation = 0f
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!binding.syncingTabs) select(binding, tab.tag as Int)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        setTabs(binding, hasBoard = false, hasWishlist = false)
        select(binding, 0)
        return binding
    }

    private fun removeOldRows(content: LinearLayout, keepTabs: View? = null, keepBody: View? = null, keepWishlist: View? = null) {
        val children = ArrayList<View>(content.childCount)
        var index = 0
        while (index < content.childCount) {
            children.add(content.getChildAt(index))
            index++
        }
        var position = 0
        while (position < children.size) {
            val child = children[position]
            if (child === keepTabs || child === keepBody || child === keepWishlist) {
                position++
                continue
            }
            val legacyTabs = child is LinearLayout && child.orientation == LinearLayout.HORIZONTAL &&
                child.childCount == 2 &&
                (child.getChildAt(0) as? TextView)?.text?.toString() == "Main" &&
                (child.getChildAt(1) as? TextView)?.text?.toString() == "Board"
            if (child.tag == TAB_MARKER || legacyTabs) {
                content.removeView(child)
                if (legacyTabs) {
                    val next = children.getOrNull(position + 1)
                    if (next is LinearLayout && next.id == View.NO_ID && next.orientation == LinearLayout.VERTICAL && next !== keepBody) {
                        content.removeView(next)
                    }
                }
            } else if (child.tag == CONTENT_MARKER || child.tag == WISHLIST_CONTENT_MARKER) {
                content.removeView(child)
            }
            position++
        }
    }

    private fun load(sheet: WidgetUserSheet, binding: Binding) {
        val repository = repository ?: return
        val generation = binding.generation
        val weakSheet = WeakReference(sheet)
        repository.load(binding.userId) { result ->
            main.post {
                val target = weakSheet.get() ?: return@post
                if (this.repository !== repository || bindings[target] !== binding ||
                    binding.generation != generation || target.view !== binding.root) return@post
                result.onSuccess { data ->
                    binding.data = data
                    updateTabs(binding, data)
                    if (data.widgets.isEmpty()) renderStatus(binding, "No widgets on this Board.")
                    else render(binding, data)
                    renderWishlist(binding, data.wishlist)
                    select(binding, binding.selected)
                }.onFailure { error ->
                    logger.error("Could not load profile board", error)
                    renderStatus(binding, "Could not load Board. Tap to retry.") {
                        renderStatus(binding, "Loading Board…")
                        load(target, binding)
                    }
                }
            }
        }
    }

    private fun updateTabs(binding: Binding, data: BoardData) {
        val hasBoard = data.widgets.isNotEmpty()
        val hasWishlist = data.wishlist?.let { it.failed || it.items.isNotEmpty() } == true
        binding.tabs.visibility = if (hasBoard || hasWishlist) View.VISIBLE else View.GONE
        if ((binding.selected == 1 && !hasBoard) || (binding.selected == 2 && !hasWishlist)) binding.selected = 0
        setTabs(binding, hasBoard, hasWishlist)
    }

    // TabLayout can't hide individual tabs, so rebuild the row with only the sections that have content.
    private fun setTabs(binding: Binding, hasBoard: Boolean, hasWishlist: Boolean) {
        val tabs = binding.tabs
        binding.syncingTabs = true
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("Main").setTag(0), false)
        if (hasBoard) tabs.addTab(tabs.newTab().setText("Board").setTag(1), false)
        if (hasWishlist) tabs.addTab(tabs.newTab().setText("Wishlist").setTag(2), false)
        binding.syncingTabs = false
    }

    private fun select(binding: Binding, section: Int) {
        binding.selected = section
        if (section != 0) {
            var childIndex = binding.content.indexOfChild(binding.tabs) + 1
            while (childIndex < binding.content.childCount) {
                val view = binding.content.getChildAt(childIndex++)
                if (view !== binding.boardContent && view !== binding.wishlistContent &&
                    !binding.visibilities.containsKey(view)) {
                    binding.visibilities[view] = view.visibility
                    view.visibility = View.GONE
                }
            }
            binding.boardContent.visibility = if (section == 1) View.VISIBLE else View.GONE
            binding.wishlistContent.visibility = if (section == 2) View.VISIBLE else View.GONE
        } else {
            restore(binding)
            binding.boardContent.visibility = View.GONE
            binding.wishlistContent.visibility = View.GONE
        }
        val tabs = binding.tabs
        var index = 0
        while (index < tabs.tabCount) {
            val tab = tabs.getTabAt(index)
            if (tab?.tag == section) {
                if (!tab.isSelected) {
                    binding.syncingTabs = true
                    tab.select()
                    binding.syncingTabs = false
                }
                break
            }
            index++
        }
    }

    private fun restore(binding: Binding) {
        for ((view, visibility) in binding.visibilities) view.visibility = visibility
        binding.visibilities.clear()
    }

    private fun renderStatus(binding: Binding, message: String, retry: (() -> Unit)? = null) {
        val host = binding.boardContent
        host.removeAllViews()
        val context = host.context
        val card = section(context, null, null)
        card.second.addView(styledText(context, "UiKit_TextView").apply {
            text = message
            if (retry != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { retry() }
            }
        })
        host.addView(card.first, sectionParams(context).apply { topMargin = dp(context, 16) })
    }

    private fun render(binding: Binding, data: BoardData) {
        val host = binding.boardContent
        host.removeAllViews()
        for (widget in data.widgets) {
            val application = widget.applicationId?.let { data.applications[it] }
            val title = when (widget.type) {
                "favorite_games" -> "Favorite Game"
                "played_games" -> "Games I Like"
                "current_games" -> "Games in Rotation"
                "want_to_play_games" -> "Want to Play"
                else -> application?.name ?: "Game Stats"
            }
            val context = host.context
            val card = section(context, title, application?.icon)
            host.addView(card.first, sectionParams(context).apply { topMargin = dp(context, 16) })
            val body = card.second
            when (widget.type) {
                "favorite_games", "current_games" -> widget.games.forEach { game ->
                    body.addView(detailedGame(body.context, game, data.games[game.id]))
                }
                "played_games", "want_to_play_games" -> grid(body, widget.games, data.games)
                "application" -> if (application != null) renderApplication(body, application)
            }
        }
    }

    private fun renderWishlist(binding: Binding, wishlist: WishlistData?) {
        val host = binding.wishlistContent
        host.removeAllViews()
        if (wishlist == null) return
        val context = host.context
        val heading = styledText(context, "UserProfile_Section_HeaderTextAppearance").apply {
            text = if (wishlist.failed) "Wishlist" else "${wishlist.items.size} ${if (wishlist.items.size == 1) "Item" else "Items"}"
            isAllCaps = false
            setTextColor(themeColor(context, "colorHeaderSecondary"))
        }
        host.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 12))
        })
        if (wishlist.failed || wishlist.items.isEmpty()) {
            host.addView(styledText(context, "UiKit_TextView").apply {
                text = if (wishlist.failed) "Could not load Wishlist. Tap to retry." else "No items on this Wishlist."
                if (wishlist.failed) setOnClickListener {
                    val sheet = bindings.entries.firstOrNull { it.value === binding }?.key ?: return@setOnClickListener
                    load(sheet, binding)
                }
            }, sectionParams(context))
            return
        }
        val size = (context.resources.displayMetrics.widthPixels - dp(context, 48)) / 2
        var index = 0
        while (index < wishlist.items.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            var column = 0
            while (column < 2) {
                val item = if (index < wishlist.items.size) wishlist.items[index++] else null
                val tile = FrameLayout(context).apply {
                    background = if (item == null) null else GradientDrawable().apply {
                        cornerRadius = dp(context, 16).toFloat()
                        setColor(themeColor(context, "colorBackgroundTertiary"))
                    }
                    clipToOutline = true
                    contentDescription = item?.name
                }
                val fallback = if (item != null) {
                    styledText(context, "UserProfile_Section_HeaderTextAppearance").apply {
                        text = item.name
                        gravity = Gravity.CENTER
                        isAllCaps = false
                        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                    }.also {
                        tile.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                } else null
                if (item?.isNitro == true) {
                    NitroArtwork.bitmap()?.let { bitmap ->
                        fallback?.visibility = View.GONE
                        tile.addView(ImageView(context).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = item.name
                        }, FrameLayout.LayoutParams(size - dp(context, 16), (size * 0.78f).toInt(),
                            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(context, 8) })
                        val logo = Utils.getResId("img_logo_nitro", "drawable")
                        if (logo != 0) {
                            tile.addView(ImageView(context).apply {
                                setImageResource(logo)
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }, FrameLayout.LayoutParams((size * 0.62f).toInt(), (size * 0.16f).toInt(),
                                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(context, 20) })
                        }
                    }
                } else if (item != null && (item.image != null || item.layers.isNotEmpty())) {
                    tile.addView(WishlistPreview(context, item) {
                        fallback?.visibility = View.GONE
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                if (item?.owned == true) {
                    tile.addView(WishlistOwnedOverlay(context), FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    ))
                }
                row.addView(tile, LinearLayout.LayoutParams(0, size, 1f).apply {
                    if (column == 0) marginEnd = dp(context, 8)
                })
                column++
            }
            host.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 16)
                marginEnd = dp(context, 16)
                bottomMargin = dp(context, 8)
            })
        }
    }

    private fun section(context: Context, title: String?, icon: String?): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(context).apply {
            radius = dp(context, 12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(themeColor(context, "colorBackgroundPrimary"))
            strokeWidth = dp(context, 1)
            strokeColor = themeColor(context, "colorBackgroundModifierAccent")
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        }
        if (title != null) {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (icon != null) {
                header.addView(SimpleDraweeView(context).apply {
                    setImageURI(icon)
                    MGImages.setRoundingParams(this, dp(context, 10).toFloat(), false, null, null, null)
                }, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                    marginEnd = dp(context, 8)
                })
            }
            header.addView(styledText(context, "UserProfile_Section_HeaderTextAppearance").apply {
                text = title
                isAllCaps = false
                setTextColor(themeColor(context, "colorHeaderPrimary"))
            })
            body.addView(header)
            body.addView(View(context).apply {
                setBackgroundColor(themeColor(context, "colorBackgroundModifierAccent"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
                topMargin = dp(context, 12)
                bottomMargin = dp(context, 12)
            })
        }
        card.addView(body)
        return card to body
    }

    private fun renderApplication(body: LinearLayout, widget: ApplicationWidget) {
        val context = body.context
        widget.image?.let { url ->
            body.addView(SimpleDraweeView(context).apply {
                setImageURI(url)
                contentDescription = widget.title ?: widget.name
                MGImages.setRoundingParams(this, dp(context, 8).toFloat(), false, null, null, null)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160)))
        }
        widget.title?.let { title ->
            body.addView(styledText(context, "UserProfile_Section_HeaderTextAppearance").apply {
                text = title
                isAllCaps = false
                setTextColor(themeColor(context, "colorHeaderPrimary"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 12)
            })
        }
        for (subtitle in widget.subtitles) {
            body.addView(styledText(context, "UiKit_TextView").apply {
                text = subtitle
                setTextColor(themeColor(context, "colorHeaderSecondary"))
            })
        }
        var index = 0
        while (index < widget.stats.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            var column = 0
            while (column < 2) {
                val stat = if (index < widget.stats.size) widget.stats[index++] else null
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                if (stat != null) {
                    cell.addView(styledText(context, "UserProfile_Section_HeaderTextAppearance").apply {
                        text = stat.value
                        isAllCaps = false
                        setTextColor(themeColor(context, "colorHeaderPrimary"))
                    })
                    cell.addView(styledText(context, "UiKit_TextView").apply {
                        text = stat.label
                        setTextColor(themeColor(context, "colorHeaderSecondary"))
                    })
                }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                column++
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(context, 12)
            })
        }
    }

    private fun detailedGame(context: Context, game: BoardGame, info: GameInfo?): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(cover(context, game, info), LinearLayout.LayoutParams(dp(context, 96), dp(context, 128)))
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), 0, 0, 0)
        }
        details.addView(styledText(context, "UiKit_TextView_SingleLine").apply {
            text = info?.name ?: UNKNOWN_GAME_NAME
        })
        game.comment?.let { comment ->
            details.addView(styledText(context, "UiKit_TextView").apply {
                text = "“$comment”"
                setTextColor(themeColor(context, "colorHeaderSecondary"))
            })
        }
        val chips = FlexboxLayout(context, null).apply { flexWrap = 1 }
        var tagIndex = 0
        while (tagIndex < game.tags.size && tagIndex < 3) {
            val tag = game.tags[tagIndex]
            chips.addView(tagChip(context, tag), tagParams(context))
            tagIndex++
        }
        if (game.tags.size > tagIndex) {
            chips.addView(tagChip(context, "+${game.tags.size - tagIndex}"), tagParams(context))
        }
        details.addView(chips)
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun tagChip(context: Context, tag: String): TextView =
        styledText(context, "UiKit_TextView").apply {
                val label = StringBuilder(tag.length)
                var index = 0
                var capitalize = true
                while (index < tag.length) {
                    val character = tag[index]
                    if (character == '_') {
                        label.append(' ')
                        capitalize = true
                    } else {
                        label.append(if (capitalize) Character.toUpperCase(character) else character)
                        capitalize = false
                    }
                    index++
                }
                text = label.toString()
                setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 5).toFloat()
                    setStroke(dp(context, 1), themeColor(context, "colorBackgroundModifierAccent"))
                }
        }

    private fun tagParams(context: Context) = FlexboxLayout.LayoutParams(
        ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(context, 5)
            topMargin = dp(context, 5)
        },
    )

    private fun grid(body: LinearLayout, games: List<BoardGame>, infos: Map<String, GameInfo>) {
        val context = body.context
        val width = context.resources.displayMetrics.widthPixels
        val height = ((width - dp(context, 96)) / 3f * 1.2f).toInt()
        var index = 0
        while (index < games.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            var columns = 0
            while (columns < 3 && index < games.size) {
                val game = games[index]
                row.addView(cover(context, game, infos[game.id]),LinearLayout.LayoutParams(0, height, 1f).apply {
                    marginEnd = dp(context, 8)
                })
                index++
                columns++
            }
            while (columns < 3) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                columns++
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 8)
            })
        }
    }

    private fun cover(context: Context, game: BoardGame, info: GameInfo?): View {
        val frame = FrameLayout(context).apply {
            setOnClickListener { GameProfileSheet.open(it, game.id, info?.name) }
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                foreground = context.getDrawable(ripple.resourceId)
            }
        }
        val image = SimpleDraweeView(context).apply {
            contentDescription = info?.name ?: UNKNOWN_GAME_NAME
            MGImages.setRoundingParams(this, dp(context, 8).toFloat(), false, null, null, null)
            info?.image?.let(::setImageURI)
        }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (info?.image == null) frame.addView(styledText(context, "UiKit_TextView").apply {
            text = info?.name ?: UNKNOWN_GAME_NAME
            gravity = Gravity.CENTER
            setBackgroundColor(themeColor(context, "colorBackgroundSecondary"))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    /**
     * Discord's own TabLayout, configured like 126.21's toolbar tabs (widget_friends_add, widget_thread_browser):
     * App_TabLayout_Text, tabSelectedTextColor/tabTextColor, fixed mode, fill gravity and no max tab width.
     */
    private fun nativeTabs(context: Context) = TabLayout(context).apply {
        setBackgroundColor(themeColor(context, "colorPrimaryTabs"))
        tabTextAppearance = Utils.getResId("App_TabLayout_Text", "style")
        tabMode = TabLayout.MODE_FIXED
        tabGravity = TabLayout.GRAVITY_FILL
        runCatching { ReflectUtils.setField(this, "requestedTabMaxWidth", 0) }
        val selected = themeColor(context, "tabSelectedTextColor")
        setTabTextColors(themeColor(context, "tabTextColor"), selected)
        setSelectedTabIndicatorColor(selected)
    }

    private fun styledText(context: Context, style: String): TextView =
        TextView(context, null, 0, Utils.getResId(style, "style"))

    private fun sectionParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(context, 16)
        marginEnd = dp(context, 16)
        bottomMargin = dp(context, 8)
    }

    private fun themeColor(context: Context, name: String): Int {
        val value = TypedValue()
        val id = Utils.getResId(name, "attr")
        return if (id != 0 && context.theme.resolveAttribute(id, value, true)) value.data else 0xFF202127.toInt()
    }

    private fun dp(context: Context, amount: Int) = (amount * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun remove(binding: Binding) {
        binding.generation++
        restore(binding)
        binding.header.elevation = binding.headerElevation
        binding.editActions.elevation = binding.editActionsElevation
        binding.actionsDivider.visibility = binding.actionsDividerVisibility
        binding.actions.elevation = binding.actionsElevation
        binding.content.removeView(binding.tabs)
        binding.content.removeView(binding.boardContent)
        binding.content.removeView(binding.wishlistContent)
        binding.boardContent.removeAllViews()
        binding.wishlistContent.removeAllViews()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        repository?.close()
        repository = null
        GameProfileSheet.pluginResources = null
        main.removeCallbacksAndMessages(null)
        for (binding in bindings.values.toList()) remove(binding)
        bindings.clear()
    }
}
