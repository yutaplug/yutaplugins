package com.github.yutaplug.dmbuttonicon

import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.aliucord.views.TextInput
import com.discord.utilities.color.ColorCompat
import com.discord.views.CheckedSetting
import com.lytefast.flexinput.R

class DMButtonIconSettings(private val plugin: DMButtonIcon) : SettingsPage() {
    private var allIcons = emptyList<String>()
    private var shown = emptyList<String>()
    private var current: TextView? = null
    private var count: TextView? = null
    private val adapter = IconAdapter()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("DMButtonIcon")
        setActionBarSubtitle("Direct Messages button")

        allIcons = DMButtonIcon.iconNames(resources)
        shown = allIcons

        section("Icon")
        current = action("Current icon", currentName()) { Utils.showToast(plugin.iconName ?: DMButtonIcon.DEFAULT_ICON) }
        action("Reset to default", "Use Discord's speech bubble icon.") {
            plugin.iconName = null
            onIconChanged()
        }
        add(
            Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                "Tint icon",
                "Color the icon like Discord's own icon. Turn off to show icons in their original colors.",
            ).apply {
                isChecked = plugin.tint
                setOnCheckedListener {
                    plugin.tint = it
                    adapter.notifyDataSetChanged()
                }
            },
        )

        divider()
        section("Choose icon")
        add(
            TextInput(requireContext(), "Search icons").apply {
                editText.setSingleLine(true)
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
                    },
                )
                val margin = DimenUtils.dpToPx(16)
                setPadding(margin, DimenUtils.dpToPx(8), margin, DimenUtils.dpToPx(4))
            },
        )
        count = note("")
        updateCount()

        val cellWidth = resources.getDimensionPixelSize(Utils.getResId("guild_list_size", "dimen"))
        linearLayout.addView(
            RecyclerView(requireContext()).apply {
                layoutManager = GridLayoutManager(context, maxOf(1, resources.displayMetrics.widthPixels / cellWidth))
                adapter = this@DMButtonIconSettings.adapter
                // A fixed height keeps the grid recycling its cells instead of inflating every icon at once.
                isNestedScrollingEnabled = true
                setPadding(0, 0, 0, DimenUtils.dpToPx(8))
                clipToPadding = false
            },
            LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6).toInt()),
        )
    }

    private fun currentName() = plugin.iconName ?: "Default"

    private fun filter(query: String) {
        val q = query.trim().lowercase().replace(' ', '_')
        shown = if (q.isEmpty()) allIcons else allIcons.filter { it.contains(q) }
        updateCount()
        adapter.notifyDataSetChanged()
    }

    private fun updateCount() {
        count?.text = "${shown.size} icons. Tap an icon to use it, or long press it to see its name."
    }

    private fun onIconChanged() {
        current?.text = currentName()
        adapter.notifyDataSetChanged()
    }

    private inner class IconAdapter : RecyclerView.Adapter<IconAdapter.Cell>() {
        inner class Cell(view: View) : RecyclerView.ViewHolder(view) {
            val wrap: FrameLayout = view.findViewById(Utils.getResId("guilds_item_profile_avatar_wrap", "id"))
            val icon: ImageView = view.findViewById(DMButtonIcon.avatarId)
            val selected: View = view.findViewById(DMButtonIcon.selectedId)
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            // Discord's own DM button, so each icon previews exactly as it will look.
            val view = LayoutInflater.from(parent.context)
                .inflate(Utils.getResId("widget_guilds_list_item_profile", "layout"), parent, false)
            view.layoutParams = view.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            val ripple = TypedValue()
            if (parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                view.setBackgroundResource(ripple.resourceId)
            }
            return Cell(view)
        }

        override fun onBindViewHolder(cell: Cell, position: Int) {
            val name = shown[position]
            val isCurrent = name == (plugin.iconName ?: DMButtonIcon.DEFAULT_ICON)
            cell.itemView.contentDescription = name
            cell.selected.visibility = if (isCurrent) View.VISIBLE else View.GONE
            if (isCurrent) {
                cell.wrap.setBackgroundResource(Utils.getResId("drawable_squircle_brand_500", "drawable"))
                cell.wrap.backgroundTintList = null
            } else {
                cell.wrap.setBackgroundResource(Utils.getResId("drawable_circle_black", "drawable"))
                cell.wrap.backgroundTintList = ColorStateList.valueOf(
                    ColorCompat.getThemedColor(cell.wrap, Utils.getResId("colorBackgroundSecondary", "attr")),
                )
            }
            DMButtonIcon.applyIcon(cell.icon, name, plugin.tint)
            if (plugin.tint) cell.icon.imageTintList = DMButtonIcon.iconTint(cell.icon, isCurrent)
            cell.itemView.setOnClickListener {
                plugin.iconName = if (name == DMButtonIcon.DEFAULT_ICON) null else name
                onIconChanged()
            }
            cell.itemView.setOnLongClickListener {
                Utils.showToast(name)
                true
            }
        }
    }

    private fun section(title: String) {
        add(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
    }

    private fun note(text: String): TextView =
        TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            this.text = text
            background = null
            setPaddingRelative(paddingStart, DimenUtils.dpToPx(4), paddingEnd, DimenUtils.dpToPx(8))
            add(this)
        }

    /** Returns the subtitle view so it can be updated. */
    private fun action(title: String, subtitle: String, action: () -> Unit): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isFocusable = true
            setOnClickListener { action() }
        }
        row.addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Label).apply {
                text = title
                background = null
                setPaddingRelative(paddingStart, DimenUtils.dpToPx(8), paddingEnd, 0)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        val sub = TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_SubText).apply {
            text = subtitle
            background = null
            setPadding(DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
        }
        row.addView(sub, LinearLayout.LayoutParams(-1, -2))
        add(row)
        return sub
    }

    private fun divider() {
        linearLayout.addView(
            View(requireContext(), null, 0, R.i.UiKit_Settings_Divider),
            LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(1)),
        )
    }

    private fun add(view: View) {
        linearLayout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2))
    }

    override fun onDestroyView() {
        current = null
        count = null
        super.onDestroyView()
    }
}
