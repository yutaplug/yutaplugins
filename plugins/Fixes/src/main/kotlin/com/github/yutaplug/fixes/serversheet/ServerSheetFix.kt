package com.github.yutaplug.fixes.serversheet

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheetViewModel
import com.github.yutaplug.fixes.Fix
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class ServerSheetFix : Fix(
    "ServerSheetFix",
    "Server sheet",
    "Fixes the server sheet sometimes opening scrolled or laid out wrong.",
) {
    private val initialized = WeakHashMap<WidgetGuildProfileSheet, Boolean>()
    private val openingGuards = WeakHashMap<NestedScrollView, OpeningGuard>()

    override fun start(context: Context) {
        patcher.patch(
            WidgetGuildProfileSheet::class.java,
            "onViewCreated",
            arrayOf(View::class.java, android.os.Bundle::class.java),
            Hook { call ->
                val root = call.args[0] as? NestedScrollView ?: return@Hook
                // The layout paints only its inner flipper. If the scroll viewport
                // grows past that child, Discord's page shows through the sheet.
                val flipper = root.findViewById<View>(Utils.getResId("guild_profile_sheet_flipper", "id"))
                val background = flipper?.background?.constantState?.newDrawable(root.resources)?.mutate()
                paintSheet(root, background)
                root.isFillViewport = true
                root.isFocusableInTouchMode = true
            },
        )
        patcher.patch(
            com.discord.app.AppBottomSheet::class.java,
            "onResume",
            emptyArray(),
            Hook { call ->
                val sheet = call.thisObject as? WidgetGuildProfileSheet ?: return@Hook
                val root = sheet.view as? NestedScrollView ?: return@Hook
                root.post {
                    if (sheet.view === root) {
                        repairActionPlacement(root)
                        root.requestFocus()
                        root.scrollTo(0, 0)
                    }
                }
            },
        )
        patcher.patch(
            WidgetGuildProfileSheet::class.java,
            "updateView",
            arrayOf(WidgetGuildProfileSheetViewModel.ViewState.Loaded::class.java),
            Hook { call ->
                val sheet = call.thisObject as WidgetGuildProfileSheet
                if (initialized.put(sheet, true) != null) return@Hook
                val root = sheet.view as? NestedScrollView ?: return@Hook
                openingGuards.remove(root)?.close()
                openingGuards[root] = OpeningGuard(root).also { it.start() }
                // A focused action can make NestedScrollView restore to the middle
                // of the profile before its asynchronously loaded content settles.
                root.post {
                    if (sheet.view === root) {
                        repairActionPlacement(root)
                        root.requestFocus()
                        root.scrollTo(0, 0)
                    }
                }
            },
        )
        patcher.patch(
            NestedScrollView::class.java,
            "onInterceptTouchEvent",
            arrayOf(MotionEvent::class.java),
            PreHook { call ->
                if ((call.args[0] as MotionEvent).actionMasked == MotionEvent.ACTION_DOWN) {
                    openingGuards.remove(call.thisObject as NestedScrollView)?.close()
                }
            },
        )
    }

    private fun repairActionPlacement(root: NestedScrollView) {
        val actions = root.findViewById<View>(Utils.getResId("guild_profile_sheet_bottom_container", "id"))
            ?: return
        val tabs = root.findViewById<View>(Utils.getResId("guild_profile_sheet_tab_items", "id"))
            ?: return
        val parent = actions.parent as? ConstraintLayout ?: return
        if (tabs.parent !== parent) return
        val params = actions.layoutParams as? ConstraintLayout.LayoutParams ?: return
        // The ViewStub's barrier constraint occasionally resolves to the top of
        // the parent, putting the whole actions column over the banner. The tab
        // row already follows the header, so anchor the column directly to it.
        if (params.topToBottom != tabs.id || params.topToTop != ConstraintLayout.LayoutParams.UNSET) {
            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = tabs.id
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.width = 0
            actions.layoutParams = params
        }
        parent.requestLayout()
    }

    private fun paintSheet(root: NestedScrollView, background: Drawable?) {
        if (background == null) return
        root.background = background
        root.post {
            val containerId = Utils.getResId("design_bottom_sheet", "id")
            val container = root.rootView.findViewById<FrameLayout>(containerId)
            if (root.isAttachedToWindow && container != null) {
                container.background = background.constantState?.newDrawable(root.resources)?.mutate()
            }
        }
    }

    private inner class OpeningGuard(root: NestedScrollView) : ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
        private val view = WeakReference(root)
        private val expiresAt = SystemClock.uptimeMillis() + 1200L

        fun start() {
            view.get()?.let {
                it.viewTreeObserver.addOnPreDrawListener(this)
                it.addOnAttachStateChangeListener(this)
            }
        }

        override fun onPreDraw(): Boolean {
            val root = view.get() ?: return true
            if (SystemClock.uptimeMillis() >= expiresAt) {
                openingGuards.remove(root)
                close()
            } else if (root.scrollY != 0) {
                root.scrollTo(0, 0)
            }
            return true
        }

        override fun onViewAttachedToWindow(view: View) {}

        override fun onViewDetachedFromWindow(view: View) {
            (view as? NestedScrollView)?.let { openingGuards.remove(it) }
            close()
        }

        fun close() {
            view.get()?.let {
                val observer = it.viewTreeObserver
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                it.removeOnAttachStateChangeListener(this)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        openingGuards.values.toList().forEach { it.close() }
        openingGuards.clear()
        initialized.clear()
    }
}
