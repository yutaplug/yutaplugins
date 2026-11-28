package com.github.yutaplug.fixes.videodecoder

import android.content.Context
import android.media.MediaCrypto
import com.aliucord.patcher.PreHook
import com.github.yutaplug.fixes.Fix
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import java.lang.reflect.Field

/**
 * Discord 126.21's ExoPlayer renderers are built with decoder fallback off (`b.i.a.c.b1` passes `false` for
 * `enableDecoderFallback`), so `MediaCodecRenderer.c0` (maybeInitCodecWithFallback) only tries the first decoder
 * for a format, usually the hardware one. When that decoder can't handle the video, e.g. a tall 1080x2340 phone
 * screen recording past its size limit (`c2.qti.avc.decoder` failing in `MediaCodec.configure` with
 * NO_EXCEEDS_CAPABILITIES), playback fails, and the inline player just keeps spinning. Newer Discord falls back to
 * the next decoder (Android's software one), which plays it.
 *
 * This turns the renderer's fallback flag (`x`) on just before it picks a decoder, so the remaining decoders are
 * tried in order after the first one fails. Videos the hardware decoder handles still use it, since it stays first.
 * Hooking `c0` (a large public method) rather than the constructor avoids ART inlining the super constructor call.
 */
internal class VideoDecoderFix :
    Fix(
        "VideoDecoderFallback",
        "Videos loading forever",
        "Tries the phone's other video decoders when the first one can't play a video. Fixes some videos (often " +
            "tall, high-resolution screen recordings) loading forever.",
    ) {
    private var fallbackField: Field? = null

    override fun start(context: Context) {
        val field = MediaCodecRenderer::class.java.getDeclaredField("x").apply { isAccessible = true }
        fallbackField = field
        patcher.patch(
            MediaCodecRenderer::class.java.getDeclaredMethod(
                "c0",
                MediaCrypto::class.java,
                Boolean::class.javaPrimitiveType,
            ),
            PreHook { param ->
                try {
                    val renderer = param.thisObject
                    if (!field.getBoolean(renderer)) field.setBoolean(renderer, true)
                } catch (e: Throwable) {
                    logger.error("Could not enable decoder fallback", e)
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        fallbackField = null
    }
}
