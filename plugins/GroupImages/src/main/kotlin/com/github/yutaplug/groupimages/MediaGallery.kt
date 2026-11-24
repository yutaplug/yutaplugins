package com.github.yutaplug.groupimages

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.player.MediaType
import com.discord.widgets.media.WidgetMedia
import com.facebook.samples.zoomable.ZoomableDraweeView
import com.google.android.exoplayer2.ui.PlayerView
import kotlin.math.abs
import b.a.d.j as AppScreens
import b.c.a.a0.d as MediaSources

/** Adds previous/next arrows, swiping and an "x/x" counter to Discord's fullscreen viewer for grouped media. */
object MediaGallery {
    private const val EXTRA_TITLES = "GROUPIMAGES_TITLES"
    private const val EXTRA_URLS = "GROUPIMAGES_URLS"
    private const val EXTRA_WIDTHS = "GROUPIMAGES_WIDTHS"
    private const val EXTRA_HEIGHTS = "GROUPIMAGES_HEIGHTS"
    private const val EXTRA_VIDEOS = "GROUPIMAGES_VIDEOS"
    private const val EXTRA_INDEX = "GROUPIMAGES_INDEX"
    private const val OVERLAY_TAG = "GroupImagesOverlay"

    private class Gallery(
        val titles: Array<String>,
        val urls: Array<String>,
        val widths: IntArray,
        val heights: IntArray,
        val videos: BooleanArray,
    ) {
        val size get() = urls.size

        fun putInto(intent: Intent) = intent
            .putExtra(EXTRA_TITLES, titles)
            .putExtra(EXTRA_URLS, urls)
            .putExtra(EXTRA_WIDTHS, widths)
            .putExtra(EXTRA_HEIGHTS, heights)
            .putExtra(EXTRA_VIDEOS, videos)

        companion object {
            fun from(intent: Intent): Gallery? {
                val gallery = Gallery(
                    intent.getStringArrayExtra(EXTRA_TITLES) ?: return null,
                    intent.getStringArrayExtra(EXTRA_URLS) ?: return null,
                    intent.getIntArrayExtra(EXTRA_WIDTHS) ?: return null,
                    intent.getIntArrayExtra(EXTRA_HEIGHTS) ?: return null,
                    intent.getBooleanArrayExtra(EXTRA_VIDEOS) ?: return null,
                )
                return if (gallery.size >= 2) gallery else null
            }
        }
    }

    fun open(context: Context, attachments: List<MessageAttachment>, index: Int) {
        val gallery = Gallery(
            attachments.map { it.a() }.toTypedArray(),
            attachments.map { it.c() }.toTypedArray(),
            attachments.map { it.g() ?: 0 }.toIntArray(),
            attachments.map { it.b() ?: 0 }.toIntArray(),
            attachments.map { it.e() == MessageAttachmentType.VIDEO }.toBooleanArray(),
        )
        launch(context, gallery, index)
    }

    /** Builds the same intent as WidgetMedia.Companion.launch(context, attachment), plus the gallery. */
    private fun launch(context: Context, gallery: Gallery, index: Int) {
        val url = gallery.urls[index]
        val source = if (gallery.videos[index]) MediaSources.P(MediaType.VIDEO, url, "javaClass") else null
        val intent = gallery.putInto(Intent())
            .putExtra(EXTRA_INDEX, index)
            .putExtra("INTENT_TITLE", gallery.titles[index])
            .putExtra("INTENT_MEDIA_URL", url)
            .putExtra("INTENT_IMAGE_URL", url)
            .putExtra("INTENT_MEDIA_WIDTH", gallery.widths[index])
            .putExtra("INTENT_MEDIA_HEIGHT", gallery.heights[index])
            .putExtra("INTENT_MEDIA_SOURCE", source)
        AppScreens.d(context, WidgetMedia::class.java, intent)
    }

    fun patch(patcher: PatcherAPI) {
        patcher.patch(
            WidgetMedia::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { call -> addOverlay(call.thisObject as WidgetMedia, call.args[0] as View) },
        )
        // Fade the overlay in and out together with the toolbar when the media is tapped.
        patcher.patch(
            WidgetMedia::class.java,
            "showControls",
            arrayOf(),
            Hook { call -> setOverlayShown(call.thisObject as WidgetMedia, true) },
        )
        patcher.patch(
            WidgetMedia::class.java,
            "hideControls",
            arrayOf(),
            Hook { call -> setOverlayShown(call.thisObject as WidgetMedia, false) },
        )
    }

    private fun setOverlayShown(fragment: WidgetMedia, shown: Boolean) {
        val overlay = fragment.view?.findViewWithTag<ViewGroup>(OVERLAY_TAG) ?: return
        for (i in 0 until overlay.childCount) {
            val child = overlay.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.isEnabled = shown
            val target = if (!shown) 0f else if (child is ImageView) ARROW_ALPHA else 1f
            child.animate().alpha(target).setDuration(200).start()
        }
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun addOverlay(fragment: WidgetMedia, view: View) {
        val root = view as? FrameLayout ?: return
        val intent = fragment.mostRecentIntent
        val gallery = Gallery.from(intent) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, gallery.size - 1)
        root.findViewWithTag<View>(OVERLAY_TAG)?.let { root.removeView(it) }

        val context = root.context
        var switching = false
        val go = { delta: Int ->
            val target = index + delta
            if (!switching && target in 0 until gallery.size) {
                switching = true
                val activity = findActivity(context) ?: fragment.activity
                if (activity != null) {
                    launch(activity, gallery, target)
                    activity.finish()
                    activity.overridePendingTransition(0, 0)
                }
            }
        }

        val overlay = FrameLayout(context).apply { tag = OVERLAY_TAG }

        // Same pill as Discord's spoiler label (widget_chat_list_adapter_item_attachment.xml).
        val counterText = TextView(context, null, 0, Utils.getResId("UiKit_TextView_H2", "style")).apply {
            text = "${index + 1}/${gallery.size}"
            setTextColor(Color.WHITE)
            val horizontal = DimenUtils.dpToPx(12)
            val vertical = DimenUtils.dpToPx(4)
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        val counter = CardView(context).apply {
            setCardBackgroundColor(ContextCompat.getColor(context, Utils.getResId("black_alpha_60", "color")))
            radius = DimenUtils.dpToPx(16).toFloat()
            cardElevation = 0f
            addView(counterText)
        }
        val counterParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = DimenUtils.dpToPx(24)
        }
        overlay.addView(counter, counterParams)

        // Same round buttons as the inline media play button (inline_media_view.xml).
        fun arrow(icon: String, gravity: Int, delta: Int) = ImageView(context).apply {
            val size = DimenUtils.dpToPx(44)
            val padding = DimenUtils.dpToPx(8)
            setBackgroundResource(Utils.getResId("drawable_circle_primary_900", "drawable"))
            setImageResource(Utils.getResId(icon, "drawable"))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            alpha = ARROW_ALPHA
            contentDescription = if (delta < 0) "Previous" else "Next"
            visibility = if (index + delta in 0 until gallery.size) View.VISIBLE else View.GONE
            setOnClickListener { go(delta) }
            overlay.addView(
                this,
                FrameLayout.LayoutParams(size, size, gravity or Gravity.CENTER_VERTICAL).apply {
                    leftMargin = DimenUtils.dpToPx(12)
                    rightMargin = DimenUtils.dpToPx(12)
                },
            )
        }
        arrow("exo_ic_chevron_left", Gravity.START, -1)
        arrow("exo_ic_chevron_right", Gravity.END, 1)

        // Put the overlay under the toolbar but above the media.
        val toolbar = root.findViewById<View>(Utils.getResId("action_bar_toolbar_layout", "id"))
        val position = toolbar?.let { root.indexOfChild(it) } ?: root.childCount
        root.addView(overlay, position, FrameLayout.LayoutParams(-1, -1))

        // Keep the counter above the video controls.
        val controls = root.findViewById<View>(Utils.getResId("media_player_control_view", "id"))
        controls?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val margin = DimenUtils.dpToPx(24) + if (v.visibility == View.VISIBLE) v.height else 0
            if (counterParams.bottomMargin != margin) {
                counterParams.bottomMargin = margin
                counter.layoutParams = counterParams
            }
        }

        // Swipe sideways to switch, unless the image is zoomed in.
        val image = root.findViewById<ZoomableDraweeView>(Utils.getResId("media_image", "id"))
        val swipeDistance = DimenUtils.dpToPx(60)
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 == null) return false
                    if (image != null && image.visibility == View.VISIBLE &&
                        image.computeHorizontalScrollRange() > image.computeHorizontalScrollExtent() + 1
                    ) {
                        return false
                    }
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) < swipeDistance || abs(dx) < abs(dy) * 1.5f) return false
                    go(if (dx < 0) 1 else -1)
                    return true
                }
            },
        )
        val swipeListener = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
        image?.setOnTouchListener(swipeListener)
        root.findViewById<PlayerView>(Utils.getResId("media_player_view", "id"))
            ?.videoSurfaceView?.setOnTouchListener(swipeListener)
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private const val ARROW_ALPHA = 0.6f
}
