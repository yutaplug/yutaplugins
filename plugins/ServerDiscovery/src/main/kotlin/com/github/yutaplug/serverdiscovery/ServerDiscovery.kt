package com.github.yutaplug.serverdiscovery

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.guilds.list.GuildListViewHolder
import com.discord.widgets.guilds.list.WidgetGuildListAdapter
import java.util.WeakHashMap

@AliucordPlugin
class ServerDiscovery : Plugin() {
    private val modifiedRows = WeakHashMap<FrameLayout, Int>()
    private var screen: DiscoveryScreen? = null

    override fun start(context: Context) {
        patcher.patch(
            WidgetGuildListAdapter::class.java,
            "onBindViewHolder",
            arrayOf(GuildListViewHolder::class.java, Int::class.javaPrimitiveType!!),
            Hook { frame ->
                // View type 5 is the existing Create Server row in Discord 126.21.
                val adapter = frame.thisObject as WidgetGuildListAdapter
                if (adapter.getItemViewType(frame.args[1] as Int) != 5) return@Hook
                val holder = frame.args[0] as? GuildListViewHolder ?: return@Hook
                val row = holder.itemView as? FrameLayout ?: return@Hook
                addDiscoveryButton(row)
            },
        )
    }

    private fun addDiscoveryButton(row: FrameLayout) {
        if (row.findViewWithTag<View>(DISCOVERY_TAG) != null) return
        val context = row.context
        val iconSize = context.resources.getDimensionPixelSize(Utils.getResId("avatar_size_large", "dimen"))
        val spacing = context.resources.getDimensionPixelSize(Utils.getResId("guild_item_spacing", "dimen"))
        val oldHeight = row.layoutParams.height

        val createButton = FrameLayout(context)
        while (row.childCount > 0) {
            val child = row.getChildAt(0)
            val params = child.layoutParams
            row.removeViewAt(0)
            createButton.addView(child, params)
        }
        row.layoutParams = row.layoutParams.apply { height = iconSize * 2 + spacing }
        row.addView(createButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, iconSize, Gravity.TOP))

        val discoveryRow = FrameLayout(context).apply {
            tag = DISCOVERY_TAG
            contentDescription = context.getString(Utils.getResId("guild_discovery_tooltip", "string"))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (screen == null) {
                    screen = DiscoveryScreen(context) { screen = null }.also { it.show() }
                }
            }
        }
        row.addView(
            discoveryRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, iconSize, Gravity.TOP).apply {
                topMargin = iconSize + spacing
            },
        )

        val backgroundColor = ColorCompat.getThemedColor(context, Utils.getResId("colorBackgroundSecondary", "attr"))
        val iconColor = ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr"))
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(backgroundColor)
        }
        val button = FrameLayout(context).apply {
            background = RippleDrawable(ColorStateList.valueOf(rippleColor(context)), circle, null)
        }
        discoveryRow.addView(button, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        button.addView(
            ImageView(context).apply {
                setImageResource(Utils.getResId("ic_compass", "drawable"))
                imageTintList = ColorStateList.valueOf(iconColor)
            },
            FrameLayout.LayoutParams(dp(context, 24), dp(context, 24), Gravity.CENTER),
        )

        modifiedRows[row] = oldHeight
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        screen?.dismiss()
        screen = null
        modifiedRows.forEach { (row, oldHeight) ->
            val createButton = row.getChildAt(0) as? FrameLayout ?: return@forEach
            if (row.findViewWithTag<View>(DISCOVERY_TAG) == null) return@forEach
            val originalChildren = mutableListOf<Pair<View, ViewGroup.LayoutParams>>()
            while (createButton.childCount > 0) {
                val child = createButton.getChildAt(0)
                val params = child.layoutParams
                createButton.removeViewAt(0)
                originalChildren.add(child to params)
            }
            row.removeAllViews()
            originalChildren.forEach { (child, params) -> row.addView(child, params) }
            row.layoutParams = row.layoutParams.apply { height = oldHeight }
        }
        modifiedRows.clear()
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val DISCOVERY_TAG = "server_discovery_button"
    }
}
