package com.github.yutaplug.readall

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.commands.ApplicationCommandType
import com.discord.widgets.guilds.list.GuildListItem
import com.discord.widgets.guilds.list.GuildListViewHolder
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@AliucordPlugin
class ReadAll : Plugin() {
    /** DM icon rows that got the long press, so [stop] can remove it again. */
    private val rows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val busy = AtomicBoolean(false)

    @Volatile private var generation = 0

    override fun start(context: Context) {
        generation++
        instance = this

        patcher.patch(
            GuildListViewHolder.FriendsViewHolder::class.java,
            "configure",
            arrayOf(GuildListItem.FriendsItem::class.java),
            Hook { frame ->
                val row = (frame.thisObject as GuildListViewHolder.FriendsViewHolder).itemView
                rows.add(row)
                row.setOnLongClickListener {
                    openSheet(it)
                    true
                }
            },
        )

        commands.registerCommand(
            "readall",
            "Mark unread channels as read",
            listOf(
                Utils.createCommandOption(
                    ApplicationCommandType.STRING,
                    "scope",
                    "What to mark as read (servers by default)",
                    choices = listOf(
                        Utils.createCommandChoice("Servers", Scope.SERVERS.value),
                        Utils.createCommandChoice("Direct messages", Scope.DMS.value),
                        Utils.createCommandChoice("Everything", Scope.ALL.value),
                    ),
                ),
            ),
        ) { ctx ->
            markAsRead(Scope.from(ctx.getString("scope")))
            null
        }
    }

    private fun openSheet(anchor: View) {
        try {
            val manager = findActivity(anchor.context)?.supportFragmentManager
                ?: Utils.appActivity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(ReadAllSheet.TAG) != null) return
            ReadAllSheet().show(manager, ReadAllSheet.TAG)
        } catch (error: Throwable) {
            logger.error("Could not open the Read All sheet", error)
        }
    }

    private fun findActivity(context: Context?): FragmentActivity? {
        var current = context
        while (current != null) {
            if (current is FragmentActivity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    fun markAsRead(scope: Scope) {
        if (!busy.compareAndSet(false, true)) {
            toast("Already marking channels as read")
            return
        }
        val run = generation
        val cancelled = { generation != run }
        Utils.threadPool.execute {
            try {
                val unreads = ReadStates.collect(scope)
                if (unreads.isEmpty()) {
                    toast("Nothing to mark as read", run)
                    return@execute
                }
                ReadStates.acknowledge(unreads, cancelled)
                val count = unreads.size
                toast("Marked $count ${if (count == 1) "channel" else "channels"} as read", run)
            } catch (error: Throwable) {
                logger.error("Could not mark channels as read", error)
                toast("Could not mark channels as read", run)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun toast(message: String, run: Int = generation) {
        Utils.mainThread.post { if (generation == run) Utils.showToast(message) }
    }

    override fun stop(context: Context) {
        generation++
        if (instance === this) instance = null
        patcher.unpatchAll()
        commands.unregisterAll()
        for (row in rows.toList()) {
            row.setOnLongClickListener(null)
            row.isLongClickable = false
        }
        rows.clear()
        try {
            val manager = findActivity(Utils.appActivity)?.supportFragmentManager
            (manager?.findFragmentByTag(ReadAllSheet.TAG) as? ReadAllSheet)?.dismissAllowingStateLoss()
        } catch (_: Throwable) {
            // No activity yet, so there is no sheet to close.
        }
    }

    companion object {
        @Volatile
        var instance: ReadAll? = null
            private set
    }
}
