package com.github.yutaplug.playembeds

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import com.discord.player.AppMediaPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.EmbedEntry
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Neither the key nor the value keeps a recycled embed row alive. */
internal class WeakValueMap<K : Any, V : Any> {
    private val entries = WeakHashMap<K, WeakReference<V>>()

    operator fun get(key: K): V? = entries[key]?.get()
    operator fun set(key: K, value: V) { entries[key] = WeakReference(value) }
    fun remove(key: K): V? = entries.remove(key)?.get()
    fun containsKey(key: K): Boolean = get(key) != null
    val keys: Set<K> get() = entries.keys
    val values: List<V> get() = entries.values.mapNotNull { it.get() }
    fun clear() = entries.clear()
}

/** An inline ExoPlayer that replaced (part of) an embed's preview. */
internal data class NativePlayerState(
    val player: AppMediaPlayer,
    /** The view added to [parent]; its parent changing means the row was rebuilt. */
    val root: View,
    val parent: ViewGroup,
    val previousVisibility: List<Pair<View, Int>>,
    /** Hide remembered views with GONE (true) or only turn visible ones INVISIBLE. */
    val forceGone: Boolean,
    /** The embed URL for hosted players, the media URL for direct videos. */
    val url: String,
    val extraViews: List<View>,
    val previousMinimumWidth: Int,
    val replacement: HostedEmbedReplacement? = null,
) {
    /** What's playing (updated by a YouTube quality switch), its provider and the embed title. */
    var media: ResolvedMedia? = null
    var provider: HostedProvider? = null
    var title: String? = null
}

/** Where a fullscreen player came from, and the window state to restore. */
internal class FullscreenState(
    val activity: Activity,
    val overlay: FrameLayout,
    val button: ImageButton,
    val parent: ViewGroup,
    val index: Int,
    val layoutParams: ViewGroup.LayoutParams,
    val previousSystemUiVisibility: Int,
    val wasFullscreen: Boolean,
    val previousOrientation: Int,
)

/**
 * A video shown fullscreen with the screen really turned to landscape. Discord
 * rebuilds its activity when the screen rotates, so the session keeps [player]
 * running on its own and shows it in a new overlay in each rebuilt activity.
 * [rowKey] (message id, embed index) finds the embed to return it to afterwards.
 */
internal class RotatedSession(
    val player: AppMediaPlayer,
    val identity: String,
    val provider: HostedProvider?,
    val title: String?,
    val media: ResolvedMedia,
    val rowKey: Pair<Long, Int>?,
    /** The orientation the activity asked for before fullscreen, restored on exit. */
    val previousOrientation: Int,
) {
    var activity: Activity? = null
    var overlay: View? = null
    var playerView: PlayerView? = null
    var previousSystemUiVisibility = 0
    var wasFullscreen = false

    /** Whether it was playing when fullscreen closed, to carry on once it's back in its embed. */
    var wasPlaying = true

    companion object {
        const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

internal data class HostedEmbedReplacement(
    val wrapper: FrameLayout,
    val originalParent: ViewGroup,
    val originalIndex: Int,
    val originalLayoutParams: ViewGroup.LayoutParams,
    val originalCard: ViewGroup,
    val originalId: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

internal class SearchEmbedClick(handler: WidgetChatListAdapter.EventHandler, val entry: EmbedEntry) {
    private val handlerRef = WeakReference(handler)
    val handler: WidgetChatListAdapter.EventHandler? get() = handlerRef.get()
}
