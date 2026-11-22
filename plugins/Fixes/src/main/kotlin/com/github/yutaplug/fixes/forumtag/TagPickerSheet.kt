package com.github.yutaplug.fixes.forumtag

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils
import com.aliucord.views.Button
import com.aliucord.widgets.BottomSheet
import com.discord.api.channel.ForumTag
import com.google.android.flexbox.FlexboxLayout
import com.lytefast.flexinput.R

/** Ported from TsyQax's ForumTagFix (UI by Loomis). */
internal class TagPickerSheet(
    private val tags: List<ForumTag>,
    private val selectedTagIds: MutableList<Long>,
    private val appliedTags: List<Long>?,
    private val onComplete: Runnable,
) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val context = view.context
        val padding = DimenUtils.dpToPx(16)
        val selected = (appliedTags ?: selectedTagIds).toMutableSet()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        root.addView(
            TextView(context, null, 0, R.i.UiKit_Settings_Text).apply {
                text = "Select Tags"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 1.2f * textSize)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, padding)
            },
        )

        val scrollView = object : NestedScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = View.MeasureSpec.makeMeasureSpec(DimenUtils.dpToPx(320), View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, max)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = DimenUtils.dpToPx(8)
                weight = 1f
            }
        }
        val flex = FlexboxLayout(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            flexDirection = 0
            flexWrap = 1
            val margin = DimenUtils.dpToPx(10)
            setPadding(margin, 0, margin, 0)
        }
        for (tag in tags) flex.addView(chip(flex, tag, selected))
        scrollView.addView(flex)
        root.addView(scrollView)

        root.addView(
            Button(context).apply {
                text = "OK"
                layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = DimenUtils.dpToPx(8)
                }
                setOnClickListener {
                    selectedTagIds.clear()
                    selectedTagIds.addAll(selected)
                    onComplete.run()
                    Utils.mainThread.post {
                        try {
                            dismiss()
                        } catch (_: Exception) {
                        }
                    }
                }
            },
        )
        addView(root)
    }

    private fun chip(parent: ViewGroup, tag: ForumTag, selected: MutableSet<Long>): View {
        val context = parent.context
        val dp2 = DimenUtils.dpToPx(2)
        val dp4 = DimenUtils.dpToPx(4)
        val dp8 = DimenUtils.dpToPx(8)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = DimenUtils.dpToPx(14).toFloat()
            setColor(Color.TRANSPARENT)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp8, dp4, dp8, dp4)
            layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply {
                rightMargin = dp8
                bottomMargin = dp8
            }
            this.background = background
        }
        val name = TextView(context, null, 0, R.i.UiKit_Settings_Text).apply {
            maxLines = 1
            val emoji = tag.b()
            if (emoji != null) {
                text = "$emoji ${tag.d()}"
                setPadding(dp2, 0, dp2, 0)
            } else {
                text = tag.d()
                setPadding(dp4, 0, dp4, 0)
            }
        }
        container.addView(name)

        val id = tag.c()
        fun render() {
            if (id in selected) {
                background.setStroke(maxOf(1, DimenUtils.dpToPx(17) / 10), 0xFF5865F2.toInt())
            } else {
                background.setStroke(DimenUtils.dpToPx(1), ColorUtils.setAlphaComponent(name.currentTextColor, 95))
            }
        }
        render()
        container.setOnClickListener {
            if (!selected.remove(id)) selected.add(id)
            render()
        }
        return container
    }
}
