package com.github.yutaplug.customrichpresence

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.entities.Plugin.SettingsTab
import com.aliucord.patcher.after
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreGatewayConnection
import com.discord.stores.StoreUserPresence

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class CustomRichPresence : Plugin() {
    init {
        settingsTab = SettingsTab(CustomRichPresenceSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
            .withArgs(settings)
    }

    override fun start(context: Context) {
        PresenceController.settings = settings
        PresenceController.logger = logger

        // The gateway may not be ready when a plugin is loaded. Reapply after
        // every connection so the setting also survives reconnects.
        patcher.after<StoreGatewayConnection>(
            "handleConnectionReady",
            Boolean::class.java,
        ) { param ->
            if (param.args[0] as Boolean) {
                PresenceController.apply()
            }
        }

        patcher.after<StoreUserPresence>(
            "updateSelfPresence",
            ModelUserSettings::class.java,
            List::class.java,
            Boolean::class.java,
        ) {
            PresenceController.scheduleSync()
        }

        PresenceController.apply()
    }

    override fun stop(context: Context) {
        PresenceController.clear()
        PresenceController.settings = null
        PresenceController.logger = null
        patcher.unpatchAll()
        commands.unregisterAll()
    }
}
