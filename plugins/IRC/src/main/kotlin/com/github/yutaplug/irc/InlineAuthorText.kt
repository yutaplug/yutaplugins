package com.github.yutaplug.irc

import android.graphics.Typeface
import android.text.Annotation
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.MetricAffectingSpan

/** Adds an author to the native rich text without reparsing the message. */
internal object InlineAuthorText {
    private const val SEPARATOR = "\u2002"

    fun prepend(
        text: SpannableStringBuilder,
        name: String,
        typeface: Typeface?,
        textSize: Float,
        color: Int,
        link: ClickableSpan,
    ) {
        // Rebinding a builder must remove only our prefix, preserving body spans.
        for (previous in text.getSpans(0, text.length, AuthorSpan::class.java)) {
            val start = text.getSpanStart(previous)
            val end = text.getSpanEnd(previous)
            text.removeSpan(previous)
            text.removeSpan(previous.link)
            if (start >= 0 && end >= start) text.delete(start, end)
        }
        // Other plugins (like MessageLatency) mark icons that belong before the author with an Annotation.
        var start = 0
        for (annotation in text.getSpans(0, text.length, Annotation::class.java)) {
            if (annotation.key == LEADING_KEY && text.getSpanStart(annotation) == 0) {
                start = maxOf(start, text.getSpanEnd(annotation))
            }
        }
        text.insert(start, name + SEPARATOR)
        val author = AuthorSpan(name.length, typeface, textSize, color, link)
        text.setSpan(author, start, start + name.length + SEPARATOR.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(link, start, start + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Annotation key for leading content that must stay before the inline author. */
    const val LEADING_KEY = "ircLeading"

    fun find(text: CharSequence): AuthorSpan? =
        (text as? Spanned)?.getSpans(0, text.length, AuthorSpan::class.java)?.firstOrNull()

    class AuthorSpan(
        val nameLength: Int,
        private val typeface: Typeface?,
        private val textSize: Float,
        private val color: Int,
        val link: ClickableSpan,
    ) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = typeface
            paint.textSize = textSize
            paint.isFakeBoldText = false
            paint.textSkewX = 0f
        }

        override fun updateDrawState(paint: TextPaint) {
            updateMeasureState(paint)
            paint.color = color
            paint.isUnderlineText = false
        }
    }
}
