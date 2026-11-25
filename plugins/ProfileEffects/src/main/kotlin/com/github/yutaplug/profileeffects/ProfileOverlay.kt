package com.github.yutaplug.profileeffects

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import b.f.g.c.c
import com.discord.utilities.images.MGImages
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** Decorations never receive input or appear as empty nodes in the accessibility tree. */
internal open class TouchThroughLayout(context: Context) : FrameLayout(context) {
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun dispatchTouchEvent(event: MotionEvent) = false
}

/** Several open sheets can share ancestors; restore clipping only after the final owner leaves. */
internal class ClipOwners {
    private data class State(
        val children: Boolean,
        val padding: Boolean,
        var owners: Int = 1,
    )

    private val states = IdentityHashMap<ViewGroup, State>()

    fun acquire(host: ViewGroup): List<ViewGroup> {
        val result = ArrayList<ViewGroup>()
        var current: ViewGroup? = host
        var depth = 0
        while (current != null && depth++ < 5) {
            val group = current
            val state = states[group]
            if (state == null) states[group] = State(group.clipChildren, group.clipToPadding) else state.owners++
            group.clipChildren = false
            group.clipToPadding = false
            result.add(group)
            current = group.parent as? ViewGroup
        }
        return result
    }

    fun release(groups: List<ViewGroup>) {
        for (group in groups) {
            val state = states[group] ?: continue
            if (--state.owners != 0) continue
            states.remove(group)
            if (!group.clipChildren) group.clipChildren = state.children
            if (!group.clipToPadding) group.clipToPadding = state.padding
        }
    }
}

internal fun View.hasResourceName(name: String): Boolean = id != View.NO_ID &&
    try {
        resources.getResourceEntryName(id) == name
    } catch (_: Exception) {
        false
    }

private fun ancestor(view: View, name: String): View? {
    var current: View? = view
    while (current != null) {
        if (current.hasResourceName(name)) return current
        current = current.parent as? View
    }
    return null
}

private fun descendant(view: View, name: String): View? {
    if (view.hasResourceName(name)) return view
    if (view is ViewGroup) {
        var index = 0
        while (index < view.childCount) {
            val found = descendant(view.getChildAt(index++), name)
            if (found != null) return found
        }
    }
    return null
}

/** One owner per header, with removable listeners and independently ordered frame/effect layers. */
internal class ProfileOverlay private constructor(
    private val header: UserProfileHeaderView,
    private val host: ViewGroup,
    private val frameHost: FrameLayout,
    private val scroll: NestedScrollView,
    private val content: View,
    private val clips: ClipOwners,
    private val log: (String, Throwable) -> Unit,
) {
    private class LayerView(val layer: FrameLayer, val image: SimpleDraweeView) {
        var imageWidth = 0
        var imageHeight = 0
    }

    private val back = FrameOverlay(header.context, false)
    private val front = FrameOverlay(header.context, true)
    private val effect = EffectOverlay(header.context, log)
    private val overlays = listOf(back, effect, front)
    private val observed = listOf(header, host, frameHost, scroll, content).distinct()
    private var clipGroups: List<ViewGroup> = emptyList()
    private var frameOrigin: Rect? = null
    private var disposed = false
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanup = DeferredCleanup({ cleanupHandler.post(it) }, { cleanupHandler.removeCallbacks(it) })
    private val resizeTask = Runnable { resize() }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }

    init {
        frameHost.addView(back, 0, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        host.addView(effect, params())
        frameHost.addView(front, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        // Android orders elevated native containers ahead of later zero-elevation siblings.
        var elevation = 0f
        var index = 0
        while (index < frameHost.childCount) {
            val child = frameHost.getChildAt(index++)
            if (!overlays.contains(child)) elevation = maxOf(elevation, child.elevation)
        }
        effect.elevation = elevation + header.resources.displayMetrics.density
        front.elevation = effect.elevation + header.resources.displayMetrics.density
        for (view in observed) view.addOnLayoutChangeListener(layoutListener)
        host.post(resizeTask)
    }

    fun matches(header: UserProfileHeaderView): Boolean = host === findHost(header) &&
        frameHost === findFrameHost(header) &&
        content === (ancestor(header, "user_sheet_content") ?: header)

    fun bind(profile: Profile, animate: Boolean) {
        if (disposed) return
        if (profile.frame == null) frameOrigin = null
        if (profile.frame != null && clipGroups.isEmpty()) clipGroups = clips.acquire(frameHost)
        if (profile.frame == null && clipGroups.isNotEmpty()) {
            clips.release(clipGroups)
            clipGroups = emptyList()
        }
        back.bind(profile.frame)
        front.bind(profile.frame)
        effect.bind(profile.effect, animate)
        resize()
    }

    fun restartAnimation() = effect.restartAnimation()

    private fun params(): ViewGroup.LayoutParams = if (host is ConstraintLayout) {
        ConstraintLayout.LayoutParams(1, 1).apply {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
    } else {
        FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT)
    }

    private fun bounds(view: View, parent: ViewGroup = host): Rect {
        val rect = Rect(0, 0, view.width, view.height)
        if (view !== parent) parent.offsetDescendantRectToMyCoords(view, rect)
        return rect
    }

    private fun resize() {
        if (disposed || content.width <= 0 || content.height <= 0) return
        // A compatibility plugin can reparent the header during an in-progress layout pass.
        if (!matches(header)) return
        val rect = bounds(content)
        val banner = descendant(header, "banner") ?: header
        val railTop = (bounds(banner).top - rect.top).coerceAtLeast(0)
        back.railTop = railTop
        front.railTop = railTop
        // Capture the sheet's initial content position. Recomputing it after scroll would
        // make the frame follow the profile instead of remaining fixed over the sheet.
        val origin = frameOrigin ?: bounds(content, frameHost).also {
            if (back.metrics != null) frameOrigin = it
        }
        val scale = rect.width().toFloat() / (back.metrics?.innerWidth ?: 1200L)
        val overflowTop = ((back.metrics?.overflowTop ?: 0L) * scale).roundToInt()
        val overflowSide = ((back.metrics?.overflowHorizontal ?: 0L) * scale).roundToInt()
        val frameWidth = rect.width() + 2 * overflowSide
        val frameHeight = banner.height + railTop + overflowTop
        for (overlay in listOf(back, front)) {
            overlay.innerWidth = rect.width()
            overlay.topOverflow = overflowTop
            val params = overlay.layoutParams as FrameLayout.LayoutParams
            val left = origin.left - overflowSide
            val top = origin.top - overflowTop
            if (params.width != frameWidth || params.height != frameHeight ||
                params.leftMargin != left || params.topMargin != top) {
                params.width = frameWidth
                params.height = frameHeight
                params.leftMargin = left
                params.topMargin = top
                overlay.layoutParams = params
            }
        }
        val params = effect.layoutParams as ViewGroup.MarginLayoutParams
        if (params.width != rect.width() || params.height != rect.height() ||
            params.topMargin != rect.top || params.leftMargin != rect.left) {
            params.width = rect.width()
            params.height = rect.height()
            params.topMargin = rect.top
            params.leftMargin = rect.left
            effect.layoutParams = params
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        host.removeCallbacks(resizeTask)
        for (view in observed) view.removeOnLayoutChangeListener(layoutListener)
        effect.dispose()
        // Header detach runs while the host still traverses its original child array.
        // Retire immediately, but keep every child slot intact until that traversal returns.
        cleanup.schedule {
            back.bind(null)
            front.bind(null)
            for (overlay in overlays) (overlay.parent as? ViewGroup)?.removeView(overlay)
            clips.release(clipGroups)
            clipGroups = emptyList()
        }
    }

    companion object {
        private fun findHost(header: UserProfileHeaderView): ViewGroup? {
            val content = ancestor(header, "user_sheet_content")
            val candidate = content?.parent as? ViewGroup ?: header.parent as? ViewGroup
            // Inserting decorative siblings into a LinearLayout would alter the native card's height.
            return candidate?.takeIf { it is FrameLayout || it is ConstraintLayout }
        }

        private fun findFrameHost(header: UserProfileHeaderView): FrameLayout? {
            var current: View? = header
            while (current != null && current !is NestedScrollView) current = current.parent as? View
            val parent = current?.parent as? FrameLayout ?: return null
            return parent.takeIf { it.hasResourceName("design_bottom_sheet") }
        }

        fun create(
            header: UserProfileHeaderView,
            clips: ClipOwners,
            log: (String, Throwable) -> Unit,
        ): ProfileOverlay? {
            val host = findHost(header) ?: return null
            val frameHost = findFrameHost(header) ?: return null
            var scroll: View? = header
            while (scroll != null && scroll !is NestedScrollView) scroll = scroll.parent as? View
            val scrollView = scroll ?: return null
            val content = ancestor(header, "user_sheet_content") ?: header
            return ProfileOverlay(header, host, frameHost, scrollView, content, clips, log)
        }
    }

    private inner class FrameOverlay(context: Context, private val front: Boolean) : TouchThroughLayout(context) {
        private var frame: Product.Frame? = null
        val metrics: FrameMetrics? get() = frame?.metrics
        private val layers = ArrayList<LayerView>()
        var innerWidth = 0
            set(value) {
                if (field == value) return
                field = value
                layoutLayers()
            }
        var topOverflow = 0
            set(value) {
                if (field == value) return
                field = value
                layoutLayers()
            }
        var railTop = 0
            set(value) {
                if (field == value) return
                field = value
                layoutLayers()
            }

        override fun dispatchDraw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.clipRect(0, 0, width, height)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saved)
        }

        fun bind(frame: Product.Frame?) {
            if (this.frame == frame) return
            this.frame = frame
            for (layer in layers) layer.image.controller = null
            layers.clear()
            removeAllViews()
            if (frame != null) {
                for (layer in frame.layers) {
                    // User sheets have no separate frame footer; preserve the mobile top/rail layout.
                    if (layer.front != front || layer.anchor == Anchor.BOTTOM) continue
                    val image = SimpleDraweeView(context)
                    val view = LayerView(layer, image)
                    layers.add(view)
                    addView(image, LayoutParams(1, 1))
                    val route = "$CDN/media/v1/collectibles-shop/${frame.sku}/${layer.id}/static"
                    MGImages.setImage(
                        image,
                        listOf(route, "$route.png"),
                        0,
                        0,
                        false,
                        null,
                        MGImages.AlwaysUpdateChangeDetector.INSTANCE,
                        object : c<ImageInfo>() {
                            private fun update(info: ImageInfo?) {
                                if (disposed || this@FrameOverlay.frame != frame || !layers.contains(view)) return
                                if (info != null && info.width > 0 && info.height > 0) {
                                    view.imageWidth = info.width
                                    view.imageHeight = info.height
                                    layoutLayers()
                                }
                            }

                            override fun onFinalImageSet(id: String?, info: ImageInfo?, animatable: Animatable?) {
                                update(info)
                                animatable?.stop()
                            }

                            override fun onIntermediateImageSet(id: String?, info: ImageInfo?) = update(info)

                            override fun onFailure(id: String?, error: Throwable?) {
                                if (error != null) log("Could not load frame layer ${layer.id}", error)
                            }
                        },
                    )
                }
            }
            layoutLayers()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            layoutLayers()
        }

        private fun layoutLayers() {
            val metrics = frame?.metrics ?: return
            if (innerWidth <= 0) return
            val scale = innerWidth.toFloat() / metrics.innerWidth
            val layerWidth = ((metrics.innerWidth + 2f * metrics.overflowHorizontal) * scale)
                .coerceIn(1f, MAX_SURFACE_SIZE.toFloat())
                .roundToInt()
            for (view in layers) {
                val aspect = if (view.imageWidth > 0) view.imageHeight.toFloat() / view.imageWidth else 0.75f
                val layerHeight = (layerWidth * aspect).coerceIn(1f, MAX_SURFACE_SIZE.toFloat()).roundToInt()
                val bottom = view.layer.anchor == Anchor.BOTTOM
                val topMargin = if (bottom) {
                    0
                } else if (view.layer.rail || view.layer.border) {
                    // Rails and borders start at the card's top edge; staples sit above it.
                    topOverflow + railTop
                } else {
                    0
                }
                val bottomMargin = if (bottom) -(metrics.overflowBottom * scale).roundToInt() else 0
                val leftMargin = 0
                val gravity = Gravity.LEFT or if (bottom) Gravity.BOTTOM else Gravity.TOP
                val params = view.image.layoutParams as LayoutParams
                if (params.width == layerWidth &&
                    params.height == layerHeight &&
                    params.topMargin == topMargin &&
                    params.bottomMargin == bottomMargin &&
                    params.leftMargin == leftMargin &&
                    params.gravity == gravity
                ) {
                    continue
                }
                params.width = layerWidth
                params.height = layerHeight
                params.topMargin = topMargin
                params.bottomMargin = bottomMargin
                params.leftMargin = leftMargin
                params.gravity = gravity
                view.image.layoutParams = params
            }
        }
    }
}
