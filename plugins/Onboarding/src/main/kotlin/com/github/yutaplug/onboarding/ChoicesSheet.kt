package com.github.yutaplug.onboarding

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.widgets.BottomSheet
import com.lytefast.flexinput.R

/** Multi-select dropdown answers. Aliucord's SelectDialog only picks one item, so this uses its themed bottom sheet. */
internal class ChoicesSheet(
    private val title: String,
    private val views: List<View>,
) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        addView(
            TextView(requireContext(), null, 0, R.i.UiKit_Settings_Item_Header).apply {
                text = title
                setPaddingRelative(paddingStart, paddingTop, paddingEnd, 0)
            },
        )
        for (child in views) {
            // The sheet can be rebuilt, which would otherwise add the same views twice.
            (child.parent as? ViewGroup)?.removeView(child)
            addView(child)
        }
    }
}
