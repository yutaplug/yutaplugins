package com.github.yutaplug.fixes.newbadges

import android.content.Context
import com.aliucord.Http
import com.aliucord.api.rn.user.RNUserProfile
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.RxUtils
import com.discord.api.user.UserProfile
import com.discord.models.user.User
import com.discord.utilities.rest.RestAPI
import com.discord.widgets.user.Badge
import com.github.yutaplug.fixes.Fix
import de.robv.android.xposed.XC_MethodHook
import java.util.Locale

/**
 * Aliucord's Badges core plugin shows the profile's `badges`. This replaces Aliucord's profile request with the
 * web app's query (`type=popout`), still sent as Aliucord's own Android client, parsed into the same [RNUserProfile].
 * It used to send it as the web client (Chrome on Windows) to also get newer badges like Account Age, which
 * Discord may leave out for the old RN version Aliucord reports, but that logged accounts out.
 *
 * Those badges also have an SVG `simple_icon_url`, which the core plugin prefers and BitmapFactory can't decode.
 * Their `icon` hash still has a PNG on the CDN (`badge-icons/{icon}.png`), so the badge list swaps the SVG for it.
 * The URL is carried in `Badge.objectType`, which the core plugin loads into the badge view.
 *
 * Aliucord *replaces* both methods (`userProfileGet`, `getBadgesForUser`) and is hooked first, so a normal-priority
 * hook registered after it never runs at all: both hooks here need the highest priority.
 */
internal class NewBadgesFix :
    Fix(
        "NewBadges",
        "New badges",
        "Shows newer Discord badges on profiles, like Account Age, which Discord otherwise doesn't send to Aliucord.",
        // Getting those badges logged accounts out (see profileRequest), so it stays off until it works safely.
        disabledReason = "Turned off for now: it could log you out of Discord. It will come back once it's safe.",
    ) {
    override fun start(context: Context) {
        // Runs before Aliucord's own replacement of this method (which then never runs) and does the same
        // request, but identified as the web client: the server leaves newer badges out for Aliucord's old RN version.
        patcher.patch(
            RestAPI::class.java,
            "userProfileGet",
            arrayOf(Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Long::class.javaObjectType),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val userId = param.args[0] as Long
                    val withMutualGuilds = param.args[1] as Boolean
                    val guildId = param.args[2] as Long?
                    param.result = RxUtils.create<UserProfile> { subscriber ->
                        try {
                            val req = profileRequest(
                                "/users/$userId/profile?type=popout&with_mutual_guilds=$withMutualGuilds" +
                                    (guildId?.let { "&guild_id=$it" } ?: ""),
                            )
                            val res = req.execute()
                            if (!res.ok()) {
                                if (res.statusCode != 404) {
                                    logger.debug("Error while fetching profile: ${res.statusCode}: ${res.statusMessage}")
                                    subscriber.onError(Http.HttpException(req, res))
                                    return@create
                                }
                            } else {
                                subscriber.onNext(res.json(GsonUtils.gsonRestApi, RNUserProfile::class.java))
                            }
                            subscriber.onCompleted()
                        } catch (error: Throwable) {
                            subscriber.onError(error)
                        }
                    }
                }
            },
        )

        patcher.patch(
            Badge.Companion::class.java,
            "getBadgesForUser",
            arrayOf(
                User::class.java,
                UserProfile::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Context::class.java,
            ),
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val profile = param.args[1] as? RNUserProfile
                    @Suppress("UNCHECKED_CAST")
                    val list = param.result as? List<Badge>
                    if (profile == null || list == null) return
                    if (list.none { it.icon == 0 && it.objectType?.let(::isSvg) == true }) return
                    val profileBadges = profile.badges ?: return

                    // The core plugin sets the tooltip to the profile badge's description, so match on that.
                    param.result = list.map { badge ->
                        if (badge.icon != 0 || badge.objectType?.let(::isSvg) != true) return@map badge
                        val tooltip = badge.tooltip?.toString()
                        val hash = profileBadges.firstOrNull { it.description == tooltip }?.icon
                        if (hash == null || hash.isEmpty()) return@map badge
                        Badge(0, badge.text, badge.tooltip, badge.showPremiumUpSell, "$BADGE_ICONS$hash.png")
                    }
                }
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    /**
     * Aliucord's own request, identified as the Discord Android app like every other Aliucord request.
     * Never identify as another client (this fix used to claim Chrome on Windows): Discord treats one token
     * showing up as two different clients as stolen and logs the account out.
     */
    private fun profileRequest(route: String): Http.Request = Http.Request.newDiscordRNRequest(route, "GET")

    private fun isSvg(url: String): Boolean =
        url.substringBefore('?').lowercase(Locale.ROOT).endsWith(".svg")

    private companion object {
        const val BADGE_ICONS = "https://cdn.discordapp.com/badge-icons/"
    }
}
