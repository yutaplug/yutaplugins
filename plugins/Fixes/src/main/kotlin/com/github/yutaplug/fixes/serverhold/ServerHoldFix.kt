package com.github.yutaplug.fixes.serverhold

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.recyclerview.widget.ItemTouchHelper
import com.discord.widgets.guilds.list.GuildsDragAndDropCallback
import com.github.yutaplug.fixes.Fix
import de.robv.android.xposed.XC_MethodHook
import kotlin.math.abs

/**
 * Aliucord's CoreFixes lets you drag a server straight out of its long-press menu: once the menu is
 * open, the first finger movement closes it and starts dragging. It doesn't check how far the finger
 * moved, so on screens that report tiny movements while a finger rests, the menu closes by itself.
 * This hook runs before Aliucord's and ignores movements smaller than Android's touch slop, the same
 * distance Android uses to tell a tap from a drag.
 */
internal class ServerHoldFix : Fix(
    "ServerHoldFix",
    "Server long press",
    "Fixes the menu from holding a server or folder closing by itself when you let go. " +
        "Moving your finger still starts dragging it.",
) {
    override fun start(context: Context) {
        patcher.patch(
            ItemTouchHelper::class.java,
            "checkSelectForSwipe",
            arrayOf(Int::class.javaPrimitiveType!!, MotionEvent::class.java, Int::class.javaPrimitiveType!!),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val helper = param.thisObject as ItemTouchHelper
                    if (helper.mCallback !is GuildsDragAndDropCallback || helper.mSelected != null) return
                    if (param.args[0] as Int != MotionEvent.ACTION_MOVE) return
                    val event = param.args[1] as MotionEvent
                    val index = param.args[2] as Int
                    if (index < 0 || index >= event.pointerCount) return
                    val recycler = helper.mRecyclerView ?: return
                    val slop = ViewConfiguration.get(recycler.context).scaledTouchSlop
                    val dx = abs(event.getX(index) - helper.mInitialTouchX)
                    val dy = abs(event.getY(index) - helper.mInitialTouchY)
                    // Below the slop, ItemTouchHelper itself would do nothing either, so skipping is safe.
                    if (dx < slop && dy < slop) param.result = null
                }
            },
        )
    }

    override fun stop(context: Context) {}
}
