package com.github.yutaplug.dmactivities

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils.dpToPx
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityType
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.StatusView
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R
import rx.Subscriber
import rx.Subscription
import kotlin.math.abs

class ActivitiesViewHolder(
    private val recycler: RecyclerView,
    adapter: WidgetChannelsListAdapter,
) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(recycler, adapter) {
    private val cardsAdapter = CardsAdapter()

    init {
        recycler.adapter = cardsAdapter
    }

    override fun onConfigure(position: Int, item: ChannelListItem) {
        super.onConfigure(position, item)
        cardsAdapter.submit((item as DMActivities.ActivitiesItem).cards)
    }

    private class CardsAdapter : RecyclerView.Adapter<CardHolder>() {
        private var cards: List<DMActivities.ActivityCard> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<DMActivities.ActivityCard>) {
            if (next == cards) return
            cards = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardHolder(parent.context)

        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(cards[position])

        override fun onViewRecycled(holder: CardHolder) = holder.unbind()
    }

    private class CardHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)) {
        private val card = itemView as FrameLayout
        private val image = SimpleDraweeView(context)
        private val status = StatusView(context, null)
        private val smallAvatar = SimpleDraweeView(context)
        private val name = TextView(context, null, 0, R.i.UiKit_TextView_Semibold)
        private val emoji = SimpleDraweeView(context)
        private val subtitle = TextView(context, null, 0, R.i.UiKit_TextView)
        private val typeIcon = ImageView(context)
        private var appSubscription: Subscription? = null

        init {
            val cardColor = ColorCompat.getThemedColor(context, R.b.colorBackgroundPrimary)
            card.background = GradientDrawable().apply {
                cornerRadius = dpToPx(16).toFloat()
                setColor(cardColor)
            }
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                card.foreground = context.getDrawable(ripple.resourceId)
            }
            card.layoutParams = RecyclerView.LayoutParams(dpToPx(244), dpToPx(92)).apply { marginEnd = dpToPx(8) }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            }
            card.addView(content, FrameLayout.LayoutParams(-1, -1))

            val imageBox = FrameLayout(context)
            imageBox.addView(image, FrameLayout.LayoutParams(dpToPx(72), dpToPx(72)))
            status.setBackgroundColor(cardColor)
            status.setBorderWidth(dpToPx(3))
            status.setCornerRadius(dpToPx(3).toFloat())
            imageBox.addView(
                status,
                FrameLayout.LayoutParams(dpToPx(22), -2, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, -dpToPx(2), -dpToPx(2))
                },
            )
            imageBox.clipChildren = false
            content.addView(imageBox, LinearLayout.LayoutParams(dpToPx(72), dpToPx(72)))

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            clipRounded(smallAvatar, circle = true)
            texts.addView(
                smallAvatar,
                LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply { bottomMargin = dpToPx(4) },
            )
            val nameRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            name.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ColorCompat.getThemedColor(context, R.b.colorHeaderPrimary))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            // A capped width keeps the emoji right after the name instead of pushed to the end.
            name.maxWidth = dpToPx(124)
            nameRow.addView(name, LinearLayout.LayoutParams(-2, -2))
            nameRow.addView(
                emoji,
                LinearLayout.LayoutParams(dpToPx(20), dpToPx(20)).apply { marginStart = dpToPx(4) },
            )
            texts.addView(nameRow, LinearLayout.LayoutParams(-1, -2))
            subtitle.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ColorCompat.getThemedColor(context, R.b.colorTextMuted))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            texts.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
            content.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpToPx(12) })

            card.addView(
                typeIcon,
                FrameLayout.LayoutParams(dpToPx(18), dpToPx(18), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dpToPx(10), dpToPx(12), 0)
                },
            )
        }

        fun bind(data: DMActivities.ActivityCard) {
            unbind()
            val context = itemView.context
            val activity = data.activity
            val custom = data.customStatus
            name.text = data.name

            val emojiData = custom?.f()
            val emojiId = emojiData?.b()?.toLongOrNull()
            when {
                emojiId != null -> {
                    emoji.visibility = View.VISIBLE
                    MGImages.setImage(emoji, ModelEmojiCustom.getImageUri(emojiId, emojiData.a(), dpToPx(20)))
                }

                emojiData?.c() != null -> {
                    emoji.visibility = View.GONE
                    name.text = "${data.name} ${emojiData.c()}"
                }

                else -> emoji.visibility = View.GONE
            }

            if (activity != null) {
                status.visibility = View.GONE
                smallAvatar.visibility = View.VISIBLE
                MGImages.setImage(smallAvatar, IconUtils.getForUser(data.user, true, dpToPx(24)))
                clipRounded(image, circle = false)
                bindActivityImage(activity)
                subtitle.text = subtitleFor(activity)
                subtitle.visibility = View.VISIBLE
                bindTypeIcon(activity)
            } else {
                smallAvatar.visibility = View.GONE
                clipRounded(image, circle = true)
                MGImages.setImage(image, IconUtils.getForUser(data.user, true, dpToPx(72)))
                status.visibility = View.VISIBLE
                status.setPresence(data.presence)
                val text = custom?.l()
                subtitle.text = text
                subtitle.visibility = if (!DMActivities.hasText(text)) View.GONE else View.VISIBLE
                typeIcon.visibility = View.GONE
            }

            card.contentDescription = listOfNotNull(data.name, subtitle.text?.takeIf { subtitle.visibility == View.VISIBLE })
                .joinToString(", ")
            card.setOnClickListener {
                val manager = (Utils.appActivity as? FragmentActivity ?: context as? FragmentActivity)
                    ?.supportFragmentManager ?: return@setOnClickListener
                WidgetUserSheet()
                    .apply {
                        arguments = Bundle().apply {
                            putLong("ARG_USER_ID", data.user.id)
                            putBoolean("ARG_IS_VOICE_CONTEXT", false)
                            putSerializable(
                                "ARG_STREAM_PREVIEW_CLICK_BEHAVIOR",
                                WidgetUserSheet.StreamPreviewClickBehavior.TARGET_AND_LAUNCH_SPECTATE,
                            )
                        }
                    }
                    .show(manager, WidgetUserSheet::class.java.name)
            }
        }

        fun unbind() {
            appSubscription?.unsubscribe()
            appSubscription = null
        }

        private fun subtitleFor(activity: Activity): String =
            when (activity.p()) {
                ActivityType.LISTENING, ActivityType.WATCHING -> activity.e() ?: activity.h()
                ActivityType.STREAMING -> activity.e() ?: activity.h()
                else -> activity.h()
            }

        private fun bindActivityImage(activity: Activity) {
            val appId = activity.a()
            val assets = activity.b()
            val asset = assets?.a() ?: assets?.c()
            val url = asset?.let { IconUtils.INSTANCE.getAssetImage(appId, it, dpToPx(72)) }
            if (url != null) {
                MGImages.setImage(image, url)
                return
            }
            image.setImageResource(Utils.getResId("drawable_ic_game_icon_placeholder_dark", "drawable"))
            if (appId == null) return
            // Detected games and apps without art fall back to the application icon.
            appSubscription = StoreStream.getApplication().observeApplication(appId).U(
                object : Subscriber<com.discord.api.application.Application?>() {
                    override fun onNext(app: com.discord.api.application.Application?) {
                        val icon = app?.f() ?: return
                        Utils.mainThread.post {
                            if (appSubscription != null) {
                                MGImages.setImage(image, IconUtils.getApplicationIcon(appId, icon, dpToPx(72)))
                            }
                        }
                    }

                    override fun onError(error: Throwable) {}

                    override fun onCompleted() {}
                },
            )
        }

        private fun clipRounded(view: View, circle: Boolean) {
            val radius = dpToPx(12).toFloat()
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (circle) {
                        outline.setOval(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            }
            view.clipToOutline = true
            view.invalidateOutline()
        }

        private fun bindTypeIcon(activity: Activity) {
            val context = itemView.context
            val isSpotify = activity.p() == ActivityType.LISTENING && "Spotify".equals(activity.h(), ignoreCase = true)
            val (drawable, tint) = when {
                isSpotify -> "ic_spotify_green_24dp" to null
                activity.p() == ActivityType.STREAMING -> "ic_call_indicator_streaming_16dp" to null
                activity.p() == ActivityType.PLAYING || activity.p() == ActivityType.COMPETING ->
                    "ic_controller_24dp" to ColorCompat.getColor(context, Utils.getResId("status_green_600", "color"))

                else -> "ic_activity_24dp" to ColorCompat.getThemedColor(context, R.b.colorInteractiveNormal)
            }
            typeIcon.visibility = View.VISIBLE
            typeIcon.setImageResource(Utils.getResId(drawable, "drawable"))
            if (tint != null) typeIcon.setColorFilter(tint) else typeIcon.clearColorFilter()
        }
    }

    companion object {
        fun createView(context: Context): RecyclerView =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dpToPx(8) }
                setPadding(dpToPx(16), dpToPx(4), dpToPx(8), dpToPx(4))
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isNestedScrollingEnabled = false
                addOnItemTouchListener(HorizontalSwipeGuard())
            }
    }

    /** Keeps the panel layout from stealing horizontal swipes while the carousel can still scroll. */
    private class HorizontalSwipeGuard : RecyclerView.SimpleOnItemTouchListener() {
        private var startX = 0f
        private var startY = 0f

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - startX
                    val dy = e.y - startY
                    if (abs(dx) > abs(dy)) {
                        // Hand the gesture back at the ends so panels still open from the edges.
                        rv.parent?.requestDisallowInterceptTouchEvent(rv.canScrollHorizontally(if (dx < 0) 1 else -1))
                    } else if (abs(dy) > abs(dx)) {
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            return false
        }
    }
}
