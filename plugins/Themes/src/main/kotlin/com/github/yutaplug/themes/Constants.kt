/*
 * Based on Ven's Aliucord Plugins (Themer)
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified for the Themes plugin.
 */

package com.github.yutaplug.themes

import com.aliucord.Constants
import com.aliucord.Logger
import com.aliucord.PluginManager
import java.io.File
import java.util.regex.Pattern

internal val logger = Logger("Themes")

internal enum class TransparencyMode(
    val value: Int,
    val label: String,
    val description: String,
) {
    NONE(0, "None", "No transparency"),
    CHAT(1, "Chat", "Chat is transparent"),
    CHAT_SETTINGS(2, "Chat & Settings", "Chat and Settings pages are transparent"),
    FULL(3, "Full", "Everything is transparent. Only works with themes made for it"),
    ;

    companion object {
        fun from(i: Int) = values().firstOrNull { it.value == i } ?: CHAT
    }
}

internal const val DEFAULT_OVERLAY_ALPHA = 150
internal val THEME_DIR = File(Constants.BASE_PATH, "themes")

internal const val THEME_DEV_CHANNEL = 868419532992172073L
internal const val THEME_SUPPORT_CHANNEL = 875213883776847873L

// Discord hardcodes the blocked message background instead of using a color resource.
internal const val BLOCKED_COLOR_DARK = 0xff34373c.toInt()
internal const val BLOCKED_COLOR_LIGHT = 0xfffcfcfc.toInt()

// Discord attachment links expire, so they are renewed right before downloading (see DiscordLinks).
internal val ALLOWED_RESOURCE_DOMAINS = arrayOf(
    "cdn.discordapp.com",
    "media.discordapp.net",
    "github.com",
    "raw.githubusercontent.com",
    "gitlab.com",
    "i.imgur.com",
    "i.ibb.co",
)

internal val ALLOWED_RESOURCE_DOMAINS_PATTERN: Pattern by lazy {
    val domains = ALLOWED_RESOURCE_DOMAINS.joinToString("|") { Pattern.quote(it) }
    Pattern.compile("^https://(www\\.)?($domains)/")
}

/**
 * Whether the old Themer plugin is installed and enabled. Aliucord reports a plugin as enabled when
 * it has no stored preference, which includes plugins that were never installed, so check both.
 */
internal fun isThemerActive() = runCatching {
    PluginManager.plugins.containsKey("Themer") && PluginManager.isPluginEnabled("Themer")
}.getOrDefault(false)

internal fun verifyUntrustedUrl(url: String) {
    require(DiscordLinks.isDiscordUrl(url) || ALLOWED_RESOURCE_DOMAINS_PATTERN.matcher(url).find()) {
        "URL $url is not allowed. Please use one of: ${ALLOWED_RESOURCE_DOMAINS.joinToString()}"
    }
}

internal val THEME_KEYS =
    arrayOf("manifest", "background", "fonts", "raws", "simple_colors", "colors", "drawable_tints", "icons")

internal val SIMPLE_KEYS = arrayOf(
    "accent",
    "background",
    "background_secondary",
    "mention_highlight",
    "active_channel",
    "statusbar",
    "input_background",
    "blocked_bg",
)

/** Names that are not Discord resources but are handled by dedicated hooks. */
internal val SPECIAL_COLORS = arrayOf("statusbar", "input_background", "active_channel", "blocked_bg")

internal val SIMPLE_ACCENT_NAMES = arrayOf(
    "link",
    "link_light",
    "brand_new",
    "brand_new_230",
    "brand_new_360",
    "brand_new_500",
    "brand_new_530",
    "brand_new_560",
    "brand_new_600",
    "uikit_btn_bg_color_selector_brand",
    "uikit_btn_bg_color_selector_secondary_dark",
    "uikit_btn_compound_color_selector_dark",
    "uikit_btn_compound_color_selector_light",
)

internal val SIMPLE_BG_NAMES = arrayOf(
    "dark_grey_2",
    "primary_600",
    "primary_660",
    "primary_800",
    "primary_dark_600",
    "primary_dark_630",
    "primary_dark_800",
)

internal val SIMPLE_BG_SECONDARY_NAMES = arrayOf(
    "primary_500",
    "primary_630",
    "primary_700",
    "primary_dark_660",
    "primary_dark_700",
    "input_background",
    "statusbar",
    "active_channel",
)

internal val SIMPLE_ACCENT_ATTRS = arrayOf(
    "color_brand",
    "color_brand_500",
    "colorControlBrandForeground",
    "colorControlActivated",
    "colorTextLink",
    "__alpha_10_theme_chat_mention_background",
    "theme_chat_mention_foreground",
)

internal val SIMPLE_BG_ATTRS = arrayOf(
    "colorSurface",
    "colorBackgroundFloating",
    "colorTabsBackground",
    "theme_chat_spoiler_inapp_bg",
    "primary_600",
    "primary_660",
    "primary_800",
)

internal val SIMPLE_BG_SECONDARY_ATTRS = arrayOf(
    "colorBackgroundTertiary",
    "colorBackgroundSecondary",
    "primary_700",
    "theme_chat_spoiler_bg",
)

internal val CHANNEL_OVERLAYS = arrayOf(
    "drawable_overlay_channels_selected_dark",
    "drawable_overlay_channels_selected_light",
    "drawable_overlay_channels_active_dark",
    "drawable_overlay_channels_active_light",
)

/** Theme attributes behind the special colors that Themer could only reach through dedicated hooks. */
internal val SPECIAL_ATTRS = mapOf(
    "input_background" to arrayOf("colorInputBackground"),
    "active_channel" to arrayOf("colorBackgroundModifierSelected"),
    "blocked_bg" to arrayOf("theme_chat_blocked_bg"),
)

internal val SIMPLE_SOUND_NAMES = arrayOf(
    "call_calling",
    "call_ringing",
    "deafen",
    "mute",
    "undeafen",
    "unmute",
    "reconnect",
    "stream_ended",
    "stream_started",
    "stream_user_joined",
    "stream_user_left",
    "user_join",
    "user_leave",
    "user_moved",
)

/** Color name to the theme attributes that use it, as Themer maps them (dark name, light name). */
private val ATTR_MAPPING_SOURCE: List<Pair<Pair<String, String>, Array<String>>> = listOf(
    ("brand_new" to "brand_new") to arrayOf("color_brand"),
    ("brand_new_360" to "brand_new_500") to arrayOf("colorControlBrandForeground"),
    ("brand_new_260" to "brand_new_530") to arrayOf("theme_chat_mention_foreground"),
    ("brand_new_500" to "brand_new_500") to arrayOf("color_brand_500"),
    ("brand_360" to "brand_500") to arrayOf("colorControlActivated"),
    ("brand_500_alpha_20" to "brand_new_160") to arrayOf("theme_chat_mention_background"),
    ("link" to "link_light") to arrayOf("colorTextLink"),
    ("mention_highlight" to "mention_highlight") to arrayOf("theme_chat_mentioned_me"),
    ("primary_dark_600" to "white") to arrayOf("colorBackgroundPrimary"),
    ("primary_dark_800" to "white") to arrayOf("colorSurface", "colorBackgroundFloating", "colorTabsBackground"),
    ("primary_600" to "primary_300") to arrayOf("primary_600", "theme_chat_spoiler_inapp_bg"),
    ("primary_630" to "white_1") to arrayOf("theme_chat_code"),
    ("primary_660" to "white_2") to arrayOf("primary_660", "theme_chat_codeblock_border"),
    ("primary_700" to "primary_light_200") to
        arrayOf("primary_700", "colorBackgroundTertiary", "colorBackgroundSecondary"),
    ("primary_800" to "primary_200") to arrayOf("primary_800"),
    ("primary_900" to "primary_100") to arrayOf("primary_900"),
    ("primary_300" to "primary_700") to arrayOf("theme_chat_spoiler_bg"),
    ("black_alpha_10" to "white_alpha_10") to arrayOf("theme_chat_spoiler_bg_visible"),
)

internal fun attrMappings(isLight: Boolean): Map<String, List<String>> {
    val map = LinkedHashMap<String, MutableList<String>>()
    ATTR_MAPPING_SOURCE.forEach { (names, attrs) ->
        val key = if (isLight) names.second else names.first
        map.getOrPut(key) { ArrayList() }.addAll(attrs)
    }
    return map
}
