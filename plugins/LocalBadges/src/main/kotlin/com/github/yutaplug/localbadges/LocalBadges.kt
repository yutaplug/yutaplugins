package com.github.yutaplug.localbadges

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Constants
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.fragments.SelectDialog
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.widgets.user.Badge
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import java.io.File
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import b.a.a.d.a as UserActionsDialog

/** Locally adds badges to any user's profile, managed from the profile's three dots menu. */
@AliucordPlugin
class LocalBadges : Plugin() {
    private val log = Logger("LocalBadges")
    private lateinit var store: BadgeStore

    /** Badge instances we created for URL icons, mapped to their URL. Identity-keyed since [Badge] is a data class. */
    private val urlBadges: MutableMap<Badge, String> = Collections.synchronizedMap(IdentityHashMap())
    private val imageCache = HashMap<String, Bitmap>()

    /** The URL each badge ImageView currently shows, so a late download doesn't land on a recycled view. */
    private val boundUrls = WeakHashMap<ImageView, String>()

    /** The profile header that was bound last, so it can be redrawn after badges change. */
    private var lastHeader: WeakReference<UserProfileHeaderView>? = null
    private var lastState: UserProfileHeaderViewModel.ViewState.Loaded? = null

    override fun start(context: Context) {
        store = BadgeStore(settings)
        uninstallCustomBadges()

        // Kotlin resolves Badge.Companion to the nested class, so read the Java static field instead.
        val badgeCompanion = Badge::class.java.getField("Companion").get(null) as Badge.Companion
        val adapterField =UserProfileHeaderView::class.java.getDeclaredField("badgesAdapter").apply {
            isAccessible = true
        }

        // Append our badges after Discord (and Aliucord's core Badges plugin) built the list.
        patcher.patch(
            UserProfileHeaderView::class.java.getDeclaredMethod(
                "updateViewState",
                UserProfileHeaderViewModel.ViewState.Loaded::class.java,
            ),
            Hook { param ->
                val header = param.thisObject as UserProfileHeaderView
                val state = param.args[0] as UserProfileHeaderViewModel.ViewState.Loaded
                lastHeader = WeakReference(header)
                lastState = state
                try {
                    val local = store.get(state.user.id)
                    if (local.isEmpty()) return@Hook
                    val base: List<Badge> = badgeCompanion.getBadgesForUser(
                        state.user,
                        state.userProfile,
                        state.isMeUserPremium,
                        state.isMeUserVerified,
                        header.context,
                    )
                    val combined = ArrayList<Badge>(base.size + local.size)
                    combined.addAll(base)
                    for (badge in local) toDiscordBadge(badge)?.let { combined.add(it) }
                    @Suppress("UNCHECKED_CAST")
                    (adapterField.get(header) as SimpleRecyclerAdapter<Badge, *>).setData(combined)
                } catch (t: Throwable) {
                    log.error("Failed to add badges", t)
                }
            },
        )

        // Load the image of URL badges. The badge's ImageView is the view holder's root view.
        patcher.patch(
            UserProfileHeaderView.BadgeViewHolder::class.java.getDeclaredMethod("bind", Badge::class.java),
            Hook { param ->
                val holder = param.thisObject as UserProfileHeaderView.BadgeViewHolder
                val imageView = holder.itemView as? ImageView ?: return@Hook
                val url = urlBadges[param.args[0] as Badge]
                if (url == null) {
                    boundUrls.remove(imageView)
                } else {
                    boundUrls[imageView] = url
                    loadImage(imageView, url)
                }
            },
        )

        patcher.patch(
            UserActionsDialog::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { param ->
                try {
                    addRows(param.thisObject as UserActionsDialog, param.args[0] as View)
                } catch (t: Throwable) {
                    log.error("Failed to add badge rows", t)
                }
            },
        )

        // Discord hides the three dots button on your own profile; show it so you can badge yourself.
        patcher.patch(
            WidgetUserSheet::class.java.getDeclaredMethod(
                "configureUI",
                WidgetUserSheetViewModel.ViewState::class.java,
            ),
            Hook { param ->
                val state = param.args[0] as? WidgetUserSheetViewModel.ViewState.Loaded ?: return@Hook
                if (!state.isMe || state.user.isSystemUser) return@Hook
                val view = (param.thisObject as WidgetUserSheet).view ?: return@Hook
                view.findViewById<View>(Utils.getResId("user_sheet_more_button", "id"))?.visibility = View.VISIBLE
            },
        )

        hideBlockOnSelf()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        urlBadges.clear()
        refreshProfile()
        lastHeader = null
        lastState = null
    }

    private fun toDiscordBadge(badge: LocalBadge): Badge? {
        val text = badge.text.takeIf { it.trim().isNotEmpty() } ?: "Badge"
        if (badge.isUrl) {
            val result = Badge(0, null, text, false, null)
            if (urlBadges.size > 500) urlBadges.clear()
            urlBadges[result] = badge.icon
            return result
        }
        val res = Utils.getResId(badge.icon, "drawable")
        if (res == 0) return null
        return Badge(res, null, text, false, null)
    }

    private fun loadImage(imageView: ImageView, url: String) {
        // The view holder may have been rebound to another badge by the time the image loads.
        loadBitmap(url) { if (boundUrls[imageView] == url) imageView.setImageBitmap(it) }
    }

    /** Calls [onLoaded] on the main thread with the image at [url], downloading it the first time. */
    private fun loadBitmap(url: String, onLoaded: (Bitmap) -> Unit) {
        synchronized(imageCache) { imageCache[url] }?.let {
            onLoaded(it)
            return
        }
        Utils.threadPool.execute {
            try {
                val bitmap = Http.Request(url).execute().stream().use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Not an image")
                synchronized(imageCache) { imageCache[url] = bitmap }
                Utils.mainThread.post { onLoaded(bitmap) }
            } catch (t: Throwable) {
                log.warn("Failed to load badge image $url", t)
            }
        }
    }

    /**
     * Shows [dialog] with an icon at the start of each row. SelectDialog's rows are TextViews styled
     * UiKit_Settings_Item_Icon, so the icon is set as their start drawable once the list exists.
     */
    private fun showWithIcons(dialog: SelectDialog, fm: FragmentManager, tag: String, icons: List<BadgeIcon>) {
        dialog.show(fm, tag)
        try {
            // Run the show transaction now so the dialog's list exists.
            fm.executePendingTransactions()
            val list = dialog.view?.findViewById<RecyclerView>(Utils.getResId("settings_language_select_list", "id"))
                ?: return
            val size = DimenUtils.dpToPx(24)
            fun decorate(row: View) {
                val tv = row as? TextView ?: return
                val position = list.getChildAdapterPosition(row)
                val icon = icons.getOrNull(position) ?: return
                fun set(drawable: Drawable?) {
                    drawable?.setBounds(0, 0, size, size)
                    tv.setCompoundDrawablesRelative(drawable, null, null, null)
                    if (tv.compoundDrawablePadding == 0) tv.compoundDrawablePadding = DimenUtils.dpToPx(16)
                }
                when (icon) {
                    is BadgeIcon.Res -> set(icon.drawable(tv.context))
                    is BadgeIcon.Url -> {
                        set(ColorDrawable(Color.TRANSPARENT))
                        loadBitmap(icon.url) { bitmap ->
                            if (list.getChildAdapterPosition(row) == position) {
                                set(BitmapDrawable(tv.resources, bitmap))
                            }
                        }
                    }
                }
            }
            for (i in 0 until list.childCount) decorate(list.getChildAt(i))
            list.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = decorate(view)

                    override fun onChildViewDetachedFromWindow(view: View) {}
                },
            )
        } catch (t: Throwable) {
            log.error("Failed to add icons to the badge list", t)
        }
    }

    /** The icon for a row of a badge list. */
    private sealed class BadgeIcon {
        abstract class Res : BadgeIcon() {
            abstract fun drawable(context: Context): Drawable?
        }

        class Named(private val name: String) : Res() {
            override fun drawable(context: Context): Drawable? =
                Utils.getResId(name, "drawable").takeIf { it != 0 }?.let { context.getDrawable(it)?.mutate() }
        }

        class Attr(private val name: String) : Res() {
            override fun drawable(context: Context): Drawable? {
                val value = TypedValue()
                if (!context.theme.resolveAttribute(Utils.getResId(name, "attr"), value, true) || value.resourceId == 0) {
                    return null
                }
                return context.getDrawable(value.resourceId)?.mutate()
            }
        }

        class Url(val url: String) : BadgeIcon()
    }

    private fun iconOf(badge: LocalBadge): BadgeIcon =
        if (badge.isUrl) BadgeIcon.Url(badge.icon) else BadgeIcon.Named(badge.icon)

    /** Redraws the open profile so badge changes show right away. */
    private fun refreshProfile() {
        val header = lastHeader?.get() ?: return
        val state = lastState ?: return
        Utils.mainThread.post {
            try {
                if (header.isAttachedToWindow) header.updateViewState(state)
            } catch (t: Throwable) {
                log.error("Failed to refresh profile", t)
            }
        }
    }

    private fun addRows(dialog: UserActionsDialog, root: View) {
        val userId = dialog.arguments?.getLong("com.discord.intent.extra.EXTRA_USER_ID", 0L) ?: 0L
        if (userId == 0L) return
        // The remove friend row is always in the layout (CopyUserInfo may detach the block row).
        val template = root.findViewById<TextView>(Utils.getResId("user_actions_dialog_remove_friend_item", "id"))
            ?: return
        val parent = template.parent as? ViewGroup ?: return
        val ctx = root.context
        var index = parent.indexOfChild(template) + 1

        fun row(text: String, iconAttr: String, onClick: (FragmentManager) -> Unit) {
            val tv = TextView(ctx, null, 0, Utils.getResId("UiKit_ListItem_Icon", "style"))
            tv.text = text
            tv.typeface = template.typeface
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
            tv.setTextColor(template.textColors)
            tv.compoundDrawablePadding = template.compoundDrawablePadding
            tv.setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            tv.gravity = template.gravity
            template.background?.constantState?.newDrawable()?.mutate()?.let { tv.background = it }
            val value = TypedValue()
            val icon = if (ctx.theme.resolveAttribute(Utils.getResId(iconAttr, "attr"), value, true) &&
                value.resourceId != 0
            ) {
                ctx.getDrawable(value.resourceId)?.mutate()
            } else {
                null
            }
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            tv.setOnClickListener {
                // Utils.appActivity can be another, hidden activity (e.g. Aliucord's settings),
                // so show the next dialog on the activity this menu belongs to.
                val fm = dialog.activity?.supportFragmentManager ?: Utils.appActivity.supportFragmentManager
                dialog.dismiss()
                try {
                    onClick(fm)
                } catch (t: Throwable) {
                    log.error("Failed to open badge dialog", t)
                    Utils.showToast("Couldn't open the badge dialog", true)
                }
            }
            parent.addView(
                tv,
                index++,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        row("Add Badge", "ic_add_circle_outline") { fm -> pickBadge(fm, userId) }
        if (store.get(userId).isNotEmpty()) row("Remove Badge", "ic_edit") { fm -> pickBadgeToRemove(fm, userId) }
    }

    private fun pickBadge(fm: FragmentManager, userId: Long) {
        val labels = ArrayList<String>(PRESET_BADGES.size + 1)
        labels.add("Custom image URL")
        for (badge in PRESET_BADGES) labels.add(badge.label)
        val icons = ArrayList<BadgeIcon>(labels.size)
        icons.add(BadgeIcon.Attr("ic_uploads_image"))
        for (badge in PRESET_BADGES) icons.add(BadgeIcon.Url(badge.icon))
        SelectDialog().apply {
            title = "Add badge"
            items = labels.toTypedArray()
            onResultListener = { which ->
                if (which == 0) {
                    askUrl(fm, userId)
                } else {
                    val preset = PRESET_BADGES[which - 1]
                    askText(fm, userId, preset.icon, preset.label)
                }
            }
        }.let { showWithIcons(it, fm, "LocalBadgesPick", icons) }
    }

    private fun askUrl(fm: FragmentManager, userId: Long) {
        val dialog = InputDialog()
        dialog
            .setTitle("Badge image")
            .setDescription("Link to a PNG, JPG or WEBP image, for example an emoji link.")
            .setPlaceholderText("https://")
            .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            .setOnOkListener {
                val url = dialog.input.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Utils.showToast("Enter a link starting with https://")
                    return@setOnOkListener
                }
                dialog.dismiss()
                askText(fm, userId, url, "")
            }
        dialog.show(fm, "LocalBadgesUrl")
    }

    private fun askText(fm: FragmentManager, userId: Long, icon: String, default: String) {
        val dialog = InputDialog()
        dialog
            .setTitle("Badge name")
            .setDescription("Shown when the badge is tapped.")
            .setPlaceholderText("Badge name")
            .setOnOkListener {
                val text = dialog.input.trim()
                if (text.isEmpty()) {
                    Utils.showToast("Enter a badge name")
                    return@setOnOkListener
                }
                dialog.dismiss()
                store.add(userId, LocalBadge(icon, text))
                refreshProfile()
                Utils.showToast("Badge added")
            }
        dialog.setOnDialogShownListener {
            dialog.inputLayout.editText?.apply {
                setText(default)
                setSelection(length())
            }
        }
        dialog.show(fm, "LocalBadgesText")
    }

    private fun pickBadgeToRemove(fm: FragmentManager, userId: Long) {
        val badges = store.get(userId)
        if (badges.isEmpty()) return
        val labels = badges.map { it.text.takeIf { t -> t.trim().isNotEmpty() } ?: it.icon }.toTypedArray()
        SelectDialog().apply {
            title = "Remove badge"
            items = labels
            onResultListener = { which -> confirmRemove(fm, userId, which, labels[which]) }
        }.let { showWithIcons(it, fm, "LocalBadgesRemove", badges.map(::iconOf)) }
    }

    private fun confirmRemove(fm: FragmentManager, userId: Long, index: Int, name: String) {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Remove badge?")
            .setDescription("Remove \"$name\" from this profile.")
            .setIsDangerous(true)
            .setOnOkListener {
                store.remove(userId, index)
                refreshProfile()
                dialog.dismiss()
            }.show(fm, "LocalBadgesRemoveConfirm")
    }

    /** Opening the three dots menu on your own profile would offer to block yourself; hide that row. */
    private fun hideBlockOnSelf() {
        try {
            val cls = Class.forName("b.a.a.d.a\$f")
            val method = cls.declaredMethods.firstOrNull {
                it.name == "invoke" && it.parameterTypes.size == 1 && it.parameterTypes[0] != Any::class.java
            } ?: return
            val stateUser = method.parameterTypes[0].getDeclaredField("a").apply { isAccessible = true }
            val receiver = findField(cls, "receiver") ?: return
            patcher.patch(
                method,
                Hook { param ->
                    val user = stateUser.get(param.args[0]) as? User ?: return@Hook
                    if (user.id != StoreStream.getUsers().me.id) return@Hook
                    val dialog = receiver.get(param.thisObject) as? UserActionsDialog ?: return@Hook
                    dialog.view?.findViewById<View>(Utils.getResId("user_actions_dialog_block_item", "id"))
                        ?.visibility = View.GONE
                },
            )
        } catch (t: Throwable) {
            log.error("Failed to patch block row", t)
        }
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = start
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    /** LocalBadges replaces CustomBadges, so remove it like Aliucord's Uninstall button does. */
    private fun uninstallCustomBadges() {
        val plugin = PluginManager.plugins[CUSTOM_BADGES] ?: return
        try {
            if (PluginManager.isPluginEnabled(CUSTOM_BADGES)) runCatching { PluginManager.stopPlugin(CUSTOM_BADGES) }
            PluginManager.unloadPlugin(CUSTOM_BADGES)
            val file = File(Constants.PLUGINS_PATH, "${plugin.__filename ?: CUSTOM_BADGES}.zip")
            if (file.exists() && !file.delete()) {
                log.warn("Couldn't delete ${file.absolutePath}")
                return
            }
            Utils.showToast("LocalBadges replaces CustomBadges, so it was uninstalled.", true)
        } catch (t: Throwable) {
            log.error("Failed to uninstall $CUSTOM_BADGES", t)
        }
    }

    private companion object {
        const val CUSTOM_BADGES = "CustomBadges"
    }
}
