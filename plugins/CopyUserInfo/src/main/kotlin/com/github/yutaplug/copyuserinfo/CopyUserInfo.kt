package com.github.yutaplug.copyuserinfo

import android.content.Context
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.users.globalName
import com.discord.stores.StoreStream
import com.discord.utilities.io.NetworkUtils
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import b.a.a.d.a as UserActionsDialog
import com.discord.api.guildmember.GuildMember as ApiGuildMember

/** Adds copy and download actions for a user's info to the profile's three dots menu (UserActionsDialog). */
@AliucordPlugin
class CopyUserInfo : Plugin() {
    private val log = Logger("CopyUserInfo")

    private class Info(
        val username: String,
        val displayName: String,
        val bio: String?,
        val avatarUrl: String,
        val bannerUrl: String?,
        val server: ServerInfo?,
    )

    private class ServerInfo(
        val nickname: String?,
        val bio: String?,
        val avatarUrl: String?,
        val bannerUrl: String?,
    )

    override fun start(context: Context) {
        patcher.patch(
            UserActionsDialog::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { param ->
                try {
                    addRows(param.thisObject as UserActionsDialog, param.args[0] as View)
                } catch (t: Throwable) {
                    log.error("Failed to add user info rows", t)
                }
            },
        )

        // Discord hides the three dots button on your own profile; show it so the copy actions are reachable.
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
    }

    private fun addRows(dialog: UserActionsDialog, root: View) {
        val container = root as? LinearLayout ?: return
        val userId = dialog.arguments?.getLong("com.discord.intent.extra.EXTRA_USER_ID", 0L) ?: 0L
        if (userId == 0L) return
        val info = loadInfo(userId, findGuildId(dialog, userId)) ?: return
        val template = root.findViewById<TextView>(Utils.getResId("user_actions_dialog_block_item", "id")) ?: return
        val ctx = root.context

        // Blocking yourself makes no sense. Discord sets the row's visibility later from its view model,
        // so detach it from the layout instead of hiding it (it stays usable as the style template).
        if (userId == StoreStream.getUsers().me.id) (template.parent as? ViewGroup)?.removeView(template)

        // With server info the menu can be taller than the screen, so keep the header pinned and scroll the rows.
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        while (container.childCount > 1) {
            val child = container.getChildAt(1)
            container.removeViewAt(1)
            rows.addView(child)
        }
        container.addView(
            ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(rows)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        fun row(text: String, iconAttr: String, onClick: () -> Unit) {
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
            // Download icons are fully opaque while Discord's menu icons are 60% alpha.
            if (iconAttr == "ic_file_download_opaque") icon?.alpha = 153
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            tv.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            tv.setOnClickListener {
                onClick()
                dialog.dismiss()
            }
            rows.addView(tv)
        }

        row("Copy Username", "ic_copy") { copy(info.username) }
        row("Copy Display Name", "ic_copy") { copy(info.displayName) }
        info.bio?.let { bio -> row("Copy Bio", "ic_copy") { copy(bio) } }
        row("Copy Profile Picture URL", "ic_copy") { copy(info.avatarUrl) }
        info.bannerUrl?.let { url -> row("Copy Banner URL", "ic_copy") { copy(url) } }
        row("Download Profile Picture", "ic_file_download_opaque") {
            download(info.avatarUrl, "${info.username}_avatar")
        }
        info.bannerUrl?.let { url ->
            row("Download Banner", "ic_file_download_opaque") { download(url, "${info.username}_banner") }
        }

        val server = info.server ?: return
        server.nickname?.let { nick -> row("Copy Server Nickname", "ic_copy") { copy(nick) } }
        server.bio?.let { bio -> row("Copy Server Bio", "ic_copy") { copy(bio) } }
        server.avatarUrl?.let { url -> row("Copy Server Profile Picture URL", "ic_copy") { copy(url) } }
        server.bannerUrl?.let { url -> row("Copy Server Banner URL", "ic_copy") { copy(url) } }
        server.avatarUrl?.let { url ->
            row("Download Server Profile Picture", "ic_file_download_opaque") {
                download(url, "${info.username}_server_avatar")
            }
        }
        server.bannerUrl?.let { url ->
            row("Download Server Banner", "ic_file_download_opaque") {
                download(url, "${info.username}_server_banner")
            }
        }
    }

    /** The guild the profile was opened from: the user sheet's guild, or else the guild of its channel. */
    private fun findGuildId(dialog: UserActionsDialog, userId: Long): Long {
        val sheet = try {
            dialog.parentFragmentManager.fragments.lastOrNull {
                it is WidgetUserSheet && it.arguments?.getLong("ARG_USER_ID") == userId
            }
        } catch (t: Throwable) {
            null
        }
        val sheetGuild = sheet?.arguments?.getLong("ARG_GUILD_ID", 0L) ?: 0L
        if (sheetGuild != 0L) return sheetGuild
        val channelId = dialog.arguments?.getLong("com.discord.intent.extra.EXTRA_CHANNEL_ID", 0L) ?: 0L
        if (channelId == 0L) return 0L
        return StoreStream.getChannels().getChannel(channelId)?.guildId ?: 0L
    }

    private fun loadServerInfo(userId: Long, guildId: Long, profileMember: ApiGuildMember?): ServerInfo? {
        if (guildId == 0L) return null
        // The sheet fetches the profile with its guild_id; the returned guild_member usually has no guild_id set.
        val pm = profileMember?.takeIf { it.f() == 0L || it.f() == guildId }
        val sm = StoreStream.getGuilds().getMember(guildId, userId)
        if (pm == null && sm == null) return null

        val nick = (sm?.nick ?: pm?.h())?.takeIf { it.trim().isNotEmpty() }
        val bio = (pm?.d() ?: sm?.bio)?.takeIf { it.trim().isNotEmpty() }
        val avatarUrl = (pm?.b() ?: sm?.avatarHash)?.takeIf { it.trim().isNotEmpty() }?.let {
            "https://cdn.discordapp.com/guilds/$guildId/users/$userId/avatars/$it.${ext(it)}?size=4096"
        }
        val bannerUrl = (pm?.c() ?: sm?.bannerHash)?.takeIf { it.trim().isNotEmpty() }?.let {
            "https://cdn.discordapp.com/guilds/$guildId/users/$userId/banners/$it.${ext(it)}?size=4096"
        }
        if (nick == null && bio == null && avatarUrl == null && bannerUrl == null) return null
        return ServerInfo(nick, bio, avatarUrl, bannerUrl)
    }

    private fun loadInfo(userId: Long, guildId: Long): Info? {
        val user = StoreStream.getUsers().users[userId]
        val profile = StoreStream.getUserProfile().getUserProfile(userId)
        val apiUser = profile?.g()
        val username = user?.username ?: apiUser?.username ?: return null

        val bio = (apiUser?.d()?.a() ?: user?.bio)?.takeIf { it.trim().isNotEmpty() }
        val avatarHash = apiUser?.a()?.a() ?: user?.avatar
        val bannerHash = apiUser?.b()?.a() ?: user?.banner
        val displayName = user?.globalName?.takeIf { it.trim().isNotEmpty() } ?: username

        val avatarUrl = if (avatarHash != null && avatarHash.trim().isNotEmpty()) {
            "https://cdn.discordapp.com/avatars/$userId/$avatarHash.${ext(avatarHash)}?size=4096"
        } else {
            "https://cdn.discordapp.com/embed/avatars/${(userId shr 22) % 6}.png"
        }
        val bannerUrl = bannerHash?.takeIf { it.trim().isNotEmpty() }?.let {
            "https://cdn.discordapp.com/banners/$userId/$it.${ext(it)}?size=4096"
        }
        val server = try {
            loadServerInfo(userId, guildId, profile?.c())
        } catch (t: Throwable) {
            log.error("Failed to load server profile", t)
            null
        }
        return Info(username, displayName, bio, avatarUrl, bannerUrl, server)
    }

    private fun ext(hash: String) = if (hash.startsWith("a_")) "gif" else "png"

    private fun copy(text: String) {
        Utils.setClipboard(text, text)
        Utils.showToast("Copied to clipboard")
    }

    private fun download(url: String, baseName: String) {
        val fileName = "$baseName.${if (url.contains(".gif")) "gif" else "png"}"
        val activity = Utils.appActivity
        activity.x.requestMediaDownload {
            NetworkUtils.downloadFile(
                activity,
                Uri.parse(url),
                fileName,
                null,
                { name -> Utils.showToast("Downloaded ${name ?: fileName}") },
                { error ->
                    log.error("Download failed", error)
                    Utils.showToast("Download failed", true)
                },
            )
        }
        Utils.showToast("Downloading $fileName")
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
