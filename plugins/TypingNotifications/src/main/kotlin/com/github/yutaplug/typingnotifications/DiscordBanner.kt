package com.github.yutaplug.typingnotifications

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.discord.app.AppComponent
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.images.MGImages
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.notice.NoticePopup
import com.discord.widgets.tabs.WidgetTabsHost
import com.discord.widgets.user.WidgetUserMentions
import java.lang.ref.WeakReference
import b.p.a.b as Alert
import d0.e0.c as KClass
import d0.z.d.a0 as Reflection

/**
 * One Discord in-app banner (NoticePopup) for everyone typing. Discord's queue shows banners one at a
 * time, each for its full duration, so separate banners would pile up and show late. Instead the banner
 * on screen is edited in place, and a new one is only requested when none is showing.
 *
 * Discord's own countdown is off for these banners; [hideLater] hides them, and it can always do so:
 * a banner still queued is dropped, and one on screen is hidden even if it could not be tracked.
 * Main thread only.
 */
internal class DiscordBanner(
    private val currentActivity: () -> Activity?,
    private val onError: (String, Throwable) -> Unit,
) {
    /** Our latest requested notice, and whether Discord has put it on screen. */
    private var noticeName: String? = null
    private var noticeShown = false
    private var count = 0L

    /** The title of our banner, used to find its view without grabbing Discord's own banners. */
    private var title: String? = null
    private var alert: WeakReference<Alert>? = null

    /** Like Discord's own banners, a touched banner stays until dismissed. */
    private var touched = false
    private var onClick: () -> Unit = {}

    private val hideLater = Runnable { hide() }

    /** Discord adds the banner a second or more after the request; keep looking for it until it shows. */
    private val watch = object : Runnable {
        var tries = 0

        override fun run() {
            if (noticeName == null || track() != null || ++tries > WATCH_TRIES) return
            Utils.mainThread.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    /** Every main tab (home, friends, search, mentions, profile), not just Discord's default home tab. */
    @Suppress("UNCHECKED_CAST")
    private val screens = listOf(WidgetTabsHost::class.java, WidgetHome::class.java, WidgetUserMentions::class.java)
        .map { Reflection.getOrCreateKotlinClass(it) as KClass<out AppComponent> }

    fun patch(patcher: PatcherAPI) {
        val show = Class.forName("com.discord.widgets.notice.NoticePopup\$enqueue\$notice\$1")
        val nameField = show.getDeclaredField("\$noticeName").apply { isAccessible = true }
        patcher.patch(show, "invoke2", arrayOf(FragmentActivity::class.java), Hook { frame ->
            val name = nameField.get(frame.thisObject)
            Utils.mainThread.post {
                if (name != noticeName || noticeShown) return@post
                noticeShown = true
                // The full duration counts from when the banner appears, not from the request.
                restartTimer()
                // Discord adds the Alert view in a posted runnable; this one runs after it.
                track(frame.args[0] as? Activity)
            }
        })
    }

    /** Shows [text], or puts it into the banner on screen, and restarts the hide timer. */
    fun show(text: TypingText, iconUrl: String?, click: () -> Unit) {
        try {
            onClick = click
            if (update(text, iconUrl)) return
            // Nothing on screen: replace any of our banners still waiting in Discord's queue.
            drop()
            val name = "${StoreNotices.IN_APP_NOTICE_TAG}#TypingNotifications#${++count}"
            noticeName = name
            title = text.title
            touched = false
            NoticePopup.INSTANCE.enqueue(
                name, text.title, null, text.body, null, null, null, null, iconUrl, null, null,
                null, // No countdown from Discord; see hideLater.
                screens, { _ -> },
                { _ ->
                    // Discord dismisses the banner after this.
                    val target = onClick
                    if (noticeName == name) forget()
                    target()
                },
            )
            Utils.mainThread.removeCallbacks(watch)
            watch.tries = 0
            Utils.mainThread.postDelayed(watch, WATCH_INTERVAL_MS)
            // Discord may hold the banner back while another one shows. If it never appears in time,
            // the typing is long over, so drop it then.
            Utils.mainThread.removeCallbacks(hideLater)
            Utils.mainThread.postDelayed(hideLater, BANNER_MS + QUEUE_WAIT_MS)
        } catch (error: Throwable) {
            onError("show in-app typing banner", error)
        }
    }

    /** Updates the banner on screen without showing a new one. Returns false if none is showing. */
    fun update(text: TypingText, iconUrl: String?): Boolean {
        val shown = track() ?: return false
        val container = shown.layoutContainer ?: return false
        val titleView = container.findViewById<TextView>(Utils.getResId("popup_title", "id")) ?: return false
        val bodyView = container.findViewById<TextView>(Utils.getResId("popup_body", "id")) ?: return false
        title = text.title
        titleView.text = text.title
        bodyView.text = text.body
        bodyView.visibility = if (text.body.isEmpty()) View.GONE else View.VISIBLE
        val icon = container.findViewById<ImageView>(Utils.getResId("popup_icon", "id"))
        if (icon != null && iconUrl != null) MGImages.setImage(icon, iconUrl)
        restartTimer()
        return true
    }

    fun hide() {
        Utils.mainThread.removeCallbacks(hideLater)
        Utils.mainThread.removeCallbacks(watch)
        val name = noticeName ?: return
        val tracked = alert
        when {
            // Hiding marks the notice seen, which lets Discord's queue move on. A tracked banner that
            // is no longer showing was already swiped away or replaced.
            tracked != null -> tracked.get()?.takeIf(::isShowing)?.d()
            // On screen but never found: Discord's dismiss fades the banner it showed last, which is ours.
            noticeShown -> NoticePopup.`access$dismiss`(NoticePopup.INSTANCE, name)
            // Still queued: typing already ended, so never show it.
            else -> StoreStream.getNotices().markSeen(name, System.currentTimeMillis())
        }
        forget()
    }

    /** Clears the state of our banner, which Discord has dismissed or will never show. */
    private fun forget() {
        Utils.mainThread.removeCallbacks(hideLater)
        Utils.mainThread.removeCallbacks(watch)
        noticeName = null
        noticeShown = false
        alert = null
        title = null
    }

    private fun drop() {
        val name = noticeName ?: return
        StoreStream.getNotices().markSeen(name, System.currentTimeMillis())
        forget()
    }

    private fun restartTimer() {
        Utils.mainThread.removeCallbacks(hideLater)
        if (!touched) Utils.mainThread.postDelayed(hideLater, BANNER_MS)
    }

    /** Finds our banner on screen by its title and remembers it. */
    private fun track(activity: Activity? = currentActivity()): Alert? {
        try {
            alert?.get()?.takeIf(::isShowing)?.let { return it }
            val title = title ?: return null
            val decor = activity?.window?.decorView as? ViewGroup ?: return null
            val titleId = Utils.getResId("popup_title", "id")
            // A plain loop: iterating an IntRange breaks on Discord's obfuscated stdlib.
            var index = decor.childCount - 1
            while (index >= 0) {
                val candidate = decor.getChildAt(index--) as? Alert ?: continue
                if (!isShowing(candidate)) continue
                val shown = candidate.layoutContainer?.findViewById<TextView>(titleId)?.text?.toString()
                if (shown != title) continue
                alert = WeakReference(candidate)
                if (!noticeShown) {
                    noticeShown = true
                    restartTimer()
                }
                Utils.mainThread.removeCallbacks(watch)
                // Discord's own touch listener only cancels its (disabled) countdown.
                candidate.layoutContainer?.setOnTouchListener { _, _ ->
                    touched = true
                    Utils.mainThread.removeCallbacks(hideLater)
                    false
                }
                return candidate
            }
        } catch (error: Throwable) {
            onError("track in-app typing banner", error)
        }
        return null
    }

    /** Attached and not fading out (Discord fades old banners when it shows a new one). */
    private fun isShowing(alert: Alert) = alert.parent != null && alert.windowToken != null && alert.alpha >= 1f

    private companion object {
        /** How long the banner stays after its last update. */
        const val BANNER_MS = 5_000L

        /** How long a requested banner may wait in Discord's queue. */
        const val QUEUE_WAIT_MS = 10_000L

        /** Look for a requested banner every 250 ms for up to 30 seconds. */
        const val WATCH_INTERVAL_MS = 250L
        const val WATCH_TRIES = 120
    }
}
