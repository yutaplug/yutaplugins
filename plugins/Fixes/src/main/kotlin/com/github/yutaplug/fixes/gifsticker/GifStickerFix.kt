package com.github.yutaplug.fixes.gifsticker

import android.content.Context
import android.view.View
import com.aliucord.patcher.Hook
import com.discord.api.sticker.BaseSticker
import com.discord.stores.StoreStream
import com.discord.views.sticker.StickerView
import com.facebook.drawee.controller.AbstractDraweeController
import com.facebook.drawee.controller.AbstractDraweeControllerBuilder
import com.github.yutaplug.fixes.Fix
import java.util.Collections
import java.util.WeakHashMap

/**
 * Aliucord shows GIF stickers (API format 4, unknown to Discord 126.21) through the static PNG
 * path with a `.gif` URL, so Fresco autoplays them and the sticker animation setting is ignored.
 */
internal class GifStickerFix :
    Fix(
        "GifStickerAnimationFix",
        "GIF sticker animation",
        "Makes GIF stickers follow the sticker animation setting in Text & Images instead of always animating.",
    ) {
    /** Sticker images that got this fix's long-press listener, so a reused view can drop it. */
    private val longPressViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun start(context: Context) {
        patcher.patch(
            StickerView::class.java,
            "d",
            arrayOf(BaseSticker::class.java, Integer::class.java),
            Hook { param ->
                val view = param.thisObject as StickerView
                val sticker = param.args[0] as? BaseSticker ?: return@Hook
                val image = view.j.b
                if (image in longPressViews) {
                    longPressViews.remove(image)
                    image.setOnLongClickListener(null)
                }
                if (sticker.b() != ".gif") return@Hook

                val mode = param.args[1] as Int? ?: StoreStream.getUserSettings().stickerAnimationSettings
                if (mode == ALWAYS_ANIMATE) return@Hook

                @Suppress("UNCHECKED_CAST")
                val controller = image.controller as? AbstractDraweeController<Any?, Any?> ?: return@Hook
                // Discord's builder adds this listener to start animations once the image loads.
                controller.A(AbstractDraweeControllerBuilder.a)
                // A memory-cache hit loads (and starts) the image before this hook runs.
                controller.c()?.takeIf { it.isRunning }?.stop()

                if (mode == ANIMATE_ON_INTERACTION) {
                    longPressViews.add(image)
                    image.setOnLongClickListener {
                        val animatable = (image.controller as? AbstractDraweeController<*, *>)?.c()
                        if (animatable != null) {
                            if (animatable.isRunning) animatable.stop() else animatable.start()
                        }
                        true
                    }
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        longPressViews.toList().forEach { it.setOnLongClickListener(null) }
        longPressViews.clear()
    }

    private companion object {
        // StoreUserSettings sticker animation values.
        const val ALWAYS_ANIMATE = 0
        const val ANIMATE_ON_INTERACTION = 1
    }
}
