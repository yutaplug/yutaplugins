package com.github.yutaplug.markdownfix

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.widgets.BottomSheet
import com.discord.player.MediaType
import com.discord.stores.StoreStream
import com.discord.widgets.media.WidgetMedia
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.R
import java.text.NumberFormat
import kotlin.math.roundToInt

/** A backport of the newer client's game profile, opened from a game mention. */
class GameProfileSheet : BottomSheet() {
    private var preview: ResolvedGame? = null

    @Volatile private var visible = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val id = arguments?.getLong(ARG_ID) ?: 0L
        if (id <= 0L) {
            dismissAllowingStateLoss()
            return
        }
        visible = true
        val context = requireContext()
        linearLayout.setPadding(0, 0, 0, dp(context, 24))
        linearLayout.setBackgroundColor(DiscordSettingsUi.color(context, "colorBackgroundPrimary"))
        render(context, id, GameProfile(name = preview?.name), loading = true)
        Utils.threadPool.execute {
            val profile = try {
                GameProfile.load(id)
            } catch (error: Exception) {
                logger.error("Could not load game profile $id", error)
                null
            }
            Utils.mainThread.post {
                if (visible &&
                    isAdded
                ) {
                    render(requireContext(), id, profile ?: GameProfile(name = preview?.name), false)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The profile is a full page on newer clients; open it expanded instead of at peek height.
        view?.post {
            val container = dialog?.findViewById<View>(Utils.getResId("design_bottom_sheet", "id")) ?: return@post
            BottomSheetBehavior.from(container).apply {
                setSkipCollapsed(true)
                setState(BottomSheetBehavior.STATE_EXPANDED)
            }
        }
    }

    override fun onDestroyView() {
        visible = false
        super.onDestroyView()
    }

    private fun render(context: Context, id: Long, profile: GameProfile, loading: Boolean) {
        linearLayout.removeAllViews()
        addHero(context, id, profile)
        if (loading) {
            linearLayout.addView(muted(context, "Loading…"), margins(context, top = 16))
            return
        }
        if (profile.media.isNotEmpty() && profile.name != null) addMedia(context, profile)
        if (profile.name == null) {
            linearLayout.addView(muted(context, "This game's profile couldn't be loaded."), margins(context, top = 16))
            return
        }
        for (store in profile.stores) addStore(context, store)
        profile.reviews?.let { addReviews(context, it) }
        profile.summary?.let { addDescription(context, it) }
        addDetails(context, profile)
        if (profile.links.isNotEmpty()) {
            addSectionTitle(context, "Links")
            for ((label, url) in profile.links) {
                linearLayout.addView(
                    body(context, label).apply {
                        setTextColor(DiscordSettingsUi.color(context, "colorTextLink"))
                        minHeight = dp(context, 40)
                        gravity = Gravity.CENTER_VERTICAL
                        setOnClickListener { openUrl(context, url) }
                    },
                    margins(context),
                )
            }
        }
    }

    private fun addHero(context: Context, id: Long, profile: GameProfile) {
        val background = DiscordSettingsUi.color(context, "colorBackgroundPrimary")
        val hasArt = profile.background != null
        val hero = FrameLayout(context)
        if (hasArt) {
            hero.addView(
                SimpleDraweeView(context).apply { setImageURI(profile.background) },
                FrameLayout.LayoutParams(-1, -1),
            )
            // Fade the artwork into the sheet so the media row below can overlap it.
            hero.addView(
                View(context).apply {
                    this.background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.argb(90, 0, 0, 0), withAlpha(background, 170), background),
                    )
                },
                FrameLayout.LayoutParams(-1, -1),
            )
        }

        val row = LinearLayout(context).apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(context, 20), dp(context, if (hasArt) 72 else 24), dp(context, 20), dp(context, 16))
        }
        val coverUrl =
            profile.cover ?: profile.icon?.let { "https://cdn.discordapp.com/app-icons/$id/$it.png?size=256" }
        if (coverUrl != null) {
            row.addView(
                SimpleDraweeView(context).apply {
                    setBackgroundColor(DiscordSettingsUi.color(context, "colorBackgroundTertiary"))
                    rounded(this, dp(context, 8).toFloat())
                    setImageURI(coverUrl)
                },
                if (profile.cover != null) {
                    LinearLayout.LayoutParams(dp(context, 112), dp(context, 150))
                } else {
                    LinearLayout.LayoutParams(dp(context, 96), dp(context, 96))
                },
            )
        }
        val titleColor = if (hasArt) Color.WHITE else DiscordSettingsUi.color(context, "colorHeaderPrimary")
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        profile.rank
            ?.takeIf {
                it in 1..MAX_RANK
            }?.let { column.addView(rankPill(context, it), LinearLayout.LayoutParams(-2, -2)) }
        column.addView(
            TextView(context, null, 0, R.i.UiKit_TextView_H1_Bold).apply {
                text = profile.name ?: "Unknown game"
                textSize = 28f
                setTextColor(titleColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 6) },
        )
        if (profile.genres.isNotEmpty()) {
            column.addView(
                TextView(context, null, 0, R.i.UiKit_TextView).apply {
                    text = profile.genres.take(MAX_GENRES).joinToString(", ")
                    textSize = 16f
                    setTextColor(
                        if (hasArt) {
                            Color.argb(
                                230,
                                255,
                                255,
                                255,
                            )
                        } else {
                            DiscordSettingsUi.color(context, "colorTextMuted")
                        },
                    )
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 16) })
        hero.addView(row, FrameLayout.LayoutParams(-1, -2))
        linearLayout.addView(hero, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rankPill(context: Context, rank: Int) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 10), dp(context, 3), dp(context, 12), dp(context, 3))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(context, 100).toFloat()
        }
        pluginDrawable("game_rank_trophy")?.let { trophy ->
            addView(
                ImageView(context).apply { setImageDrawable(trophy) },
                LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)).apply { marginEnd = dp(context, 6) },
            )
        }
        addView(
            TextView(context, null, 0, R.i.UiKit_TextView_Bold).apply {
                text = "#$rank GLOBAL RANK"
                textSize = 13f
                setTextColor(Color.BLACK)
            },
        )
    }

    private fun addMedia(context: Context, profile: GameProfile) {
        val width = resources.displayMetrics.widthPixels - dp(context, 20 + 20 + 28)
        val height = width * 9 / 16
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            setPadding(dp(context, 20), 0, dp(context, 20), 0)
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = profile.media.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(FrameLayout(context)) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val media = profile.media[position]
                    val frame = holder.itemView as FrameLayout
                    frame.removeAllViews()
                    frame.layoutParams = RecyclerView.LayoutParams(width, height).apply {
                        marginEnd = if (position == profile.media.size - 1) 0 else dp(context, 10)
                    }
                    frame.setBackgroundColor(Color.BLACK)
                    rounded(frame, dp(context, 16).toFloat())
                    media.fallbackImage?.let { fallback ->
                        frame.addView(
                            SimpleDraweeView(context).apply { setImageURI(fallback) },
                            FrameLayout.LayoutParams(-1, -1),
                        )
                    }
                    frame.addView(
                        SimpleDraweeView(context).apply { setImageURI(media.image) },
                        FrameLayout.LayoutParams(-1, -1),
                    )
                    if (media.video !=
                        null
                    ) {
                        frame.addView(
                            playButton(context),
                            FrameLayout.LayoutParams(dp(context, 44), dp(context, 44), Gravity.CENTER),
                        )
                    }
                    frame.setOnClickListener { openMedia(context, profile.name.orEmpty(), media) }
                }
            }
        }
        PagerSnapHelper().attachToRecyclerView(recycler)
        linearLayout.addView(recycler, LinearLayout.LayoutParams(-1, height).apply { bottomMargin = dp(context, 20) })
    }

    private fun playButton(context: Context) = ImageView(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val padding = dp(context, 10)
        setPadding(padding, padding, padding, padding)
        ContextCompat.getDrawable(context, Utils.getResId("ic_play_arrow_24dp", "drawable"))?.mutate()?.let {
            it.setTint(Color.BLACK)
            setImageDrawable(it)
        }
    }

    private fun addStore(context: Context, store: GameStore) {
        val button = MaterialButton(ContextThemeWrapper(context, R.i.UiKit_Material_Button_Secondary), null, 0).apply {
            text = store.label
            isAllCaps = false
            textSize = 16f
            storeIcon(context, store.category)?.let { icon ->
                this.icon = icon
                iconTint = null
                iconSize = dp(context, 24)
                iconPadding = dp(context, 10)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            }
            setOnClickListener { openUrl(context, store.url) }
        }
        linearLayout.addView(button, margins(context, bottom = 8).apply { height = dp(context, 52) })
    }

    private fun storeIcon(context: Context, category: Int): Drawable? {
        val light = StoreStream.getUserSettingsSystem().theme == "light"
        val plugin = when (category) {
            16 -> "game_store_epicgames"
            21 -> "game_store_riotgames"
            22 -> "game_store_roblox"
            else -> null
        }
        if (plugin != null) return pluginDrawable(if (light) "${plugin}_light" else plugin)
        val native = when (category) {
            13 -> if (light) "ic_account_steam_light_24dp" else "ic_account_steam_white_24dp"
            20 -> "ic_account_battlenet_light_and_dark_24dp"
            else -> return null
        }
        return Utils.getResId(native, "drawable").takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }
    }

    private fun addReviews(context: Context, reviews: GameReviews) {
        addSectionTitle(context, "Reviews")
        val score = reviews.openCriticScore
        if (score != null) {
            val tier = reviews.openCriticTier?.takeIf { it in 1..4 }
            val card = reviewCard(context, "OpenCritic", reviews.openCriticUrl)
            if (tier != null) {
                card.addView(
                    SimpleDraweeView(
                        context,
                    ).apply { setImageURI("https://img.opencritic.com/mighty-man/${TIER_IMAGES[tier - 1]}.png") },
                    LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply { marginEnd = dp(context, 16) },
                )
            }
            card.addView(
                TextView(context, null, 0, R.i.UiKit_TextView_Bold).apply {
                    text = score.toString()
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(DiscordSettingsUi.color(context, "colorHeaderPrimary"))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(
                            dp(context, 3),
                            tier?.let { TIER_COLORS[it - 1] } ?: DiscordSettingsUi.color(context, "colorTextMuted"),
                        )
                    }
                },
                LinearLayout.LayoutParams(dp(context, 46), dp(context, 46)),
            )
            linearLayout.addView(card, margins(context, bottom = 8))
        }
        val rating = reviews.steamRating
        if (rating != null) {
            val card = reviewCard(context, "Steam", reviews.steamUrl)
            val count = reviews.steamCount ?: 0
            val (label, color) = steamLabel(rating, count)
            card.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(
                        TextView(context, null, 0, R.i.UiKit_TextView_Bold).apply {
                            text = label
                            textSize = 15f
                            setTextColor(color)
                        },
                    )
                    addView(
                        muted(
                            context,
                            "${(rating * 100).roundToInt()}% of ${NumberFormat.getIntegerInstance().format(
                                count,
                            )} reviews",
                        ).apply { setPadding(0, 0, 0, 0) },
                    )
                },
            )
            linearLayout.addView(card, margins(context, bottom = 8))
        }
    }

    private fun reviewCard(context: Context, title: String, url: String?) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 76)
        setPadding(dp(context, 20), dp(context, 12), dp(context, 16), dp(context, 12))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 16).toFloat()
            setStroke(dp(context, 1), DiscordSettingsUi.color(context, "colorBackgroundModifierAccent"))
        }
        addView(
            TextView(context, null, 0, R.i.UiKit_TextView_Semibold).apply {
                text = title
                textSize = 17f
                setTextColor(DiscordSettingsUi.color(context, "colorHeaderPrimary"))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (url != null) setOnClickListener { openUrl(context, url) }
    }

    private fun steamLabel(rating: Double, count: Int): Pair<String, Int> {
        val positive = Color.rgb(102, 192, 244)
        val mixed = Color.rgb(185, 160, 116)
        val negative = Color.rgb(163, 76, 37)
        return when {
            rating >= 0.95 && count >= 500 -> "Overwhelmingly Positive" to positive
            rating >= 0.8 && count >= 50 -> "Very Positive" to positive
            rating >= 0.8 -> "Positive" to positive
            rating >= 0.7 -> "Mostly Positive" to positive
            rating >= 0.4 -> "Mixed" to mixed
            rating >= 0.2 -> "Mostly Negative" to negative
            else -> "Negative" to negative
        }
    }

    private fun addDescription(context: Context, summary: String) {
        val text = body(context, summary).apply {
            textSize = 16f
            maxLines = COLLAPSED_LINES
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
        }
        linearLayout.addView(text, margins(context, top = 16))
        val toggle = body(context, "Show more").apply {
            setTextColor(DiscordSettingsUi.color(context, "colorTextLink"))
            textSize = 16f
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            setOnClickListener {
                val expanded = text.maxLines == Int.MAX_VALUE
                text.maxLines = if (expanded) COLLAPSED_LINES else Int.MAX_VALUE
                this.text = if (expanded) "Show more" else "Show less"
            }
        }
        linearLayout.addView(toggle, margins(context, bottom = 8))
        text.post {
            val layout = text.layout ?: return@post
            if (layout.lineCount > 0 &&
                layout.getEllipsisCount(layout.lineCount - 1) > 0
            ) {
                toggle.visibility = View.VISIBLE
            }
        }
    }

    private fun addDetails(context: Context, profile: GameProfile) {
        val details = listOf(
            "Released" to profile.releaseDate,
            (if (profile.developers.size > 1) "Developers" else "Developer") to profile.developers.joinToString(", "),
            (if (profile.publishers.size > 1) "Publishers" else "Publisher") to profile.publishers.joinToString(", "),
            "Platforms" to profile.platforms.joinToString(", "),
        ).filter { !it.second.isNullOrEmpty() }
        if (details.isEmpty()) return
        addSectionTitle(context, "Details")
        for ((label, value) in details) {
            linearLayout.addView(muted(context, label).apply { setPadding(0, 0, 0, 0) }, margins(context, top = 4))
            linearLayout.addView(body(context, value!!).apply { setPadding(0, 0, 0, 0) }, margins(context, bottom = 8))
        }
    }

    private fun addSectionTitle(context: Context, title: String) {
        linearLayout.addView(
            TextView(context, null, 0, R.i.UiKit_TextView_H2_Bold).apply {
                text = title
                textSize = 18f
                setTextColor(DiscordSettingsUi.color(context, "colorHeaderPrimary"))
            },
            margins(context, top = 24, bottom = 12),
        )
    }

    private fun margins(context: Context, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(context, 20), dp(context, top), dp(context, 20), dp(context, bottom))
    }

    private fun rounded(view: View, radius: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        view.clipToOutline = true
    }

    private fun body(context: Context, value: String) = DiscordSettingsUi.text(context).apply {
        text = value
        textSize = 15f
    }

    private fun muted(context: Context, value: String) = TextView(context, null, 0, R.i.UiKit_TextView).apply {
        text = value
        textSize = 13f
        setTextColor(DiscordSettingsUi.color(context, "colorTextMuted"))
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun openMedia(context: Context, title: String, media: GameMedia) {
        try {
            // Discord's own viewer; its public overloads need a message attachment or embed.
            val companion = WidgetMedia::class.java.getDeclaredField("Companion").get(null)!!
            companion.javaClass
                .getDeclaredMethod(
                    "launch",
                    Context::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaObjectType,
                    Int::class.javaObjectType,
                    MediaType::class.java,
                ).apply { isAccessible = true }
                .invoke(
                    companion,
                    context,
                    title,
                    media.video ?: media.image,
                    media.video,
                    if (media.video != null) media.fallbackImage ?: media.image else media.image,
                    media.width,
                    media.height,
                    if (media.video != null) MediaType.VIDEO else null,
                )
        } catch (error: Exception) {
            logger.error("Could not open game media in Discord's viewer", error)
            openUrl(context, media.video ?: media.image)
        }
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Utils.showToast("Couldn't open link")
        }
    }

    private fun pluginDrawable(name: String): Drawable? {
        val res = pluginResources ?: return null
        val id = res.getIdentifier(name, "drawable", "com.github.yutaplug.markdownfix")
        return if (id == 0) null else ResourcesCompat.getDrawable(res, id, null)
    }

    private fun dp(context: Context, value: Int) = DiscordSettingsUi.dp(context, value)

    companion object {
        private const val ARG_ID = "game_id"
        private const val MAX_RANK = 100
        private const val MAX_GENRES = 2
        private const val COLLAPSED_LINES = 3
        private val TIER_IMAGES = arrayOf("mighty-man", "strong-man", "fair-man", "weak-man")
        private val TIER_COLORS = intArrayOf(
            Color.rgb(252, 67, 10),
            Color.rgb(158, 0, 180),
            Color.rgb(74, 161, 206),
            Color.rgb(128, 176, 106),
        )
        private val logger = Logger("MarkdownFix")

        @Volatile internal var pluginResources: Resources? = null

        internal fun open(view: View, id: String, preview: ResolvedGame?) {
            val gameId = id.toLongOrNull()?.takeIf { it > 0 } ?: return
            var context = view.context
            while (context is ContextWrapper && context !is FragmentActivity) context = context.baseContext
            val activity = context as? FragmentActivity ?: Utils.appActivity ?: return
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved) return
            GameProfileSheet()
                .apply {
                    this.preview = preview
                    arguments = Bundle().apply { putLong(ARG_ID, gameId) }
                }.show(manager, "GameProfileSheet")
        }
    }
}
