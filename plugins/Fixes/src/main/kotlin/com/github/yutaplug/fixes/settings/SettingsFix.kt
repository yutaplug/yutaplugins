package com.github.yutaplug.fixes.settings

import android.content.Context
import android.content.SharedPreferences
import com.aliucord.Utils
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettings
import com.discord.utilities.persister.Persister
import com.github.yutaplug.fixes.Fix

/** Keeps explicit media choices local instead of relying on legacy settings sync. */
internal class SettingsFix : Fix(
    "SettingsFix",
    "Settings",
    "Fixes settings such as who can send you friend requests and accessibility options not applying correctly.",
) {
    private var friendSettings: FriendSettingsFix? = null

    override fun start(context: Context) {
        // Keep the existing preference file so renaming does not lose media choices.
        val preferences = context.getSharedPreferences("MediaSettingsFix", Context.MODE_PRIVATE)
        val store = StoreStream.getUserSettings()
        try {
            friendSettings = FriendSettingsFix { message, error -> logger.warn(message, error) }.also { it.start(patcher) }
            patcher.patchRequired(
                StoreUserSettings::class.java,
                "getIsAutoPlayGifsEnabled",
                hook = PreHook { frame ->
                    // Discord's getter reads SharedPreferences, but its setter writes a
                    // Persister file. Use the same source as the observable and setter.
                    frame.result = publisher<Boolean>(frame.thisObject as StoreUserSettings, GIFS_PUBLISHER).get()
                },
            )
            patcher.patchRequired(
                StoreUserSettings::class.java,
                "setIsAutoPlayGifsEnabled",
                arrayOf(Boolean::class.javaPrimitiveType!!),
                PreHook { frame ->
                    val value = frame.args[0] as Boolean
                    saveBoolean(preferences, GIFS, value)
                    // Keep the original method's return contract: the previous value.
                    frame.result = publisher<Boolean>(frame.thisObject as StoreUserSettings, GIFS_PUBLISHER)
                        .set(value, true)
                },
            )
            patchBooleanSetting(preferences, EMOJI, "setIsAnimatedEmojisEnabled", "allowAnimatedEmojisPublisher")
            patchBooleanSetting(preferences, ATTACHMENTS, "setIsAttachmentMediaInline")
            patchBooleanSetting(preferences, EMBED_MEDIA, "setIsEmbedMediaInlined")
            patchBooleanSetting(preferences, EMBEDS, "setIsRenderEmbedsEnabled")
            patcher.patchRequired(
                StoreUserSettings::class.java,
                "setStickerAnimationSettings",
                arrayOf(AppActivity::class.java, Int::class.javaPrimitiveType!!),
                PreHook { frame ->
                    val userChange = frame.args[0] != null
                    if (userChange) {
                        saveInt(preferences, STICKERS, frame.args[1] as Int)
                    } else if (preferences.contains(STICKERS)) {
                        frame.args[1] = preferences.getInt(STICKERS, 0)
                    }
                    // These controls must work even if the legacy REST endpoint fails.
                    publisher<Int>(frame.thisObject as StoreUserSettings, "stickerAnimationSettingsPublisher")
                        .set(frame.args[1] as Int, true)
                    frame.result = null
                },
            )

            // Only restore explicit choices. Until a control is changed, Discord can
            // still supply its existing account setting, including on first install.
            if (preferences.contains(GIFS)) {
                publisher<Boolean>(store, GIFS_PUBLISHER).set(preferences.getBoolean(GIFS, true), true)
            }
            if (preferences.contains(EMOJI)) store.setIsAnimatedEmojisEnabled(null, preferences.getBoolean(EMOJI, true))
            if (preferences.contains(STICKERS)) store.setStickerAnimationSettings(null, preferences.getInt(STICKERS, 0))
            if (preferences.contains(ATTACHMENTS)) store.setIsAttachmentMediaInline(null, preferences.getBoolean(ATTACHMENTS, true))
            if (preferences.contains(EMBED_MEDIA)) store.setIsEmbedMediaInlined(null, preferences.getBoolean(EMBED_MEDIA, true))
            if (preferences.contains(EMBEDS)) store.setIsRenderEmbedsEnabled(null, preferences.getBoolean(EMBEDS, true))
        } catch (error: Throwable) {
            friendSettings?.close()
            friendSettings = null
            patcher.unpatchAll()
            Utils.showToast("SettingsFix could not start: ${error.javaClass.simpleName}: ${error.message}", true)
            throw error
        }
    }

    private fun patchBooleanSetting(
        preferences: SharedPreferences,
        key: String,
        method: String,
        publisherField: String? = null,
    ) {
        patcher.patchRequired(
            StoreUserSettings::class.java,
            method,
            arrayOf(AppActivity::class.java, Boolean::class.javaPrimitiveType!!),
            PreHook { frame ->
                if (frame.args[0] != null) {
                    saveBoolean(preferences, key, frame.args[1] as Boolean)
                } else if (preferences.contains(key)) {
                    // READY and USER_SETTINGS_UPDATE use a null activity. Prevent
                    // stale server values from replacing an explicit local choice.
                    frame.args[1] = preferences.getBoolean(key, true)
                }
                if (publisherField != null) {
                    publisher<Boolean>(frame.thisObject as StoreUserSettings, publisherField)
                        .set(frame.args[1] as Boolean, true)
                    frame.result = null
                } else {
                    // Run Discord's preference write and embed-observable update,
                    // while suppressing the obsolete server write for this control.
                    frame.args[0] = null
                }
            },
        )
    }

    private fun saveBoolean(preferences: SharedPreferences, key: String, value: Boolean) {
        if (!preferences.edit().putBoolean(key, value).commit()) {
            logger.warn("Could not persist media setting: $key")
        }
    }

    private fun saveInt(preferences: SharedPreferences, key: String, value: Int) {
        if (!preferences.edit().putInt(key, value).commit()) {
            logger.warn("Could not persist media setting: $key")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> publisher(store: StoreUserSettings, field: String): Persister<T> =
        ReflectUtils.getField(store, field) as Persister<T>

    override fun stop(context: Context) {
        friendSettings?.close()
        friendSettings = null
        patcher.unpatchAll()
    }

    private companion object {
        const val GIFS = "autoplay_gifs"
        const val GIFS_PUBLISHER = "autoPlayGifsPublisher"
        const val EMOJI = "animated_emoji"
        const val STICKERS = "sticker_animation"
        const val ATTACHMENTS = "inline_attachments"
        const val EMBED_MEDIA = "inline_embed_media"
        const val EMBEDS = "render_embeds"
    }
}
