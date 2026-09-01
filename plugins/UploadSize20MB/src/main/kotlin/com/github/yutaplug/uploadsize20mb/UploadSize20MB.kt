package com.github.yutaplug.uploadsize20mb

import android.content.Context
import android.util.Base64
import com.aliucord.Http
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.instead
import com.aliucord.utils.RNSuperProperties
import com.discord.api.premium.PremiumTier
import com.discord.models.user.User
import com.discord.utilities.premium.PremiumUtils
import org.json.JSONObject

private const val FREE_UPLOAD_LIMIT_MB = 20
private const val NITRO_BASIC_UPLOAD_LIMIT_MB = 50
private const val NITRO_CLASSIC_UPLOAD_LIMIT_MB = 50
private const val NITRO_UPLOAD_LIMIT_MB = 500
private const val CURRENT_RN_BUILD_NUMBER = 6081
private const val CURRENT_RN_VERSION_CODE = 341200
private const val CURRENT_RN_VERSION = "341.0 - rn"
private const val CURRENT_RN_USER_AGENT = "Discord-Android/$CURRENT_RN_VERSION_CODE;RNA"

/** Backports Discord's current 20 MB free upload limit to older Discord builds. */
@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class UploadSize20MB : Plugin() {
    override fun start(context: Context) {
        val currentSuperProperties = buildCurrentSuperProperties()

        // Aliucord 2.9.1's Http helper inlines the older RN identity
        // (Discord 283.10/build 4169). Discord now uses that identity when
        // deciding which upload treatment applies to the attachment-init
        // request, so update the two request headers at the final common
        // header boundary. This keeps the real file_size unchanged.
        patcher.after<Http.Request>("setHeader", String::class.java, String::class.java) { param ->
            val request = param.thisObject as Http.Request
            if (request.conn.url.host != "discord.com") return@after

            when ((param.args[0] as String).lowercase()) {
                "user-agent" -> request.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
                "x-super-properties" -> request.conn.setRequestProperty("X-Super-Properties", currentSuperProperties)
            }
        }

        // UploadSize is a required Aliucord core plugin and patches these same
        // methods. Registering these replacements from the user plugin lets
        // the newer free-tier value win while retaining Nitro limits.
        patcher.instead<PremiumUtils>("getGuildMaxFileSizeMB", Int::class.java) { param ->
            val tier = param.args[0] as Int
            when (tier) {
                2 -> NITRO_CLASSIC_UPLOAD_LIMIT_MB
                3 -> 100
                else -> FREE_UPLOAD_LIMIT_MB
            }
        }

        patcher.instead<PremiumUtils>("getMaxFileSizeMB", User::class.java) { param ->
            val user = param.args[0] as User
            when (user.premiumTier) {
                PremiumTier.TIER_0 -> NITRO_BASIC_UPLOAD_LIMIT_MB
                PremiumTier.TIER_1 -> NITRO_CLASSIC_UPLOAD_LIMIT_MB
                PremiumTier.TIER_2 -> NITRO_UPLOAD_LIMIT_MB
                else -> FREE_UPLOAD_LIMIT_MB
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun buildCurrentSuperProperties(): String {
        val properties = JSONObject(RNSuperProperties.superProperties.toString())
        properties.put("has_client_mods", false)
        properties.put("client_version", CURRENT_RN_VERSION)
        properties.put("release_channel", "canaryRelease")
        properties.put("client_build_number", CURRENT_RN_BUILD_NUMBER)
        properties.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())
        return Base64.encodeToString(properties.toString().toByteArray(), Base64.NO_WRAP)
    }
}
