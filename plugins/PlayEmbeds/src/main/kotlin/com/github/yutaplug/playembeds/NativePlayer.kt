package com.github.yutaplug.playembeds

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewParent
import android.widget.FrameLayout
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import java.lang.reflect.Method

/** Builds players from Discord's own ExoPlayer setup and its stock control views. */
internal object NativePlayers {
    private const val FEATURE_TAG = "PlayEmbeds"
    private const val SHOW_BUFFERING_WHEN_PLAYING = 1

    private var factory: Method? = null

    /** `AppMediaPlayerFactory.create(Context)`, obfuscated as `b.a.p.i.a`. */
    fun init() {
        factory = Class.forName("b.a.p.i").getMethod("a", Context::class.java)
    }

    fun create(context: Context): AppMediaPlayer? = try {
        factory?.invoke(null, context) as? AppMediaPlayer
    } catch (_: Throwable) {
        null
    }

    /** A video surface with ExoPlayer's stock controller (Discord's exo_player_control_view). */
    fun videoView(context: Context): PlayerView = PlayerView(context, null).apply {
        setBackgroundColor(Color.BLACK)
        setShutterBackgroundColor(Color.BLACK)
        setUseController(true)
        setControllerAutoShow(true)
        setShowBuffering(SHOW_BUFFERING_WHEN_PLAYING)
    }

    /** A control bar that stays visible, for audio shown over its artwork. */
    fun controlBar(context: Context): PlayerControlView = PlayerControlView(context, null).apply {
        setShowTimeoutMs(0)
    }

    /** `AppMediaPlayer.prepare(source, playWhenReady, loop, startMs, view, controls)`. */
    fun play(
        player: AppMediaPlayer,
        uri: String,
        playerView: PlayerView,
        controlView: PlayerControlView?,
        positionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        val source = MediaSource(Uri.parse(uri), FEATURE_TAG, MediaType.VIDEO)
        player.a(source, playWhenReady, false, positionMs, playerView, controlView)
        player.d(1f)
    }

    /** `Player.getCurrentPosition()`, obfuscated as `y1.T()`. */
    fun position(player: AppMediaPlayer): Long = try {
        val exo: Any = player.f
        exo.javaClass.getMethod("T").invoke(exo) as? Long ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** `Player.getPlayWhenReady()`, obfuscated as `y1.j()`. */
    fun isPlaying(player: AppMediaPlayer): Boolean = try {
        val exo: Any = player.f
        exo.javaClass.getMethod("j").invoke(exo) as? Boolean ?: true
    } catch (_: Throwable) {
        true
    }

    /** `SimpleExoPlayer.setPlayWhenReady(false)`, obfuscated as `k2.u(boolean)`. */
    fun pause(player: AppMediaPlayer) {
        try {
            val exo: Any = player.f
            exo.javaClass.getMethod("u", Boolean::class.javaPrimitiveType).invoke(exo, false)
        } catch (_: Throwable) {
            // Released, or the method moved; leave playback alone.
        }
    }

    fun release(player: AppMediaPlayer) {
        try {
            player.c()
        } catch (_: Throwable) {
            // Already released.
        }
    }
}

/**
 * Black full-window container for a fullscreen player. Key events reach the
 * focused player controls through this view, so Back is caught here before
 * the activity would navigate away.
 */
internal class FullscreenOverlay(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Some Discord/device-specific parent containers don't reliably propagate
 * disallow-intercept through the hierarchy. Request it from every ancestor so
 * seekbar drags stay with the player instead of becoming panel/chat swipes.
 */
internal class TouchGuardLayout(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val finished = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        requestAncestorsDisallowIntercept(!finished)
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            // Reassert after the child's own dispatch; release only once the gesture ends.
            requestAncestorsDisallowIntercept(!finished)
        }
    }

    private fun requestAncestorsDisallowIntercept(disallow: Boolean) {
        var ancestor: ViewParent? = parent
        while (ancestor != null) {
            ancestor.requestDisallowInterceptTouchEvent(disallow)
            ancestor = (ancestor as? android.view.View)?.parent
        }
    }
}
