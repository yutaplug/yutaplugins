package com.github.yutaplug.messageapps

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.app.AppDialog
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class MessageApps : Plugin() {
    private val rows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val pages = Collections.newSetFromMap(WeakHashMap<AppsPage, Boolean>())
    private val rowId = View.generateViewId()

    @Volatile internal var active = false
        private set

    override fun start(context: Context) {
        active = true
        patchMessageMenu()
        patchUserMenu()
    }

    private fun patchMessageMenu() {
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { call ->
                val actions = call.thisObject as WidgetChatListActions
                val model = call.args[0] as WidgetChatListActions.Model
                val channel = model.channel ?: return@Hook
                val container = actions.view?.findViewById<LinearLayout>(
                    Utils.getResId("dialog_chat_actions_container", "id"),
                ) ?: return@Hook
                val row = container.findViewById<TextView>(rowId) ?: run {
                    val native = takeRow(container.context, "widget_chat_list_actions", "dialog_chat_actions_reply")
                    val icon = ContextCompat.getDrawable(
                        container.context,
                        Utils.getResId("ic_slash_command_24dp", "drawable"),
                    )
                    val arrow = ContextCompat.getDrawable(
                        container.context,
                        Utils.getResId("exo_ic_chevron_right", "drawable"),
                    )
                    native.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, arrow, null)
                    val pin = container.findViewById<View>(Utils.getResId("dialog_chat_actions_pin", "id"))
                    val insertion = if (pin == null) container.childCount else container.indexOfChild(pin) + 1
                    container.addView(native, insertion)
                    native
                }
                row.visibility = if (model.message.isLocal || model.message.id <= 0L) View.GONE else View.VISIBLE
                row.setOnClickListener {
                    if (!active) return@setOnClickListener
                    openApps(container.context, CommandTarget.message(channel.k(), channel.i(), model.message.id))
                    actions.dismiss()
                }
            },
        )
    }

    // The profile sheet's "..." button opens this dialog (Block, Unblock, Remove Friend).
    private fun patchUserMenu() {
        val dialogClass = Class.forName("b.a.a.d.a")
        patcher.patch(
            dialogClass.getDeclaredMethod("onViewBound", View::class.java),
            Hook { call ->
                val dialog = call.thisObject as AppDialog
                val root = call.args[0] as? LinearLayout ?: return@Hook
                val args = dialog.argumentsOrDefault
                val userId = args.getLong("com.discord.intent.extra.EXTRA_USER_ID", 0L)
                // Sheets opened outside a chat (friends list, member list) have no channel; use the open one.
                val channelId = args.getLong("com.discord.intent.extra.EXTRA_CHANNEL_ID", 0L)
                    .takeIf { it != 0L } ?: StoreStream.getChannelsSelected().id
                val channel = StoreStream.getChannels().getChannel(channelId)
                if (userId == 0L || channel == null) return@Hook
                val row = root.findViewById<TextView>(rowId) ?: run {
                    val native = takeRow(root.context, "user_actions_dialog", "user_actions_dialog_block_item")
                    val icon = ContextCompat.getDrawable(
                        root.context,
                        Utils.getResId("ic_slash_command_24dp", "drawable"),
                    )?.mutate()
                    icon?.setTint(
                        ColorCompat.getThemedColor(root.context, Utils.getResId("colorInteractiveNormal", "attr")),
                    )
                    native.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                    root.addView(native)
                    native
                }
                row.setOnClickListener {
                    if (!active) return@setOnClickListener
                    openApps(root.context, CommandTarget.user(channel.k(), channel.i(), userId))
                    dialog.dismiss()
                }
            },
        )
    }

    private fun takeRow(context: Context, layout: String, id: String): TextView {
        val template = LayoutInflater.from(context).inflate(Utils.getResId(layout, "layout"), null) as ViewGroup
        val native = template.findViewById<TextView>(Utils.getResId(id, "id"))
        (native.parent as ViewGroup).removeView(native)
        native.id = rowId
        native.text = "Apps"
        native.visibility = View.VISIBLE
        rows.add(native)
        return native
    }

    private fun openApps(context: Context, target: CommandTarget) {
        val page = AppsPage(this, target)
        pages.add(page)
        Utils.openPageWithProxy(context, page)
    }

    internal fun register(page: AppsPage) {
        pages.add(page)
    }

    override fun stop(context: Context) {
        active = false
        patcher.unpatchAll()
        rows.toList().forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rows.clear()
        pages.toList().forEach { it.close() }
        pages.clear()
    }
}
