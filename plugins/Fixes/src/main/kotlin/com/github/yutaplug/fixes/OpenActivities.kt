package com.github.yutaplug.fixes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.aliucord.Utils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Tracks the app's live activities, so a fix can refresh them when it is toggled. */
internal object OpenActivities : Application.ActivityLifecycleCallbacks {
    private val activities = WeakHashMap<Activity, Boolean>()
    private var application: Application? = null

    @Volatile private var resumed: WeakReference<Activity>? = null

    /** The activity in front, or Aliucord's app activity if none has resumed since [start]. */
    fun front(): Activity? {
        val activity = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: current()
        return activity?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    fun start() {
        if (application != null) return
        val app = Utils.appContext.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(this)
        application = app
        current()?.let { activities[it] = true }
    }

    fun stop() {
        application?.unregisterActivityLifecycleCallbacks(this)
        application = null
        activities.clear()
    }

    /** Live activities, excluding the one in front (normally the settings page being used). */
    fun inBackground(): List<Activity> {
        val front = current()
        return activities.keys.filter { it !== front && !it.isFinishing }
    }

    private fun current(): Activity? = try {
        Utils.appActivity
    } catch (_: Throwable) {
        null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        activities[activity] = true
    }

    override fun onActivityDestroyed(activity: Activity) {
        activities.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
