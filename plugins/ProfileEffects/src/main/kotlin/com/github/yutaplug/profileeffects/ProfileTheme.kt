package com.github.yutaplug.profileeffects

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.discord.utilities.color.ColorCompat
import com.discord.views.user.UserAvatarPresenceView
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.google.android.material.textfield.TextInputLayout

/**
 * Paints a user sheet with its owner's two profile theme colors (top to bottom), the way newer
 * Discord does. The colors are blended with the app theme's background so Discord 126.21's
 * fixed text colors stay readable; surfaces on top become translucent so the gradient shows.
 * Everything changed is remembered and restored.
 */
internal class ProfileTheme(private val header: UserProfileHeaderView) {
    private class Saved(val background: Drawable?, val elevation: Float)

    private class SavedCard(val color: ColorStateList, val elevation: Float)

    private val backgrounds = HashMap<View, Saved>()
    private val cards = HashMap<CardView, SavedCard>()
    private var avatar: UserAvatarPresenceView? = null
    private var applied: List<Int>? = null
    private var translucent = 0
    private var content: View? = null
    private var walkQueued = false

    // Sections such as activities, connections or other plugins' cards can appear after the profile loads.
    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        if (walkQueued) return@OnLayoutChangeListener
        walkQueued = true
        view.post {
            walkQueued = false
            if (applied != null) content?.let(::themeSections)
        }
    }

    fun apply(colors: List<Int>?) {
        if (colors == null || colors.size < 2) {
            restore()
            return
        }
        val scroll = scrollView() ?: return
        if (colors == applied && backgrounds.isNotEmpty()) {
            content?.let(::themeSections)
            return
        }
        val context = header.context
        val base = ColorCompat.getThemedColor(context, Utils.getResId("colorBackgroundSecondary", "attr"))
        val surface = ColorCompat.getThemedColor(context, Utils.getResId("colorBackgroundTertiary", "attr"))
        val dark = luminance(base) < 0.5f
        // Dark themes need more of the background mixed in to keep white text readable.
        val amount = if (dark) 0.5f else 0.4f
        val top = NameRenderer.mix(colors[0], base, amount)
        val bottom = NameRenderer.mix(colors[1], base, amount)
        applied = colors
        translucent = (surface and 0xFFFFFF) or ((if (dark) 0x59 else 0x80) shl 24)

        background(scroll, GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)))
        (header.parent as? View)?.let { background(it, ColorDrawable(Color.TRANSPARENT)) }
        for (name in SURFACES) find(scroll, name)?.let { background(it, ColorDrawable(Color.TRANSPARENT)) }
        background(header, ColorDrawable(Color.TRANSPARENT))
        // Cards recolored with an earlier palette take the new one.
        for (card in cards.keys) card.setCardBackgroundColor(translucent)
        val root = scroll.getChildAt(0) ?: scroll
        if (content !== root) {
            content?.removeOnLayoutChangeListener(layoutListener)
            content = root
            root.addOnLayoutChangeListener(layoutListener)
        }
        themeSections(root)
        avatar = findAvatar(header)
        avatar?.setAvatarBackgroundColor(top)
    }

    /** Makes every card and text box below the header translucent so the gradient shows through. */
    private fun themeSections(view: View) {
        if (view === header) return
        if (view is CardView && !cards.containsKey(view)) {
            cards[view] = SavedCard(view.cardBackgroundColor, view.cardElevation)
            view.setCardBackgroundColor(translucent)
            // A shadow behind a translucent card shows through it.
            view.cardElevation = 0f
        }
        if (view is TextInputLayout) {
            // A filled box layers its color over an opaque surface color, so a transparent box color
            // still paints solid. Its fill is the text field's background; clear that instead.
            view.editText?.let { field -> if (!backgrounds.containsKey(field)) background(field, ColorDrawable(Color.TRANSPARENT)) }
        }
        if (view is ViewGroup) {
            var index = 0
            while (index < view.childCount) themeSections(view.getChildAt(index++))
        }
    }

    fun restore() {
        if (applied == null && backgrounds.isEmpty() && cards.isEmpty()) return
        applied = null
        content?.removeOnLayoutChangeListener(layoutListener)
        content = null
        for ((view, saved) in backgrounds) {
            view.background = saved.background
            view.elevation = saved.elevation
        }
        backgrounds.clear()
        for ((card, saved) in cards) {
            card.setCardBackgroundColor(saved.color)
            card.cardElevation = saved.elevation
        }
        cards.clear()
        avatar?.let {
            val color = ColorCompat.getThemedColor(header.context, Utils.getResId("colorBackgroundTertiary", "attr"))
            it.setAvatarBackgroundColor(color)
        }
        avatar = null
    }

    private fun background(view: View, drawable: Drawable) {
        if (!backgrounds.containsKey(view)) backgrounds[view] = Saved(view.background, view.elevation)
        view.background = drawable
        if (view !is NestedScrollView) view.elevation = 0f
    }

    private fun scrollView(): NestedScrollView? {
        var current: View? = header
        while (current != null && current !is NestedScrollView) current = current.parent as? View
        return current
    }

    private fun find(root: View, name: String): View? {
        val id = Utils.getResId(name, "id")
        return if (id == 0) null else root.findViewById(id)
    }

    private fun findAvatar(view: View): UserAvatarPresenceView? {
        if (view is UserAvatarPresenceView) return view
        if (view is ViewGroup) {
            var index = 0
            while (index < view.childCount) {
                findAvatar(view.getChildAt(index++))?.let { return it }
            }
        }
        return null
    }

    private fun luminance(color: Int) =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private companion object {
        val SURFACES = listOf(
            "user_sheet_profile_add_to_server_container",
            "user_sheet_profile_edit_container",
            "user_sheet_profile_actions_container",
        )
    }
}
