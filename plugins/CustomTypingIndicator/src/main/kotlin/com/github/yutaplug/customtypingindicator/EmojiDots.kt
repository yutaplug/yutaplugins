package com.github.yutaplug.customtypingindicator

import android.content.Context
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.Interpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.LinearLayout
import com.aliucord.utils.DimenUtils
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import kotlin.math.PI
import kotlin.math.sin

/** Up to three emojis that replace the typing dots, animated in turn like the dots are. */
class EmojiDots(context: Context) : LinearLayout(context) {
    private val size = DimenUtils.dpToPx(16)
    private var animating = false
    private var bound: TypingStyle? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    fun bind(style: TypingStyle) {
        if (style == bound && animating) return
        stop()
        bound = style
        while (childCount > style.emojis.size) removeViewAt(childCount - 1)
        while (childCount < style.emojis.size) {
            addView(
                SimpleDraweeView(context),
                LayoutParams(size, size).apply { marginEnd = DimenUtils.dpToPx(2) },
            )
        }
        style.emojis.forEachIndexed { index, emoji ->
            val view = getChildAt(index) as SimpleDraweeView
            view.contentDescription = emoji.unicode ?: "emoji"
            MGImages.setImage(view, imageUri(context, emoji, size))
        }
        start(style.animation)
    }

    /**
     * Uses view animations like Discord's own TypingDot, so the emojis move whenever the dots would; animators
     * are skipped entirely when the system animator duration scale is off.
     */
    private fun start(animation: Int) {
        val lift = DimenUtils.dpToPx(4).toFloat()
        for (index in 0 until childCount) {
            // Each emoji runs the same loop shifted in phase, so the three stay staggered on every repeat.
            val phase = index * STAGGER
            val anim: Animation = when (animation) {
                TypingStyle.ANIMATION_PULSE -> ScaleAnimation(
                    1f, 1.25f, 1f, 1.25f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f,
                ).apply { interpolator = Interpolator { hump(it, phase) } }
                TypingStyle.ANIMATION_RING -> RotateAnimation(
                    0f, 15f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f,
                ).apply { interpolator = Interpolator { shake(it, phase) } }
                else -> TranslateAnimation(0f, 0f, 0f, -lift)
                    .apply { interpolator = Interpolator { hump(it, phase) } }
            }
            anim.duration = DURATION
            anim.repeatCount = Animation.INFINITE
            getChildAt(index).startAnimation(anim)
        }
        animating = true
    }

    fun stop() {
        cancelAnimations()
        bound = null
    }

    private fun cancelAnimations() {
        for (index in 0 until childCount) getChildAt(index).clearAnimation()
        animating = false
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val style = bound
        if (style != null && !animating && visibility == VISIBLE) start(style.animation)
    }

    companion object {
        private const val DURATION = 1200L
        private const val STAGGER = 0.125f

        /** Fraction of each loop spent moving; the rest is a pause, like the native dots. */
        private const val ACTIVE = 0.5f

        /** Where this emoji is in its loop, from 0 to 1, after shifting by [phase]. */
        private fun cycle(time: Float, phase: Float): Float {
            val shifted = time - phase
            return if (shifted < 0f) shifted + 1f else shifted
        }

        /** Rises to 1 and back to 0 during the active part of the loop: a bounce or a pulse. */
        private fun hump(time: Float, phase: Float): Float {
            val t = cycle(time, phase)
            return if (t >= ACTIVE) 0f else sin(PI * t / ACTIVE).toFloat()
        }

        /** Swings back and forth with a dying amplitude, like a bell: -1 to 1 of the 15° rotation. */
        private fun shake(time: Float, phase: Float): Float {
            val t = cycle(time, phase)
            if (t >= ACTIVE) return 0f
            val progress = t / ACTIVE
            return (-sin(4 * PI * progress) * (1 - progress)).toFloat()
        }

        fun imageUri(context: Context, emoji: TypingEmoji, size: Int): String {
            val id = emoji.customId
            // The CDN rejects sizes outside its fixed list, so round up the way Discord does.
            if (id != null) return ModelEmojiCustom.getImageUri(id, emoji.animated, IconUtils.getMediaProxySize(size))
            val name = emoji.unicode ?: ""
            val known = runCatching { StoreStream.getEmojis().unicodeEmojiSurrogateMap[name] }.getOrNull()
            val codePoints = mutableListOf<String>()
            var index = 0
            while (index < name.length) {
                val codePoint = Character.codePointAt(name, index)
                codePoints += Integer.toHexString(codePoint)
                index += Character.charCount(codePoint)
            }
            val native = ModelEmojiUnicode.getImageUri(known?.codePoints ?: codePoints.joinToString("_"), context)
            if (native != "res:///0") return native
            // Newer emoji may not exist in 126.21's bundled Twemoji resources.
            val assetCodePoints = if ("200d" in codePoints) codePoints else codePoints.filter { it != "fe0f" }
            return "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.3/assets/72x72/${assetCodePoints.joinToString("-")}.png"
        }
    }
}
