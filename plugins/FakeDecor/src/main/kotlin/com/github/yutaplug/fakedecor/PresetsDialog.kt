package com.github.yutaplug.fakedecor

import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils
import com.discord.app.AppDialog
import com.discord.utilities.color.ColorCompat
import com.facebook.drawee.drawable.`ScalingUtils$ScaleType`
import com.facebook.drawee.view.SimpleDraweeView
import com.lytefast.flexinput.R

/** Decor presets with previews, in Discord's themed language-picker dialog (the one Aliucord's SelectDialog uses). */
class PresetsDialog : AppDialog(Utils.getResId("widget_settings_language_select", "layout")) {
    data class Preset(
        val asset: String,
        val title: String,
        val collection: String,
    )

    var presets: List<Preset> = emptyList()
    var onPicked: ((Preset) -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // Presets are passed in memory; a recreated dialog has none, so close it.
        if (presets.isEmpty()) {
            dismiss()
            return
        }
        val list = view.findViewById<RecyclerView>(Utils.getResId("settings_language_select_list", "id"))
        val header = (list.parent as ViewGroup).getChildAt(0) as ViewGroup
        for (i in 0 until header.childCount) {
            val title = header.getChildAt(i) as? TextView ?: continue
            title.text = "Decor presets"
            break
        }
        list.layoutManager = LinearLayoutManager(view.context)
        list.adapter = Adapter()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = presets.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent)

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(presets[position])
    }

    private inner class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context)) {
        private val image = SimpleDraweeView(parent.context)
        private val title = TextView(parent.context, null, 0, R.i.UiKit_Settings_Item_Label)
        private val collection = TextView(parent.context, null, 0, R.i.UiKit_Settings_Item_SubText)

        init {
            val context = parent.context
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(8), DimenUtils.dpToPx(16), DimenUtils.dpToPx(8))
                val ripple = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
            }
            image.apply {
                hierarchy.n(`ScalingUtils$ScaleType`.e)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundSecondary))
            }
            (itemView as LinearLayout).addView(image, LinearLayout.LayoutParams(DimenUtils.dpToPx(72), DimenUtils.dpToPx(72)))
            title.apply {
                background = null
                setPadding(0, 0, 0, 0)
            }
            collection.apply {
                background = null
                setPadding(0, DimenUtils.dpToPx(2), 0, 0)
            }
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(title, LinearLayout.LayoutParams(-1, -2))
            labels.addView(collection, LinearLayout.LayoutParams(-1, -2))
            (itemView as LinearLayout).addView(
                labels,
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = DimenUtils.dpToPx(12) },
            )
        }

        fun bind(preset: Preset) {
            title.text = preset.title
            collection.text = preset.collection
            image.setImageURI(Uri.parse(FakeDecor.assetUrl(preset.asset, false)))
            itemView.contentDescription = "${preset.title}, ${preset.collection}"
            itemView.setOnClickListener {
                onPicked?.invoke(preset)
                dismiss()
            }
        }
    }
}
