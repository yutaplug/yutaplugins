package com.github.yutaplug.squareservers

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.DimenUtils
import com.discord.utilities.images.MGImages
import com.discord.widgets.guilds.list.FolderItemDecoration
import com.discord.widgets.guilds.list.GuildListItem
import com.discord.widgets.guilds.list.GuildListViewHolder
import com.discord.widgets.guilds.list.WidgetGuildListAdapter
import com.facebook.drawee.view.SimpleDraweeView
import java.lang.ref.WeakReference

@AliucordPlugin
class SquareServers : Plugin() {
    private val overlayColor = GuildListViewHolder.GuildViewHolder::class.java
        .getDeclaredField("overlayColor").apply { isAccessible = true }
    private val overlayColorInFolder = GuildListViewHolder.GuildViewHolder::class.java
        .getDeclaredField("overlayColorInFolder").apply { isAccessible = true }
    private val folderDrawables = arrayOf("drawableNoChildren", "tintableDrawableNoChildren", "drawableWithChildren")
        .map { FolderItemDecoration::class.java.getDeclaredField(it).apply { isAccessible = true } }
    private var adapter: WeakReference<WidgetGuildListAdapter>? = null

    init {
        settingsTab = SettingsTab(SquareServersSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    var radiusDp: Int
        get() = settings.getInt(RADIUS_KEY, DEFAULT_RADIUS).coerceIn(0, MAX_RADIUS)
        set(value) {
            settings.setInt(RADIUS_KEY, value)
            // Every hook below runs on bind or draw, so rebinding the list applies the new radius.
            adapter?.get()?.notifyDataSetChanged()
        }

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
                MGImages.setRoundingParams(avatar, radius(), false, overlay, null, null)

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
            GuildListViewHolder.FolderViewHolder::class.java,
            "configure",
            arrayOf(GuildListItem.FolderItem::class.java),
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
            FolderItemDecoration::class.java,
            "onDraw",
            arrayOf(Canvas::class.java, RecyclerView::class.java, RecyclerView.State::class.java),
            PreHook { call ->
                for (field in folderDrawables) {
                    ((field.get(call.thisObject) as Drawable).mutate() as? GradientDrawable)?.cornerRadius = radius()
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
                val drawee = buttonDrawee(call.result as GuildListViewHolder) ?: return@Hook
                // The drawee is match_parent wide; a circle hid that, a rounded rect stretches across the row.
                val size = drawee.resources.getDimensionPixelSize(Utils.getResId("avatar_size_large", "dimen"))
                drawee.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            },
        )
        patcher.patch(
            WidgetGuildListAdapter::class.java,
            "onBindViewHolder",
            arrayOf(GuildListViewHolder::class.java, Int::class.java),
            Hook { call ->
                val adapter = call.thisObject as WidgetGuildListAdapter
                if (this.adapter?.get() !== adapter) this.adapter = WeakReference(adapter)
                val drawee = buttonDrawee(call.args[0] as GuildListViewHolder) ?: return@Hook
                MGImages.setRoundingParams(drawee, radius(), false, null, null, null)
            },
        )
    }

    private fun buttonDrawee(holder: GuildListViewHolder): SimpleDraweeView? {
        if (holder !is GuildListViewHolder.SimpleViewHolder && holder !is GuildListViewHolder.DiscordHubViewHolder) {
            return null
        }
        return (holder.itemView as? FrameLayout)?.getChildAt(0) as? SimpleDraweeView
    }

    private fun radius() = DimenUtils.dpToPx(radiusDp).toFloat()

    private fun View.squircle(drawable: String? = null) {
        if (drawable != null) setBackgroundResource(Utils.getResId(drawable, "drawable"))
        (background?.mutate() as? GradientDrawable)?.cornerRadius = radius()
        invalidateOutline()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        adapter = null
    }

    companion object {
        const val DEFAULT_RADIUS = 12
        const val MAX_RADIUS = 23
        private const val RADIUS_KEY = "radius"
    }
}
