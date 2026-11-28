package com.github.yutaplug.fixes.mediadim

import android.content.Context
import android.view.View
import com.aliucord.Utils
import com.aliucord.patcher.Hook
import com.discord.widgets.chat.list.InlineMediaView
import com.github.yutaplug.fixes.Fix
import kotlin.coroutines.Continuation

/**
 * Images and videos in search results (and other screens over the home screen) were dimmed in DMs.
 * InlineMediaView darkens itself with its `opacity_shim` while Home's left or right panel is open, which
 * is meant for the chat behind an open panel. A DM's search opens from its right panel, so the panel is
 * still open behind the search screen and every result's media is darkened. Keep the shim only in the
 * home screen's chat.
 */
internal class MediaDimFix :
    Fix(
        "MediaDimFix",
        "Darker images in search",
        "Fixes images and videos in search results looking darker when searching in DMs.",
    ) {
    override fun start(context: Context) {
        val collector = Class.forName(
            "com.discord.widgets.chat.list.InlineMediaView\$onViewAttachedToWindow\$1\$invokeSuspend\$\$inlined\$collect\$1",
        )
        val outerField = collector.getDeclaredField("this$0").apply { isAccessible = true }
        val centerPanel = Utils.getResId("panel_center", "id")
        val shimId = Utils.getResId("opacity_shim", "id")
        patcher.patch(
            collector,
            "emit",
            arrayOf(Any::class.java, Continuation::class.java),
            Hook { call ->
                val outer = outerField.get(call.thisObject) ?: return@Hook
                val media = outer.javaClass.getDeclaredField("this$0")
                    .apply { isAccessible = true }
                    .get(outer) as? InlineMediaView ?: return@Hook
                val shim = media.findViewById<View>(shimId) ?: return@Hook
                if (shim.visibility == View.VISIBLE && !inside(media, centerPanel)) shim.visibility = View.GONE
            },
        )
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    private fun inside(view: View, id: Int): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent.id == id) return true
            parent = parent.parent
        }
        return false
    }
}
