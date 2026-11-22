package com.github.yutaplug.typingnotifications

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.discord.views.typing.TypingDots
import java.lang.ref.WeakReference

/**
 * A compact typing banner built from Discord's own chat typing bar (`chat_overlay_typing` in
 * `widget_chat_overlay`: the animated dots and the typing text), shown as a small card at the top of
 * the screen. Unlike Discord's notice popups this view belongs to the plugin, so it is edited in place.
 * Main thread only.
 */
internal class CompactBanner(private val onError: (String, Throwable) -> Unit) {
    private var bar: WeakReference<View>? = null
    private var text: WeakReference<TextView>? = null
    private var dots: WeakReference<TypingDots>? = null
    private var onClick: () -> Unit = {}
    private val hideLater = Runnable { hide() }

    /** Shows the banner with [content], or updates the one on screen, and restarts its timer. */
    fun show(activity: Activity, content: CharSequence, durationMs: Long, click: () -> Unit) {
        try {
            onClick = click
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val current = bar?.get()
            if (current == null || current.parent !== decor) {
                current?.let(::remove)
                create(activity, decor) ?: return
            }
            text?.get()?.text = content
            Utils.mainThread.removeCallbacks(hideLater)
            Utils.mainThread.postDelayed(hideLater, durationMs)
        } catch (error: Throwable) {
            onError("show compact typing banner", error)
        }
    }

    fun hide() {
        Utils.mainThread.removeCallbacks(hideLater)
        val view = bar?.get() ?: return
        bar = null
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(-view.height.toFloat() / 2)
            .setDuration(150)
            .withEndAction { remove(view) }
            .start()
    }

    /** Removes the banner immediately, e.g. when the plugin stops. */
    fun clear() {
        Utils.mainThread.removeCallbacks(hideLater)
        bar?.get()?.let(::remove)
        bar = null
    }

    private fun remove(view: View) {
        dots?.get()?.c()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun create(activity: Activity, decor: ViewGroup): View? {
        val layout = Utils.getResId("widget_chat_overlay", "layout")
        if (layout == 0) return null
        val overlay = LayoutInflater.from(activity).inflate(layout, decor, false)
        val typing = overlay.findViewById<RelativeLayout>(Utils.getResId("chat_overlay_typing", "id")) ?: return null
        val label = typing.findViewById<TextView>(Utils.getResId("chat_typing_users_typing", "id")) ?: return null
        val typingDots = typing.findViewById<TypingDots>(Utils.getResId("chat_overlay_typing_dots", "id"))
        (typing.parent as? ViewGroup)?.removeView(typing)

        // Slowmode belongs to the chat bar, not to a notification.
        listOf("chat_typing_users_slowmode", "chat_typing_users_slowmode_icon").forEach { name ->
            typing.findViewById<View>(Utils.getResId(name, "id"))?.visibility = View.GONE
        }
        val rowHeight = dimen(activity, "uikit_typing_indicator_height", 32)
        typing.visibility = View.VISIBLE
        typing.minimumHeight = rowHeight
        typing.setPadding(dp(activity, 12), dp(activity, 2), dp(activity, 12), dp(activity, 2))
        typing.background = GradientDrawable().apply {
            setColor(color(activity, "colorBackgroundTertiary"))
            cornerRadius = dp(activity, 8).toFloat()
        }
        typing.elevation = dimen(activity, "app_elevation", 6).toFloat()
        typingDots?.layoutParams = (typingDots?.layoutParams as? RelativeLayout.LayoutParams)?.apply {
            height = rowHeight
            addRule(RelativeLayout.ALIGN_PARENT_TOP)
        }
        // Several places get one line each, so let the text grow instead of the bar's fixed single line.
        label.maxLines = 5
        label.minHeight = rowHeight
        label.layoutParams = (label.layoutParams as RelativeLayout.LayoutParams).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        label.setTextColor(color(activity, "colorTextNormal"))
        typing.isClickable = true
        typing.setOnClickListener {
            onClick()
            hide()
        }

        val side = dp(activity, 8)
        decor.addView(
            typing,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { setMargins(side, statusBarHeight(activity) + side, side, 0) },
        )
        typing.alpha = 0f
        typing.translationY = -rowHeight.toFloat()
        typing.animate().alpha(1f).translationY(0f).setDuration(150).start()
        typingDots?.a(false)

        bar = WeakReference(typing)
        text = WeakReference(label)
        dots = typingDots?.let { WeakReference(it) }
        return typing
    }

    private fun statusBarHeight(activity: Activity): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
    }

    private fun dimen(activity: Activity, name: String, fallbackDp: Int): Int {
        val id = Utils.getResId(name, "dimen")
        return if (id != 0) activity.resources.getDimensionPixelSize(id) else dp(activity, fallbackDp)
    }

    private fun color(activity: Activity, attr: String): Int = ColorCompat.getThemedColor(activity, Utils.getResId(attr, "attr"))

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
