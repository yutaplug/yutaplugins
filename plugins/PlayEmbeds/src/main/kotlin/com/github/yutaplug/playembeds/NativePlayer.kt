package com.github.yutaplug.playembeds

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Xml
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import com.aliucord.Utils
import com.discord.player.AppMediaPlayer
import com.discord.player.MediaSource
import com.discord.player.MediaType
import com.google.android.exoplayer2.ui.PlayerControlView
import com.google.android.exoplayer2.ui.PlayerView
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Method

/** Builds players from Discord's own ExoPlayer setup and its stock control views. */
internal object NativePlayers {
    private const val FEATURE_TAG = "PlayEmbeds"
    private const val SHOW_BUFFERING_WHEN_PLAYING = 1
    private const val STATE_BUFFERING = 2
    private const val STATE_READY = 3
    private const val SCREEN_CHECK_MS = 1000L

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

    /**
     * Whether rotating Discord's own activity can't be used for fullscreen. Before
     * Android 8 the activity has no window while it's rebuilt for a rotation (about
     * 3 seconds), so the phone goes back to portrait meanwhile and the rebuilt
     * activity asks for landscape again, rebuilding it endlessly. Fullscreen then
     * rotates in a screen of its own, or turns the player sideways as a fallback.
     */
    val turnsPlayerSideways get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

    /**
     * A video surface with ExoPlayer's stock controller (Discord's exo_player_control_view).
     * Where fullscreen turns the player sideways, it draws into a TextureView, which
     * (unlike the default SurfaceView) rotates with its view.
     */
    fun videoView(context: Context): PlayerView = (textureVideoView(context) ?: PlayerView(context, null)).apply {
        setBackgroundColor(Color.BLACK)
        setShutterBackgroundColor(Color.BLACK)
        setUseController(true)
        setControllerAutoShow(true)
        setShowBuffering(SHOW_BUFFERING_WHEN_PLAYING)
        keepScreenOnWhilePlaying(this)
    }

    /**
     * A PlayerView built with the attributes of the one in Discord's media viewer
     * (widget_media), which draws into a TextureView. Only where [turnsPlayerSideways].
     */
    private fun textureVideoView(context: Context): PlayerView? {
        if (!turnsPlayerSideways) return null
        val layoutId = Utils.getResId("widget_media", "layout")
        if (layoutId == 0) return null
        val parser = try {
            context.resources.getLayout(layoutId)
        } catch (_: Throwable) {
            return null
        }
        try {
            var event = parser.next()
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == PlayerView::class.java.name) {
                    return PlayerView(context, Xml.asAttributeSet(parser)).apply { id = View.NO_ID }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // Fall back to the default SurfaceView player.
        } finally {
            parser.close()
        }
        return null
    }

    /**
     * Keeps the screen from dimming or locking while this view's player is
     * playing (or buffering to play), like Discord's own video viewer. Android
     * only honors it while the view is attached and visible, so it follows the
     * view into fullscreen and ends once the row is scrolled away or recycled.
     */
    private fun keepScreenOnWhilePlaying(view: PlayerView) {
        val check = object : Runnable {
            override fun run() {
                val awake = wantsScreenOn(view.player)
                if (view.keepScreenOn != awake) view.keepScreenOn = awake
                view.postDelayed(this, SCREEN_CHECK_MS)
            }
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeCallbacks(check)
                    v.post(check)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    v.removeCallbacks(check)
                    v.keepScreenOn = false
                }
            },
        )
    }

    /** `Player.getPlayWhenReady()` (`y1.j()`) while the playback state (`y1.y()`) is buffering or ready. */
    private fun wantsScreenOn(player: Any?): Boolean = try {
        if (player == null || player.javaClass.getMethod("j").invoke(player) != true) {
            false
        } else {
            val state = player.javaClass.getMethod("y").invoke(player) as? Int
            state == STATE_BUFFERING || state == STATE_READY
        }
    } catch (_: Throwable) {
        false
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

    /** Shows [player]'s current playback in [playerView] without reloading it, as `AppMediaPlayer.a` binds views. */
    fun bind(player: AppMediaPlayer, playerView: PlayerView, controlView: PlayerControlView?) {
        playerView.setPlayer(player.f)
        controlView?.setPlayer(player.f)
    }

    /** `SimpleExoPlayer.setPlayWhenReady(true)`, obfuscated as `k2.u(boolean)`. */
    fun resume(player: AppMediaPlayer) {
        try {
            val exo: Any = player.f
            exo.javaClass.getMethod("u", Boolean::class.javaPrimitiveType).invoke(exo, true)
        } catch (_: Throwable) {
            // Released, or the method moved.
        }
    }

    /** `Player.getCurrentPosition()`, obfuscated as `y1.T()`. */
    fun position(player: AppMediaPlayer): Long = try {
        val exo: Any = player.f
        exo.javaClass.getMethod("T").invoke(exo) as? Long ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** `Player.getPlaybackState()`, obfuscated as `y1.y()`: 1 idle, 2 buffering, 3 ready, 4 ended. */
    fun playbackState(player: AppMediaPlayer): Int = try {
        val exo: Any = player.f
        exo.javaClass.getMethod("y").invoke(exo) as? Int ?: -1
    } catch (_: Throwable) {
        -1
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
