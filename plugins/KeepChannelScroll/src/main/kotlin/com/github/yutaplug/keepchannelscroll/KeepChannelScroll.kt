package com.github.yutaplug.keepchannelscroll

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.widgets.channels.list.WidgetChannelListModel
import com.discord.widgets.channels.list.WidgetChannelsList
import com.google.android.material.appbar.AppBarLayout
import java.util.WeakHashMap

/** Saves the channel list scroll position per server and restores it when the server is opened again. */
@AliucordPlugin
class KeepChannelScroll : Plugin() {
    private val listeners = WeakHashMap<RecyclerView, RecyclerView.OnScrollListener>()
    private val pendingRestores = WeakHashMap<MGRecyclerAdapterSimple<*>, Long>()
    private val selectedGuildIdField by lazy {
        WidgetChannelsList::class.java.getDeclaredField("selectedGuildId").apply { isAccessible = true }
    }

    override fun start(context: Context) {
        patcher.patch(
            WidgetChannelsList::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook {
                val fragment = it.thisObject as WidgetChannelsList
                val list = fragment.channelList() ?: return@Hook
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                        val guildId = fragment.selectedGuildId() ?: return
                        if (pendingRestores[recyclerView.adapter] == guildId) return
                        save(guildId, recyclerView)
                    }
                }
                listeners.remove(list)?.let(list::removeOnScrollListener)
                list.addOnScrollListener(listener)
                listeners[list] = listener
            },
        )

        patcher.patch(
            WidgetChannelsList::class.java,
            "configureUI",
            arrayOf(WidgetChannelListModel::class.java),
            PreHook {
                val fragment = it.thisObject as WidgetChannelsList
                val model = it.args[0] as WidgetChannelListModel
                val oldGuildId = fragment.selectedGuildId()
                val newGuildId = model.selectedGuild?.id
                if (oldGuildId == newGuildId) return@PreHook
                val list = fragment.channelList() ?: return@PreHook
                val adapter = list.adapter as? MGRecyclerAdapterSimple<*> ?: return@PreHook
                // Skip saving while the old server's restore hasn't been applied, the list still shows stale rows.
                if (oldGuildId != null && pendingRestores[adapter] != oldGuildId) save(oldGuildId, list)
                if (newGuildId != null) pendingRestores[adapter] = newGuildId else pendingRestores.remove(adapter)
            },
        )

        // Data is diffed asynchronously, so restore only once the new server's rows are in the adapter.
        patcher.patch(
            MGRecyclerAdapterSimple::class.java,
            "dispatchUpdates",
            arrayOf(DiffUtil.DiffResult::class.java, List::class.java, List::class.java),
            Hook {
                val adapter = it.thisObject as MGRecyclerAdapterSimple<*>
                val guildId = pendingRestores.remove(adapter) ?: return@Hook
                val list = listeners.keys.firstOrNull { list -> list.adapter === adapter } ?: return@Hook
                restore(guildId, list, adapter)
            },
        )
    }

    private fun WidgetChannelsList.selectedGuildId() = selectedGuildIdField.get(this) as Long?

    private fun WidgetChannelsList.channelList() =
        view?.findViewById<RecyclerView>(Utils.getResId("channels_list", "id"))

    private fun save(guildId: Long, list: RecyclerView) {
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val adapter = list.adapter as? MGRecyclerAdapterSimple<*> ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val offset = (layoutManager.findViewByPosition(position)?.top ?: 0) - list.paddingTop
        val key = adapter.internalData.getOrNull(position)?.key ?: ""
        settings.setString(guildId.toString(), "$position,$offset,$key")
    }

    private fun restore(guildId: Long, list: RecyclerView, adapter: MGRecyclerAdapterSimple<*>) {
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val saved = settings.getString(guildId.toString(), null)?.split(",", limit = 3)
        var position = 0
        var offset = 0
        if (saved != null && saved.size == 3) {
            val data = adapter.internalData
            val keyIndex = data.indexOfFirst { item -> item.key == saved[2] }
            position = if (keyIndex >= 0) keyIndex else saved[0].toIntOrNull() ?: 0
            position = position.coerceIn(0, maxOf(data.size - 1, 0))
            offset = saved[1].toIntOrNull() ?: 0
        }
        if (position > 0) {
            (list.parent as? View)
                ?.findViewById<AppBarLayout>(Utils.getResId("app_bar_layout", "id"))
                ?.setExpanded(false, false)
        }
        layoutManager.scrollToPositionWithOffset(position, offset)
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        listeners.forEach { (list, listener) -> list.removeOnScrollListener(listener) }
        listeners.clear()
        pendingRestores.clear()
    }
}
