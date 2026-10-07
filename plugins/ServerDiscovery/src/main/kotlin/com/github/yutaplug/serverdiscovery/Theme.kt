package com.github.yutaplug.serverdiscovery

import android.content.Context
import android.util.TypedValue

/** The theme's ripple color, like `selectableItemBackground`: dark on light themes and light on dark ones. */
internal fun rippleColor(context: Context): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.colorControlHighlight, value, true)) {
        if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    } else {
        0x33808080
    }
}
