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

import android.graphics.Color
import android.os.Build
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreStream
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class ThemeException(override val message: String) : IOException(message)

/** One theme JSON file in [THEME_DIR], in Aliucord Themer's format. */
internal class ThemeFile(val file: File) {
    var name: String = file.name.removeSuffix(".json")
        private set
    var author: String = "Anonymous"
        private set
    var version: String = "1.0.0"
        private set
    var license: String? = null
        private set
    var updaterUrl: String? = null
        private set

    /** Stable identity used for the enabled list; theme names inside the JSON may change. */
    val id: String get() = file.name

    init {
        readManifest(json())
    }

    private fun readManifest(json: JSONObject) {
        (json.optJSONObject("manifest") ?: json).run {
            optString("name").takeIf { it.isNotEmpty() }?.let { name = it }
            optString("author").takeIf { it.isNotEmpty() }?.let { author = it }
            optString("version").takeIf { it.isNotEmpty() }?.let { version = it }
            license = optString("license").takeIf { it.isNotEmpty() }
            updaterUrl = optString("updater").takeIf { it.isNotEmpty() }
        }
    }

    fun json() = JSONObject(file.readText())

    fun write(json: JSONObject) {
        file.writeText(json.toString(4))
        readManifest(json)
    }

    /** Converts Themer's legacy flat format in place. Returns whether the file was legacy. */
    fun convertIfLegacy(): Boolean {
        val json = json()
        if (THEME_KEYS.any { json.has(it) }) return false
        logger.info("Converting legacy theme $name to the new format")
        write(convertLegacy(json))
        return true
    }

    /** Downloads a newer version from the manifest's updater URL. Blocking; returns true when updated. */
    fun update(): Boolean {
        val url = updaterUrl ?: return false
        verifyUntrustedUrl(url)
        val source = if (DiscordLinks.isDiscordUrl(url)) DiscordLinks.resolve(url) else url
        val text = Http.Request(source).use { it.execute().text() }
        val remote = JSONObject(text)
        val remoteVersion = (remote.optJSONObject("manifest") ?: remote).optString("version")
        if (remoteVersion.isEmpty() || !isNewer(remoteVersion, version)) return false
        // Mirrored themes often keep the original updater link, which would bring the dead background back.
        if (DeadThemes.hasGoneBackground(remote)) {
            logger.warn("Themes: skipped update of $name, the new version's background was deleted from Discord")
            return false
        }
        file.writeText(text)
        logger.info("[$name] Updated $version -> $remoteVersion")
        readManifest(remote)
        return true
    }

    companion object {
        /** Compares dotted versions numerically ("1.10.0" is newer than "1.9.2"). */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.trim().toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun create(name: String): ThemeFile {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed.any { it in "/\\:*?\"<>|" }) {
                throw ThemeException("Theme names can't contain special characters")
            }
            val file = File(THEME_DIR, "$trimmed.json")
            if (file.exists()) throw ThemeException("A theme with this name already exists")
            val author = runCatching { StoreStream.getUsers().me.username }.getOrNull() ?: "Anonymous"
            val json = JSONObject().put(
                "manifest",
                JSONObject().put("name", trimmed).put("author", author).put("version", "1.0.0"),
            )
            THEME_KEYS.forEach { if (!json.has(it)) json.put(it, JSONObject()) }
            try {
                THEME_DIR.mkdirs()
                file.writeText(json.toString(4))
            } catch (error: Throwable) {
                throw ThemeException("Failed to create the theme file")
            }
            return ThemeFile(file)
        }

        private fun convertLegacy(json: JSONObject): JSONObject {
            val manifest = JSONObject()
            val background = JSONObject()
            val fonts = JSONObject()
            val simpleColors = JSONObject()
            val colors = JSONObject()
            val drawableTints = JSONObject()
            json.keys().asSequence().sorted().forEach { key ->
                when (key) {
                    "author", "version", "name", "license", "updater" -> {
                        manifest.put(key, json.getString(key))
                    }

                    "background_url" -> {
                        background.put("url", json.getString(key))
                    }

                    "background_transparency" -> {
                        background.put("overlay_alpha", json.getInt(key))
                    }

                    "font" -> {
                        fonts.put("*", json.getString(key))
                    }

                    "simple_accent_color" -> {
                        simpleColors.put("accent", json.get(key))
                    }

                    "simple_bg_color" -> {
                        simpleColors.put("background", json.get(key))
                    }

                    "simple_bg_secondary_color" -> {
                        simpleColors.put("background_secondary", json.get(key))
                    }

                    "mention_highlight" -> {
                        simpleColors.put(key, json.get(key))
                    }

                    "active_channel_color", "statusbar_color", "input_background_color" -> {
                        simpleColors.put(key.removeSuffix("_color"), json.get(key))
                    }

                    else -> {
                        when {
                            key.startsWith("color_") -> colors.put(key.removePrefix("color_"), json.get(key))

                            key.startsWith(
                                "drawablecolor_",
                            ) -> drawableTints.put(key.removePrefix("drawablecolor_"), json.get(key))

                            key.startsWith("font_") -> fonts.put(key.removePrefix("font_"), json.getString(key))

                            else -> logger.warn("Unrecognised legacy theme key $key")
                        }
                    }
                }
            }
            return JSONObject()
                .put("manifest", manifest)
                .put("background", background)
                .put("fonts", fonts)
                .put("simple_colors", simpleColors)
                .put("colors", colors)
                .put("drawable_tints", drawableTints)
        }
    }
}

/**
 * Reads a theme color. Themer stores signed ARGB ints; Material You names (`system_accent1_500`) and
 * `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` strings are accepted too. Returns null for unusable values.
 */
internal fun parseColor(value: Any?): Int? = when (value) {
    is Number -> {
        value.toLong().toInt()
    }

    is String -> {
        val s = value.trim()
        when {
            s.startsWith("system_") -> systemColor(s)
            s.startsWith("#") -> parseHex(s.substring(1))
            else -> s.toLongOrNull()?.toInt()
        }
    }

    else -> {
        null
    }
}

private fun parseHex(hex: String): Int? {
    val expanded = when (hex.length) {
        3 -> "ff" + hex.map { "$it$it" }.joinToString("")
        4 -> hex.map { "$it$it" }.joinToString("")
        6 -> "ff$hex"
        8 -> hex
        else -> return null
    }
    return expanded.toLongOrNull(16)?.toInt()
}

private fun systemColor(name: String): Int? {
    if (Build.VERSION.SDK_INT < 31) return null
    return runCatching {
        val id = android.R.color::class.java.getField(name).getInt(null)
        Utils.appContext.getColor(id)
    }.getOrNull()
}

/** Formats a color for display: `#AARRGGBB`. */
internal fun formatColor(color: Int) = String.format("#%08X", color)

internal fun opaque(color: Int) = color or Color.BLACK
