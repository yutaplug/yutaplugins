package com.github.yutaplug.fixes.newbadges

import android.content.Context
import android.util.Base64
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
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Aliucord's Badges core plugin shows the profile's `badges`, but Discord's server leaves newer badges such as
 * Account Age out of `/users/{id}/profile` for the old RN version Aliucord reports (`341.0 - rn`, build 6081), while
 * the web app gets them. So this replaces Aliucord's profile request with the web app's (`type=popout`, web client
 * super properties and build number), parsed into the same [RNUserProfile].
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
                            val req = webRequest(
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

    /** Like Aliucord's `Http.Request.newDiscordRNRequest`, but with the web client's identity, as in Discord's web app. */
    private fun webRequest(route: String): Http.Request {
        val headers = RestAPI.AppHeadersProvider.INSTANCE
        return Http.Request("https://discord.com/api/v9$route", "GET")
            .setHeader("User-Agent", WEB_USER_AGENT)
            .setHeader("X-Super-Properties", webSuperProperties)
            .setHeader("Accept-Language", headers.acceptLanguages)
            .setHeader("Accept", "*/*")
            .setHeader("Authorization", headers.authToken)
            .setHeader("X-Discord-Locale", headers.locale)
            .setHeader("X-Discord-Timezone", TimeZone.getDefault().id)
    }

    private val webSuperProperties: String by lazy {
        val json = JSONObject().apply {
            put("os", "Windows")
            put("browser", "Chrome")
            put("device", "")
            put("system_locale", Locale.getDefault().toString().replace("_", "-"))
            put("has_client_mods", false)
            put("browser_user_agent", WEB_USER_AGENT)
            put("browser_version", WEB_BROWSER_VERSION)
            put("os_version", "10")
            put("referrer", "")
            put("referring_domain", "")
            put("referrer_current", "")
            put("referring_domain_current", "")
            put("release_channel", "stable")
            put("client_build_number", WEB_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
        }
        Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    private fun isSvg(url: String): Boolean =
        url.substringBefore('?').lowercase(Locale.ROOT).endsWith(".svg")

    private companion object {
        const val BADGE_ICONS = "https://cdn.discordapp.com/badge-icons/"

        /** Discord web's build number (`BUILD_NUMBER` on discord.com/app, 2026-11-26). Newer is never a problem. */
        const val WEB_BUILD_NUMBER = 681034
        const val WEB_BROWSER_VERSION = "142.0.0.0"
        const val WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$WEB_BROWSER_VERSION Safari/537.36"
    }
}
