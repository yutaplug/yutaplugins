package com.github.yutaplug.voicechannelmenu

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel

@AliucordPlugin
class VoiceChannelMenu : Plugin() {
    override fun start(context: Context) {
        // Discord only gives voice channels a long-press action (opening settings) for members who can
        // manage them. Replace it for everyone with a channel sheet.
        patcher.patch(
            WidgetChannelsListAdapter.ItemChannelVoice::class.java.getDeclaredMethod(
                "onConfigure",
                Int::class.javaPrimitiveType,
                ChannelListItem::class.java,
            ),
            Hook { frame ->
                val holder = frame.thisObject as WidgetChannelsListAdapter.ItemChannelVoice
                val item = frame.args[1] as? ChannelListItemVoiceChannel ?: return@Hook
                val channel = item.component1()
                // 16 is MANAGE_CHANNELS, the same check Discord uses for the settings shortcut.
                val canManage = PermissionUtils.can(16L, item.component4())
                holder.itemView.setOnLongClickListener { view ->
                    val activity = view.context as? FragmentActivity ?: return@setOnLongClickListener false
                    VoiceChannelSheet.show(activity.supportFragmentManager, channel.k(), canManage)
                    true
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
