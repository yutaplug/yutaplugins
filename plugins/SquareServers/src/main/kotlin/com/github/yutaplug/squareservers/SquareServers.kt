package com.github.yutaplug.squareservers

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.images.MGImages
import com.discord.widgets.guilds.list.FolderItemDecoration
import com.discord.widgets.guilds.list.GuildListItem
import com.discord.widgets.guilds.list.GuildListViewHolder
import com.discord.widgets.guilds.list.WidgetGuildListAdapter
import com.facebook.drawee.view.SimpleDraweeView
import kotlin.jvm.functions.Function1

@AliucordPlugin
class SquareServers : Plugin() {
    private val overlayColor = GuildListViewHolder.GuildViewHolder::class.java
        .getDeclaredField("overlayColor").apply { isAccessible = true }
    private val overlayColorInFolder = GuildListViewHolder.GuildViewHolder::class.java
        .getDeclaredField("overlayColorInFolder").apply { isAccessible = true }

    override fun start(context: Context) {
        // Discord rounds unselected guilds as circles and only squircles the selected one.
        patcher.patch(
            GuildListViewHolder.GuildViewHolder::class.java,
            "configureGuildIconBackground",
            arrayOf(Boolean::class.java, Boolean::class.java, Boolean::class.java),
            Hook { call ->
                val holder = call.thisObject as GuildListViewHolder.GuildViewHolder
                val selected = call.args[0] as Boolean
                val inFolder = call.args[1] as Boolean
                val hasIcon = call.args[2] as Boolean
                val itemView = holder.itemView
                val avatar = itemView.findViewById<SimpleDraweeView>(Utils.getResId("guilds_item_avatar", "id"))
                val overlay = (if (inFolder) overlayColorInFolder else overlayColor).getInt(holder)
                MGImages.setRoundingParams(avatar, radius(itemView).toFloat(), false, overlay, null, null)

                val background = when {
                    hasIcon -> "drawable_squircle_transparent"
                    selected -> "drawable_squircle_brand_500"
                    // Discord tints this one after setting it; the view keeps that tint list.
                    else -> "drawable_squircle_white"
                }
                itemView.findViewById<View>(Utils.getResId("guilds_item_avatar_wrap", "id"))?.squircle(background)
            },
        )

        patcher.patch(
            GuildListViewHolder.FolderViewHolder::class.java.getDeclaredConstructor(
                View::class.java,
                Function1::class.java,
                Function1::class.java,
            ),
            Hook { call ->
                val itemView = (call.thisObject as GuildListViewHolder.FolderViewHolder).itemView
                itemView.findViewById<View>(Utils.getResId("guilds_item_folder_container", "id"))?.squircle()
            },
        )
        patcher.patch(
            GuildListViewHolder.FolderViewHolder::class.java,
            "onDragStarted",
            emptyArray(),
            Hook { call ->
                val itemView = (call.thisObject as GuildListViewHolder.FolderViewHolder).itemView
                itemView.findViewById<View>(Utils.getResId("guilds_item_folder", "id"))?.squircle()
            },
        )

        // Folder backgrounds are drawn by the list's item decoration, not the folder views.
        patcher.patch(
            FolderItemDecoration::class.java.getDeclaredConstructor(
                Drawable::class.java,
                Drawable::class.java,
                Drawable::class.java,
                Int::class.java,
            ),
            Hook { call ->
                val r = radius(Utils.appContext).toFloat()
                for (name in arrayOf("drawableNoChildren", "tintableDrawableNoChildren", "drawableWithChildren")) {
                    val field = FolderItemDecoration::class.java.getDeclaredField(name).apply { isAccessible = true }
                    ((field.get(call.thisObject) as Drawable).mutate() as? GradientDrawable)?.cornerRadius = r
                }
            },
        )

        patcher.patch(
            GuildListViewHolder.FriendsViewHolder::class.java,
            "configure",
            arrayOf(GuildListItem.FriendsItem::class.java),
            Hook { call ->
                val item = call.args[0] as GuildListItem.FriendsItem
                val itemView = (call.thisObject as GuildListViewHolder.FriendsViewHolder).itemView
                itemView.findViewById<View>(Utils.getResId("guilds_item_profile_avatar_wrap", "id"))
                    ?.squircle(if (item.isSelected) "drawable_squircle_brand_500" else "drawable_squircle_white")
            },
        )

        // Add server and hub buttons are plain views with a circular drawee.
        patcher.patch(
            WidgetGuildListAdapter::class.java,
            "onCreateViewHolder",
            arrayOf(ViewGroup::class.java, Int::class.java),
            Hook { call ->
                val holder = call.result as? GuildListViewHolder ?: return@Hook
                if (holder !is GuildListViewHolder.SimpleViewHolder &&
                    holder !is GuildListViewHolder.DiscordHubViewHolder
                ) {
                    return@Hook
                }
                val drawee = (holder.itemView as? FrameLayout)?.getChildAt(0) as? SimpleDraweeView ?: return@Hook
                // The drawee is match_parent wide; a circle hid that, a rounded rect stretches across the row.
                val size = drawee.resources.getDimensionPixelSize(Utils.getResId("avatar_size_large", "dimen"))
                drawee.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                MGImages.setRoundingParams(drawee, radius(drawee).toFloat(), false, null, null, null)
            },
        )
    }

    private fun radius(view: View) = radius(view.context)

    private fun radius(context: Context) =
        context.resources.getDimensionPixelSize(Utils.getResId("guild_icon_radius", "dimen"))

    private fun View.squircle(drawable: String? = null) {
        if (drawable != null) setBackgroundResource(Utils.getResId(drawable, "drawable"))
        (background?.mutate() as? GradientDrawable)?.cornerRadius = radius(this).toFloat()
        invalidateOutline()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
