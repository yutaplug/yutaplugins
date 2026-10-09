package com.github.yutaplug.dmbuttonicon

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.guilds.list.GuildListItem
import com.discord.widgets.guilds.list.GuildListViewHolder
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class DMButtonIcon : Plugin() {
    /** DM button icons currently on screen, so a new choice applies without reopening the app. */
    private val icons = Collections.newSetFromMap(WeakHashMap<ImageView, Boolean>())

    /** Drawable name of the chosen icon, or null for Discord's default. */
    var iconName: String?
        get() = settings.getString(KEY_ICON, null)
        set(value) {
            if (value == null) settings.remove(KEY_ICON) else settings.setString(KEY_ICON, value)
            refresh()
        }

    var tint: Boolean
        get() = settings.getBool(KEY_TINT, true)
        set(value) {
            settings.setBool(KEY_TINT, value)
            refresh()
        }

    init {
        settingsTab = SettingsTab(DMButtonIconSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    override fun start(context: Context) {
        patcher.patch(
            GuildListViewHolder.FriendsViewHolder::class.java,
            "configure",
            arrayOf(GuildListItem.FriendsItem::class.java),
            Hook {
                val holder = it.thisObject as GuildListViewHolder.FriendsViewHolder
                val icon = holder.itemView.findViewById<ImageView>(avatarId) ?: return@Hook
                icons.add(icon)
                apply(icon, (it.args[0] as GuildListItem.FriendsItem).isSelected)
            },
        )
        refresh()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        for (icon in icons.toList()) {
            applyIcon(icon, null, true)
            icon.imageTintList = iconTint(icon, isSelected(icon))
        }
        icons.clear()
    }

    private fun refresh() {
        for (icon in icons.toList()) apply(icon, isSelected(icon))
    }

    // The selected pill next to the icon is shown only while the DM button is selected (see FriendsViewHolder.configure).
    private fun isSelected(icon: ImageView): Boolean =
        (icon.parent?.parent as? View)?.findViewById<View>(selectedId)?.visibility == View.VISIBLE

    private fun apply(icon: ImageView, selected: Boolean) {
        applyIcon(icon, iconName, tint)
        if (tint) icon.imageTintList = iconTint(icon, selected)
    }

    companion object {
        private const val KEY_ICON = "icon"
        private const val KEY_TINT = "tint"
        const val DEFAULT_ICON = "ic_guild_list_dms_24dp"

        val avatarId by lazy { Utils.getResId("guilds_item_profile_avatar", "id") }
        val selectedId by lazy { Utils.getResId("guilds_item_profile_selected", "id") }

        /** Same colors FriendsViewHolder.configure uses: white when selected, primary_300 otherwise. */
        fun iconTint(view: View, selected: Boolean): ColorStateList = ColorStateList.valueOf(
            if (selected) {
                ColorCompat.getColor(view, Utils.getResId("white", "color"))
            } else {
                ColorCompat.getThemedColor(view, Utils.getResId("primary_300", "attr"))
            },
        )

        /** Sets [name]'s drawable on [icon], or the default icon when [name] is null or can't be loaded. */
        fun applyIcon(icon: ImageView, name: String?, tint: Boolean) {
            val custom = name?.let { Utils.getResId(it, "drawable") }?.takeIf { it != 0 }
            val loaded = custom != null && try {
                icon.setImageResource(custom)
                true
            } catch (e: Throwable) {
                false
            }
            if (!loaded) icon.setImageResource(Utils.getResId(DEFAULT_ICON, "drawable"))
            // Icons come in many sizes; fit them in the default icon's 24dp box without upscaling small ones.
            val size = if (loaded) DimenUtils.dpToPx(24) else FrameLayout.LayoutParams.WRAP_CONTENT
            (icon.layoutParams as? FrameLayout.LayoutParams)?.let {
                if (it.width != size || it.height != size) {
                    it.width = size
                    it.height = size
                    icon.layoutParams = it
                }
            }
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            if (!tint) icon.imageTintList = null
        }

        /** Names of all of Discord's `ic_` drawables, sorted. */
        fun iconNames(res: Resources): List<String> {
            val type = Utils.getResId(DEFAULT_ICON, "drawable") and 0xFFFF0000.toInt()
            val names = ArrayList<String>()
            var misses = 0
            var entry = 0
            while (misses < 64 && entry <= 0xFFFF) {
                try {
                    val name = res.getResourceEntryName(type or entry)
                    if (name.startsWith("ic_")) names.add(name)
                    misses = 0
                } catch (e: Resources.NotFoundException) {
                    misses++
                }
                entry++
            }
            names.sort()
            return names
        }
    }
}
