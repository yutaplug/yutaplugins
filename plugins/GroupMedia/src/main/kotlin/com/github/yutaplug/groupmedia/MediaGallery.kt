package com.github.yutaplug.groupmedia

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.player.MediaType
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.media.WidgetMedia
import com.facebook.common.references.CloseableReference
import com.facebook.datasource.DataSource
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.samples.zoomable.ZoomableDraweeView
import com.google.android.exoplayer2.ui.PlayerView
import java.util.concurrent.Executor
import kotlin.math.abs
import b.a.d.j as AppScreens
import b.c.a.a0.d as MediaSources
import b.f.e.f as DataSubscriber
import b.f.g.a.a.b as FrescoHolder
import b.f.j.j.c as CloseableImage
import com.facebook.imagepipeline.request.ImageRequest.c as RequestLevel

/** Adds previous/next arrows, swiping and an "x/x" counter to Discord's fullscreen viewer for grouped media. */
object MediaGallery {
    private const val EXTRA_TITLES = "GROUPMEDIA_TITLES"
    private const val EXTRA_URLS = "GROUPMEDIA_URLS"
    private const val EXTRA_WIDTHS = "GROUPMEDIA_WIDTHS"
    private const val EXTRA_HEIGHTS = "GROUPMEDIA_HEIGHTS"
    private const val EXTRA_VIDEOS = "GROUPMEDIA_VIDEOS"
    private const val EXTRA_INDEX = "GROUPMEDIA_INDEX"
    private const val OVERLAY_TAG = "GroupMediaOverlay"
    private val logger = Logger("GroupMedia")

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

    private var showArrows: () -> Boolean = { true }

    fun patch(patcher: PatcherAPI, showArrows: () -> Boolean) {
        this.showArrows = showArrows
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
            if (child.visibility == View.GONE) continue
            child.isEnabled = shown
            val target = if (!shown) 0f else if (child is ImageView) ARROW_ALPHA else 1f
            // Hide faded-out views so the invisible thumbnails and arrows can't be tapped.
            if (shown) child.visibility = View.VISIBLE
            child.animate().alpha(target).setDuration(200)
                .withEndAction { if (!shown) child.visibility = View.INVISIBLE }
                .start()
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

        // Counter on top, thumbnail strip under it, both at the bottom of the screen.
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

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
        bottom.addView(counter, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(
            thumbnailStrip(context, gallery, index) { target -> go(target - index) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = DimenUtils.dpToPx(12) },
        )
        val bottomParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = DimenUtils.dpToPx(BOTTOM_MARGIN_DP)
        }
        overlay.addView(bottom, bottomParams)

        // Same round buttons as the inline media play button (inline_media_view.xml).
        val arrowsShown = showArrows()
        fun arrow(icon: String, gravity: Int, delta: Int) = ImageView(context).apply {
            val size = DimenUtils.dpToPx(44)
            val padding = DimenUtils.dpToPx(8)
            setBackgroundResource(Utils.getResId("drawable_circle_primary_900", "drawable"))
            setImageResource(Utils.getResId(icon, "drawable"))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(padding, padding, padding, padding)
            alpha = ARROW_ALPHA
            contentDescription = if (delta < 0) "Previous" else "Next"
            visibility = if (arrowsShown && index + delta in 0 until gallery.size) View.VISIBLE else View.GONE
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

        // Keep the counter and thumbnails above the video controls.
        val controls = root.findViewById<View>(Utils.getResId("media_player_control_view", "id"))
        // The control view itself fills the screen (exo_player_control_view.xml is match_parent);
        // only the bar holding the play button at its bottom takes space.
        val playButtonId = Utils.getResId("exo_play", "id")
        controls?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val bar = v.findViewById<View>(playButtonId)?.parent as? View
            val barHeight = if (v.visibility == View.VISIBLE && bar != null) v.height - bar.top else 0
            val margin = DimenUtils.dpToPx(BOTTOM_MARGIN_DP) + barHeight
            if (bottomParams.bottomMargin != margin) {
                bottomParams.bottomMargin = margin
                bottom.layoutParams = bottomParams
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

        prefetch(fragment, gallery, index)
    }

    private val formattedUrlMethod by lazy {
        WidgetMedia::class.java.getDeclaredMethod("getFormattedUrl", Context::class.java, Uri::class.java)
            .apply { isAccessible = true }
    }

    /**
     * Loads the other images into Fresco's caches now, nearest first, so swiping to them shows them right away.
     * Uses the viewer's own getFormattedUrl (with Aliucord's fixes) so the request matches the one it makes later.
     */
    private fun prefetch(fragment: WidgetMedia, gallery: Gallery, index: Int) {
        try {
            val context = fragment.context ?: return
            val pipeline = FrescoHolder.a?.b ?: return
            val intent = fragment.mostRecentIntent
            val width = intent.getIntExtra("INTENT_MEDIA_WIDTH", 0)
            val height = intent.getIntExtra("INTENT_MEDIA_HEIGHT", 0)
            val targets = (0 until gallery.size)
                .filter { it != index && !gallery.videos[it] }
                .sortedBy { abs(it - index) }
            val urls = try {
                targets.map {
                    // getFormattedUrl sizes the URL from the intent's width and height.
                    intent.putExtra("INTENT_MEDIA_WIDTH", gallery.widths[it])
                    intent.putExtra("INTENT_MEDIA_HEIGHT", gallery.heights[it])
                    formattedUrlMethod.invoke(fragment, context, Uri.parse(gallery.urls[it])) as String
                }
            } finally {
                intent.putExtra("INTENT_MEDIA_WIDTH", width)
                intent.putExtra("INTENT_MEDIA_HEIGHT", height)
            }
            for (url in urls) {
                val request = MGImages.getImageRequest(url, 0, 0, false).a()
                val source = pipeline.a(request, null, RequestLevel.j /* FULL_FETCH */, null, null)
                // Closing the finished source only drops our reference; the image stays cached.
                source.f(
                    object : DataSubscriber<CloseableReference<CloseableImage>> {
                        override fun onNewResult(ds: DataSource<CloseableReference<CloseableImage>>) {
                            if (ds.c()) ds.close()
                        }

                        override fun onFailure(ds: DataSource<CloseableReference<CloseableImage>>) {
                            ds.close()
                        }

                        override fun onCancellation(ds: DataSource<CloseableReference<CloseableImage>>) {}

                        override fun onProgressUpdate(ds: DataSource<CloseableReference<CloseableImage>>) {}
                    },
                    Executor { it.run() },
                )
            }
        } catch (e: Throwable) {
            logger.error("Failed to preload gallery images", e)
        }
    }

    /** A row of small previews of every item; the current one is wider and fully opaque, tapping another opens it. */
    private fun thumbnailStrip(context: Context, gallery: Gallery, index: Int, open: (Int) -> Unit): View {
        val height = DimenUtils.dpToPx(THUMB_HEIGHT_DP)
        val gap = DimenUtils.dpToPx(4)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val side = DimenUtils.dpToPx(16)
            setPadding(side, 0, side, 0)
        }
        var selected: View? = null
        for (i in 0 until gallery.size) {
            val current = i == index
            val width = DimenUtils.dpToPx(if (current) THUMB_SELECTED_WIDTH_DP else THUMB_WIDTH_DP)
            val image = SimpleDraweeView(context)
            setCenterCrop(image)
            val (w, h) = previewSize(gallery.widths[i], gallery.heights[i], width, height)
            MGImages.setImage(image, EmbedResourceUtils.INSTANCE.getPreviewUrls(gallery.urls[i], w, h, false))
            val card = CardView(context).apply {
                radius = DimenUtils.dpToPx(4).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(ContextCompat.getColor(context, Utils.getResId("black_alpha_60", "color")))
                alpha = if (current) 1f else THUMB_ALPHA
                contentDescription = gallery.titles[i]
                addView(image, FrameLayout.LayoutParams(-1, -1))
                if (!current) setOnClickListener { open(i) }
            }
            row.addView(card, LinearLayout.LayoutParams(width, height).apply { if (i > 0) leftMargin = gap })
            if (current) selected = card
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
            // Center the current item when the row is wider than the screen.
            post {
                val target = selected ?: return@post
                scrollTo(target.left + target.width / 2 - width / 2, 0)
            }
        }
    }

    /** Proxy size that covers a [boxWidth]x[boxHeight] box while keeping the media's aspect ratio. */
    private fun previewSize(mediaWidth: Int, mediaHeight: Int, boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
        if (mediaWidth <= 0 || mediaHeight <= 0) return boxWidth to boxHeight
        val scale = maxOf(boxWidth.toFloat() / mediaWidth, boxHeight.toFloat() / mediaHeight)
        return maxOf(1, (mediaWidth * scale).toInt()) to maxOf(1, (mediaHeight * scale).toInt())
    }

    /** Fresco's CENTER_CROP; the obfuscated ScalingUtils$ScaleType class can't be named from Kotlin. */
    private fun setCenterCrop(view: SimpleDraweeView) {
        try {
            val scaleTypeClass = Class.forName("com.facebook.drawee.drawable.ScalingUtils\$ScaleType")
            val centerCrop = scaleTypeClass.getField("i").get(null)
            val hierarchy = view.hierarchy
            hierarchy.javaClass.getMethod("n", scaleTypeClass).invoke(hierarchy, centerCrop)
        } catch (_: Throwable) {
        }
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
    private const val THUMB_ALPHA = 0.5f
    private const val BOTTOM_MARGIN_DP = 24
    private const val THUMB_HEIGHT_DP = 52
    private const val THUMB_WIDTH_DP = 26
    private const val THUMB_SELECTED_WIDTH_DP = 40
}
