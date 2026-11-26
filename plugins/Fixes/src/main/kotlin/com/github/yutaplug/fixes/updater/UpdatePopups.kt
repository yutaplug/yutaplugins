package com.github.yutaplug.fixes.updater

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.discord.widgets.notice.NoticePopup
import com.github.yutaplug.fixes.OpenActivities
import java.lang.ref.WeakReference

/**
 * The updater's in-app notices: Discord's own popup (the one for message notifications) at the top of the
 * screen, which can be swiped away or closed with its X, and runs an action when tapped.
 *
 * - Discord closes the popup after a delay with a ValueAnimator. With Android's animations off (Battery saver
 *   on many phones, or "Remove animations"), animators end at once, so the popup closed as soon as it opened.
 *   So it's shown without Discord's delay, and closed here with a plain timer instead.
 * - It skips Discord's notice queue, which shows one notice at a time when its navigation updates, and could
 *   leave it waiting behind Discord's own notices.
 * - Discord relaunches the screen soon after startup, taking a popup shown on it along. So it waits until a
 *   screen has stayed in front for a moment, and shows it again if that screen closes before it was dismissed.
 */
internal object UpdatePopups {
    private const val SHOW_FOR_MS = 15_000L

    private class Popup(val title: String, val body: String, val onClick: (View) -> Unit) {
        var name = ""
        var shownIn: WeakReference<Activity>? = null
        var shownAt = 0L

        /** When its view was first seen on screen, or 0; it's added a moment after it's shown. */
        var appearedAt = 0L
        var attempts = 0
        val queuedAt = System.currentTimeMillis()
    }

    // java.util, not Kotlin's ArrayDeque: Discord ships a stripped copy of that one.
    private val popups = java.util.ArrayDeque<Popup>()
    private var watching = false
    private var stableFront: WeakReference<Activity>? = null
    private var stableSince = 0L

    fun show(title: String, body: String, onClick: (View) -> Unit) {
        if (!Updater.setting(Updater.SHOW_POPUPS, true)) {
            Updater.logger.info("Popup \"$title\" not shown: update popups are turned off")
            return
        }
        Updater.logger.info("Popup \"$title\" queued")
        Utils.mainThread.post {
            popups.add(Popup(title, body, onClick))
            if (!watching) {
                watching = true
                watch()
            }
        }
    }

    private fun watch() {
        val popup = popups.peekFirst()
        if (popup == null) {
            watching = false
            return
        }
        try {
            step(popup)
        } catch (e: Throwable) {
            Updater.logger.error("Could not show a popup", e)
            popups.pollFirst()
        }
        Utils.mainThread.postDelayed({ watch() }, 500L)
    }

    private fun step(popup: Popup) {
        val shownIn = popup.shownIn
        if (shownIn != null) {
            val activity = shownIn.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                if (isOnScreen(activity, popup)) {
                    val now = System.currentTimeMillis()
                    if (popup.appearedAt == 0L) {
                        popup.appearedAt = now
                        Updater.logger.info("Popup \"${popup.title}\" is on screen")
                    } else if (now - popup.appearedAt > SHOW_FOR_MS) {
                        // Discord's own timer is off (see above), so close it here.
                        dismiss(popup)
                    }
                    return
                }
                // Gone after it appeared: swiped away, closed, tapped, or closed by the timer.
                if (popup.appearedAt != 0L) {
                    Updater.logger.info("Popup \"${popup.title}\" closed")
                    popups.pollFirst()
                    return
                }
                // Still waiting for its view to be added.
                if (System.currentTimeMillis() - popup.shownAt < 3000L) return
                Updater.logger.info("Popup \"${popup.title}\" never appeared, showing it again")
            } else {
                Updater.logger.info("Popup \"${popup.title}\": its screen closed before it was dismissed, showing it again")
            }
            popup.shownIn = null
            popup.appearedAt = 0L
        }
        if (popup.attempts >= 5 || System.currentTimeMillis() - popup.queuedAt > 10 * 60_000L) {
            Updater.logger.info("Gave up on popup \"${popup.title}\" after ${popup.attempts} attempts")
            popups.pollFirst()
            return
        }
        val front = OpenActivities.front() as? FragmentActivity
        if (front == null || front.isFinishing || front.isDestroyed || !front.hasWindowFocus()) {
            stableFront = null
            return
        }
        if (stableFront?.get() !== front) {
            stableFront = WeakReference(front)
            stableSince = System.currentTimeMillis()
            return
        }
        if (System.currentTimeMillis() - stableSince < 1500L) return
        popup.attempts++
        popup.name = "fixes-updater-${System.nanoTime()}"
        val shown = display(front, popup)
        Updater.logger.info("Showing popup \"${popup.title}\" on ${front.javaClass.simpleName} (attempt ${popup.attempts}): $shown")
        // Watched even if Discord said no, so a popup that didn't attach is retried after the grace period.
        popup.shownIn = WeakReference(front)
        popup.shownAt = System.currentTimeMillis()
    }

    /** Whether [popup]'s view is in [activity]'s window, found by its title. */
    private fun isOnScreen(activity: Activity, popup: Popup): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        var found = false
        fun visit(view: View) {
            if (found || view.visibility != View.VISIBLE) return
            if (view is TextView && view.text?.toString() == popup.title) found = true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        // The popup is added straight to the window, beside the screen's own content, which is skipped.
        for (i in 0 until decor.childCount) {
            val child = decor.getChildAt(i)
            if (child.findViewById<View>(android.R.id.content) == null) visit(child)
        }
        return found
    }

    /** Runs NoticePopup's own display code on [activity] directly, without Discord's notice queue. */
    private fun display(activity: FragmentActivity, popup: Popup): Boolean {
        val close = UpdaterUi.drawable(activity, "ic_close_grey_24dp", null)
        val onClick: (View) -> Unit = popup.onClick
        val onClose: (View) -> Unit = {}
        val function = DISPLAY.newInstance(
            onClick,
            popup.name,
            // No auto-dismiss: Discord's would close it at once with animations off. Closed by the timer above.
            null,
            null,
            activity.applicationInfo.icon.takeIf { it != 0 },
            null,
            null,
            null,
            popup.title,
            null,
            popup.body,
            close,
            null,
            onClose,
        )
        // Called by reflection: casting it to a Kotlin function type fails, because Kotlin checks the cast
        // with its own internal function classes, which Discord ships renamed.
        return DISPLAY_INVOKE.invoke(function, activity) == true
    }

    /** Closes [popup] the way its X does. */
    private fun dismiss(popup: Popup) {
        try {
            // access$dismiss is static and takes the instance first; dismiss is called on the instance.
            if (java.lang.reflect.Modifier.isStatic(DISMISS.modifiers)) {
                DISMISS.invoke(null, NoticePopup.INSTANCE, popup.name)
            } else {
                DISMISS.invoke(NoticePopup.INSTANCE, popup.name)
            }
        } catch (e: Throwable) {
            Updater.logger.error("Could not close a popup", e)
            popups.pollFirst()
        }
    }

    /**
     * The function NoticePopup.enqueue hands Discord's notice queue to show the popup. Its constructor takes
     * onClick, name, auto-dismiss seconds, icon URL, icon resource, body image URL, body image, body
     * background, title, subtitle, body, top-right icon, stickers and the top-right icon's click.
     */
    private val DISPLAY by lazy {
        Class.forName("com.discord.widgets.notice.NoticePopup\$enqueue\$notice\$1").declaredConstructors.single()
            .apply { isAccessible = true }
    }

    /**
     * Its `invoke(FragmentActivity)`. jadx lists it as `invoke2`, but that's jadx's own rename ("renamed from:
     * invoke"); on the device it's `invoke`, next to the generic `invoke(Object)` bridge, which works too.
     */
    private val DISPLAY_INVOKE by lazy {
        val methods = DISPLAY.declaringClass.methods.filter { it.name == "invoke" && it.parameterTypes.size == 1 }
        (methods.firstOrNull { it.parameterTypes[0] == FragmentActivity::class.java } ?: methods.first())
            .apply { isAccessible = true }
    }

    /** NoticePopup's private `dismiss(String)`, which its X and taps use, through its static accessor. */
    private val DISMISS by lazy {
        val accessor = NoticePopup::class.java.declaredMethods.firstOrNull {
            it.name == "access\$dismiss" && it.parameterTypes.size == 2
        }
        if (accessor != null) {
            accessor.apply { isAccessible = true }
        } else {
            NoticePopup::class.java.getDeclaredMethod("dismiss", String::class.java).apply { isAccessible = true }
        }
    }
}
