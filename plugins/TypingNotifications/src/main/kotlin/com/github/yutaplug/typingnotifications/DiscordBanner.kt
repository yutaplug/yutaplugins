package com.github.yutaplug.typingnotifications

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.discord.utilities.images.MGImages
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.WidgetUserMentions
import java.lang.ref.WeakReference
import b.p.a.b as Alert

/**
 * One Discord in-app banner (NoticePopup) for everyone typing. The banner on screen is edited in place, and
 * a new one is only shown when none is showing.
 *
 * NoticePopup.enqueue is unreliable, so this runs NoticePopup's own display code directly instead
 * (like Fixes' updater popups):
 * - Discord's auto-dismiss countdown is a ValueAnimator, which ends at once with Android's animations off
 *   (Battery saver, "Remove animations"), and a tap ended it before the click landed. So the banner has
 *   no countdown from Discord; [hideLater], a plain timer, hides it.
 * - Discord's notice queue shows one notice at a time, only when its navigation updates, and could leave
 *   the banner behind Discord's own notices until the typing was long over. It's skipped.
 * - Discord relaunches the screen soon after startup, taking a banner shown on it along. So the banner
 *   waits until a screen has stayed in front for a moment, and is shown again if that screen closes.
 *
 * Main thread only.
 */
internal class DiscordBanner(
    private val currentActivity: () -> Activity?,
    private val onError: (String, Throwable) -> Unit,
) {
    /** Whether a banner was asked for and not hidden yet (it may still be waiting for a screen). */
    private var requested = false
    private var text: TypingText? = null
    private var iconUrl: String? = null
    private var onClick: () -> Unit = {}

    /** Like Discord's own banners, a touched banner stays until dismissed. */
    private var touched = false

    /** The banner last handed to Discord: its name, the screen it went on, and when. */
    private var count = 0L
    private var noticeName: String? = null
    private var shownIn: WeakReference<Activity>? = null
    private var shownAt = 0L
    private var attempts = 0

    /** The title the banner on screen shows, used to find its view without grabbing Discord's own banners. */
    private var shownTitle: String? = null
    private var alert: WeakReference<Alert>? = null

    /** When a screen last came to the front. */
    private var resumedAt = 0L

    private val hideLater = Runnable { hide() }

    private val watch = object : Runnable {
        override fun run() {
            if (!requested) return
            try {
                step()
            } catch (error: Throwable) {
                onError("show in-app typing banner", error)
                hide()
            }
            if (requested) Utils.mainThread.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    /** Called when any screen comes to the front. */
    fun onScreenResumed() {
        resumedAt = SystemClock.elapsedRealtime()
    }

    /** Shows [text], or puts it into the banner on screen, and restarts the hide timer. */
    fun show(text: TypingText, iconUrl: String?, click: () -> Unit) {
        try {
            onClick = click
            if (!requested) {
                requested = true
                touched = false
                attempts = 0
                this.text = text
                this.iconUrl = iconUrl
                Utils.mainThread.removeCallbacks(watch)
                watch.run()
                // If no screen can show the banner in time, the typing is long over, so drop it then.
                if (requested && alert == null) {
                    Utils.mainThread.removeCallbacks(hideLater)
                    Utils.mainThread.postDelayed(hideLater, BANNER_MS + WAIT_MS)
                }
            } else if (!update(text, iconUrl)) {
                // Still waiting for a screen, or about to appear: it'll show the newest text.
                this.text = text
                this.iconUrl = iconUrl
            }
        } catch (error: Throwable) {
            onError("show in-app typing banner", error)
        }
    }

    /** Updates the banner on screen without showing a new one. Returns false if none is showing. */
    fun update(text: TypingText, iconUrl: String?): Boolean {
        if (!requested) return false
        this.text = text
        this.iconUrl = iconUrl
        val shown = track() ?: return false
        apply(shown)
        restartTimer()
        return true
    }

    fun hide() {
        Utils.mainThread.removeCallbacks(hideLater)
        Utils.mainThread.removeCallbacks(watch)
        if (!requested) return
        val activity = shownIn?.get()
        val shown = track()
        when {
            shown != null -> shown.d()
            // Handed to Discord a moment ago; Discord adds its view in a posted runnable.
            activity != null && SystemClock.elapsedRealtime() - shownAt < APPEAR_MS -> {
                val title = shownTitle
                Utils.mainThread.postDelayed({ find(activity, title)?.d() }, WATCH_INTERVAL_MS)
            }
        }
        forget()
    }

    /** Clears the state of our banner, which is gone or will never show. */
    private fun forget() {
        Utils.mainThread.removeCallbacks(hideLater)
        Utils.mainThread.removeCallbacks(watch)
        requested = false
        text = null
        noticeName = null
        shownIn = null
        shownTitle = null
        alert = null
    }

    private fun step() {
        val shownActivity = shownIn?.get()
        val screenOpen = shownActivity != null && !shownActivity.isFinishing && !shownActivity.isDestroyed

        val tracked = alert?.get()
        if (tracked != null) {
            if (isShowing(tracked)) return
            // Swiped away, closed, tapped, or replaced by one of Discord's own banners.
            if (screenOpen) {
                forget()
                return
            }
            // Its screen closed (Discord relaunches it after startup): show it again on the new one.
            alert = null
            shownIn = null
        } else if (shownIn != null) {
            if (screenOpen && track() != null) return
            // Discord adds its view a moment after it's shown.
            if (screenOpen && SystemClock.elapsedRealtime() - shownAt < APPEAR_MS) return
            shownIn = null
        }

        if (attempts >= MAX_ATTEMPTS) {
            hide()
            return
        }
        val front = currentActivity() as? FragmentActivity ?: return
        if (front.isFinishing || front.isDestroyed || !front.hasWindowFocus()) return
        if (SystemClock.elapsedRealtime() - resumedAt < STABLE_MS) return
        if (!onMainTab(front.supportFragmentManager)) return
        display(front)
    }

    /** Runs NoticePopup's own display code on [activity] directly, without Discord's notice queue. */
    private fun display(activity: FragmentActivity) {
        val text = text ?: return
        val name = "TypingNotifications#${++count}"
        val onBannerClick: (View) -> Unit = {
            // Discord dismisses the banner after this.
            if (noticeName == name) {
                val target = onClick
                forget()
                target()
            }
        }
        val onClose: (View) -> Unit = {}
        val function = DISPLAY.newInstance(
            onBannerClick,
            name,
            null, // No auto-dismiss from Discord; see hideLater.
            iconUrl,
            null,
            null,
            null,
            null,
            text.title,
            null,
            text.body,
            closeIcon(activity),
            null,
            onClose,
        )
        attempts++
        noticeName = name
        shownTitle = text.title
        shownIn = WeakReference(activity)
        shownAt = SystemClock.elapsedRealtime()
        alert = null
        // Called by reflection: casting it to a Kotlin function type fails, because Kotlin checks the cast
        // with its own internal function classes, which Discord ships renamed.
        DISPLAY_INVOKE.invoke(function, activity)
    }

    /** Puts the latest text and icon into [shown]. */
    private fun apply(shown: Alert) {
        val text = text ?: return
        val container = shown.layoutContainer ?: return
        val titleView = container.findViewById<TextView>(Utils.getResId("popup_title", "id")) ?: return
        val bodyView = container.findViewById<TextView>(Utils.getResId("popup_body", "id")) ?: return
        shownTitle = text.title
        titleView.text = text.title
        bodyView.text = text.body
        bodyView.visibility = if (text.body.isEmpty()) View.GONE else View.VISIBLE
        val icon = container.findViewById<ImageView>(Utils.getResId("popup_icon", "id"))
        iconUrl?.let { if (icon != null) MGImages.setImage(icon, it) }
    }

    private fun restartTimer() {
        Utils.mainThread.removeCallbacks(hideLater)
        if (!touched) Utils.mainThread.postDelayed(hideLater, BANNER_MS)
    }

    /** Finds our banner on its screen and remembers it. */
    private fun track(): Alert? {
        try {
            alert?.get()?.takeIf(::isShowing)?.let { return it }
            val activity = shownIn?.get() ?: return null
            val found = find(activity, shownTitle) ?: return null
            alert = WeakReference(found)
            // Text that changed while Discord was adding the view.
            if (text?.title != shownTitle || text?.body != found.bodyText()) apply(found)
            // The full duration counts from when the banner appears.
            restartTimer()
            // Discord's own touch listener only cancels its (disabled) countdown.
            found.layoutContainer?.setOnTouchListener { _, _ ->
                touched = true
                Utils.mainThread.removeCallbacks(hideLater)
                false
            }
            return found
        } catch (error: Throwable) {
            onError("track in-app typing banner", error)
        }
        return null
    }

    /** The banner in [activity]'s window showing [title]. */
    private fun find(activity: Activity, title: String?): Alert? {
        title ?: return null
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val titleId = Utils.getResId("popup_title", "id")
        // A plain loop: iterating an IntRange breaks on Discord's obfuscated stdlib.
        var index = decor.childCount - 1
        while (index >= 0) {
            val candidate = decor.getChildAt(index--) as? Alert ?: continue
            if (!isShowing(candidate)) continue
            val shown = candidate.layoutContainer?.findViewById<TextView>(titleId)?.text?.toString()
            if (shown == title) return candidate
        }
        return null
    }

    private fun Alert.bodyText() =
        layoutContainer?.findViewById<TextView>(Utils.getResId("popup_body", "id"))?.text?.toString()

    /** Attached and not fading out (Discord fades old banners when it shows a new one). */
    private fun isShowing(alert: Alert) = alert.parent != null && alert.windowToken != null && alert.alpha >= 1f

    /** Every main tab (home, friends, search, mentions, profile), like the screens Discord's queue allowed. */
    private fun onMainTab(manager: FragmentManager): Boolean = manager.fragments.any {
        it.isResumed && it.isVisible &&
            (it is WidgetTabsHost || it is WidgetHome || it is WidgetUserMentions || onMainTab(it.childFragmentManager))
    }

    /** Discord's own close icon, as its DM banners use, instead of the default settings gear. */
    private fun closeIcon(activity: Activity): Drawable? = try {
        val value = TypedValue()
        val attr = Utils.getResId("ic_close_24dp", "attr")
        if (attr != 0 && activity.theme.resolveAttribute(attr, value, true) && value.resourceId != 0) {
            ContextCompat.getDrawable(activity, value.resourceId)
        } else {
            null
        }
    } catch (error: Throwable) {
        null
    }

    private companion object {
        /** How long the banner stays after its last update. */
        const val BANNER_MS = 5_000L

        /** How long a requested banner may wait for a screen to show it on. */
        const val WAIT_MS = 10_000L

        /** How long a screen must stay in front before a banner is shown on it. */
        const val STABLE_MS = 1_000L

        /** How long Discord may take to add the banner's view before it's shown again. */
        const val APPEAR_MS = 3_000L
        const val MAX_ATTEMPTS = 5

        const val WATCH_INTERVAL_MS = 250L

        /**
         * The function NoticePopup.enqueue hands Discord's notice queue to show the banner. Its constructor
         * takes onClick, name, auto-dismiss seconds, icon URL, icon resource, body image URL, body image, body
         * background, title, subtitle, body, top-right icon, stickers and the top-right icon's click.
         */
        val DISPLAY by lazy {
            Class.forName("com.discord.widgets.notice.NoticePopup\$enqueue\$notice\$1").declaredConstructors.single()
                .apply { isAccessible = true }
        }

        /** Its `invoke(FragmentActivity)` (jadx lists it as `invoke2`, its own rename). */
        val DISPLAY_INVOKE by lazy {
            val methods = DISPLAY.declaringClass.methods.filter { it.name == "invoke" && it.parameterTypes.size == 1 }
            (methods.firstOrNull { it.parameterTypes[0] == FragmentActivity::class.java } ?: methods.first())
                .apply { isAccessible = true }
        }
    }
}
