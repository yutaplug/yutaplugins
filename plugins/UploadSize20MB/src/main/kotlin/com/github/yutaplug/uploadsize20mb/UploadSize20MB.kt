package com.github.yutaplug.uploadsize20mb

import android.content.Context
import android.util.Base64
import com.aliucord.Http
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.RNSuperProperties
import com.discord.api.premium.PremiumTier
import com.discord.models.user.User
import com.discord.utilities.premium.PremiumUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XCallback
import org.json.JSONObject

private const val DEFAULT_MAX_FILE_SIZE = 20
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
        // The core UploadSize plugin's RN transport uses Aliucord's older
        // client identity. Discord uses these headers when selecting the
        // upload treatment, so update them at the final common header boundary.
        patcher.after<Http.Request>("setHeader", String::class.java, String::class.java) { param ->
            val request = param.thisObject as Http.Request
            if (request.conn.url.host != "discord.com") return@after

            when ((param.args[0] as String).lowercase()) {
                "user-agent" -> {
                    request.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
                }

                "x-super-properties" -> {
                    request.conn.setRequestProperty("X-Super-Properties", currentSuperProperties())
                }
            }
        }

        // Apply the values after newDiscordRNRequest has finished writing all
        // of its own headers. This also avoids depending on the order in
        // which the helper's internal setHeader calls are hooked.
        patcher.after<Http.Request>("newDiscordRNRequest", String::class.java, String::class.java) { param ->
            val request = param.result as? Http.Request ?: return@after
            if (request.conn.url.host != "discord.com") return@after

            request.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
            request.conn.setRequestProperty("X-Super-Properties", currentSuperProperties())
        }

        installFileSizeOverrides()
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private fun installFileSizeOverrides() {
        // UploadSize is a required core plugin and uses an instead-hook for
        // these methods. Use a higher priority than the core hook so the
        // result is deterministic; same-priority hooks are identity-sorted and
        // can otherwise change order between app processes.
        val guildMethod = PremiumUtils::class.java.getDeclaredMethod(
            "getGuildMaxFileSizeMB",
            Int::class.javaPrimitiveType,
        )
        patcher.patch(
            guildMethod,
            object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val tier = param.args[0] as Int
                    param.result = when (tier) {
                        2 -> NITRO_CLASSIC_UPLOAD_LIMIT_MB
                        3 -> 100
                        else -> DEFAULT_MAX_FILE_SIZE
                    }
                }
            },
        )

        val userMethod = PremiumUtils::class.java.getDeclaredMethod(
            "getMaxFileSizeMB",
            User::class.java,
        )
        patcher.patch(
            userMethod,
            object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // DM validation may pass null when there is no user model.
                    // Treat it as a free account, matching the client.
                    val user = param.args[0] as? User
                    param.result = when (user?.premiumTier) {
                        PremiumTier.TIER_0 -> NITRO_BASIC_UPLOAD_LIMIT_MB
                        PremiumTier.TIER_1 -> NITRO_CLASSIC_UPLOAD_LIMIT_MB
                        PremiumTier.TIER_2 -> NITRO_UPLOAD_LIMIT_MB
                        else -> DEFAULT_MAX_FILE_SIZE
                    }
                }
            },
        )
    }

    private var cachedSuperProperties: String? = null

    private fun currentSuperProperties(): String {
        cachedSuperProperties?.let { return it }

        return synchronized(this) {
            cachedSuperProperties ?: buildCurrentSuperProperties().also { cachedSuperProperties = it }
        }
    }

    private fun buildCurrentSuperProperties(): String {
        val properties = try {
            // RNSuperProperties touches Aliucord's settings during static
            // initialization. Keep that optional so a timing issue during
            // app startup cannot prevent the plugin's other hooks from being
            // installed.
            JSONObject(RNSuperProperties.superProperties.toString())
        } catch (throwable: Throwable) {
            logger.warn("Falling back to minimal RN super properties", throwable)
            JSONObject()
        }

        properties.put("has_client_mods", false)
        properties.put("os", "Android")
        properties.put("browser", "Discord Android")
        properties.put("client_version", CURRENT_RN_VERSION)
        properties.put("release_channel", "canaryRelease")
        properties.put("client_build_number", CURRENT_RN_BUILD_NUMBER)
        properties.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())
        return Base64.encodeToString(properties.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
