package com.github.yutaplug.profileeffects

import android.text.Spannable
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.WeakHashMap

/**
 * Keeps a reply preview's text clear of the replied-to author's name. Discord indents the text's
 * first line by the width of the avatar and name, measured once while setting up the row, before a
 * display name font makes the name wider (or after it finished loading), so the name ran into the
 * text. This re-measures the indent whenever the avatar and name change size.
 */
internal class ReplyIndent {
    private val holderId by lazy { Utils.getResId("chat_list_adapter_item_reply_leading_views", "id") }
    private val contentId by lazy { Utils.getResId("chat_list_adapter_item_text_reply_content", "id") }

    /** Each leading views holder, with the reply text next to it. */
    private val holders = WeakHashMap<View, TextView>()

    private val listener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) view.post { fix(view) }
    }

    /** Right after the row was configured, with the name styled. */
    fun bind(itemView: View) {
        val holder = itemView.findViewById<View>(holderId) ?: return
        val content = itemView.findViewById<TextView>(contentId) ?: return
        if (holders.put(holder, content) == null) holder.addOnLayoutChangeListener(listener)
        fix(holder)
    }

    private fun fix(holder: View) {
        val content = holders[holder] ?: return
        val text = content.text as? Spannable ?: return
        // Discord's indent: a first-line margin of the holder's width over the whole text.
        val old = text.getSpans(0, text.length, LeadingMarginSpan.Standard::class.java)
            .singleOrNull { it.getLeadingMargin(false) == 0 } ?: return
        holder.measure(0, 0)
        val width = holder.measuredWidth
        if (old.getLeadingMargin(true) == width) return
        val margin = LeadingMarginSpan.Standard(width, 0)

        // Message text keeps its images through the view's DraweeSpanStringBuilder, which setText drops.
        val builder = (content as? SimpleDraweeSpanTextView)?.let { draweeBuilder(it) }
        if (builder != null) {
            replace(builder, margin)
            content.setDraweeSpanStringBuilder(builder)
        } else {
            replace(text, margin)
            content.text = text
        }
    }

    private fun replace(text: Spannable, margin: LeadingMarginSpan.Standard) {
        for (span in text.getSpans(0, text.length, LeadingMarginSpan.Standard::class.java)) {
            if (span.getLeadingMargin(false) == 0) text.removeSpan(span)
        }
        text.setSpan(margin, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun draweeBuilder(view: SimpleDraweeSpanTextView): DraweeSpanStringBuilder? = try {
        builderField.get(view) as? DraweeSpanStringBuilder
    } catch (_: Throwable) {
        null
    }

    /** Stops following the rows and puts back the indent for the unstyled names, for when the plugin stops. */
    fun clear() {
        for (holder in ArrayList(holders.keys)) {
            holder.removeOnLayoutChangeListener(listener)
            try {
                fix(holder)
            } catch (_: Throwable) {
            }
        }
        holders.clear()
    }

    private companion object {
        val builderField = SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder")
            .apply { isAccessible = true }
    }
}
