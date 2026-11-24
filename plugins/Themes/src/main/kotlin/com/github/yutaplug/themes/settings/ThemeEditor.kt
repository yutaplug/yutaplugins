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

package com.github.yutaplug.themes.settings

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.SettingsPage
import com.aliucord.utils.DimenUtils
import com.discord.utilities.color.ColorCompat
import com.github.yutaplug.themes.ALLOWED_RESOURCE_DOMAINS
import com.github.yutaplug.themes.SIMPLE_KEYS
import com.github.yutaplug.themes.SIMPLE_SOUND_NAMES
import com.github.yutaplug.themes.THEME_KEYS
import com.github.yutaplug.themes.ThemeFile
import com.github.yutaplug.themes.ThemeResolver
import com.github.yutaplug.themes.ThemeStore
import com.github.yutaplug.themes.Themes
import com.github.yutaplug.themes.logger
import com.lytefast.flexinput.R
import org.json.JSONObject

/** Edits one theme file. Changes stay in memory until Save. */
internal class ThemeEditor(
    private val plugin: Themes,
    private val theme: ThemeFile,
    /** Unsaved edits carried over when the page is reopened after a theme change. */
    edits: JSONObject? = null,
) : SettingsPage(),
    ReopenablePage {
    private val json: JSONObject = edits ?: run {
        runCatching { theme.convertIfLegacy() }
        theme.json().apply { THEME_KEYS.forEach { if (optJSONObject(it) == null) put(it, JSONObject()) } }
    }

    override fun reopen() = ThemeEditor(plugin, theme, json)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Theme Editor")
        setActionBarSubtitle(theme.name)
        linearLayout.setPadding(0, 0, 0, DimenUtils.dpToPx(16))
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(requireContext(), R.b.colorBackgroundPrimary))
        addHeaderButton("Save", Utils.getResId("ic_check_white_24dp", "drawable")) {
            save()
            true
        }

        val hosts = ALLOWED_RESOURCE_DOMAINS.joinToString()
        note(
            "Images, fonts and sounds must be https links on: $hosts, or files from your device: tap the " +
                "folder button in a field to pick one. Expiring Discord attachment links are renewed automatically.",
        )
        entry("Manifest", "ic_audit_logs_24dp") {
            FormPage(
                "Manifest",
                arrayOf("name", "author", "version", "license", "updater"),
                json.getJSONObject("manifest"),
                Validators::manifest,
            )
        }
        entry("Background", "ic_text_image_24dp") {
            FormPage(
                "Background",
                arrayOf("url", "overlay_alpha", "blur_radius"),
                json.getJSONObject("background"),
                Validators::background,
            ) { key -> ThemeFileKind.IMAGE.takeIf { key == "url" } }
        }
        entry("Fonts", "ic_edit_24dp") {
            val names = arrayOf("*") + ThemeResolver.fontIds().keys.sorted()
            FormPage("Fonts", names, json.getJSONObject("fonts"), Validators::url) { ThemeFileKind.FONT }
        }
        entry("Sounds", "ic_sound_24dp") {
            FormPage("Sounds", SIMPLE_SOUND_NAMES, json.getJSONObject("raws"), Validators::url) { ThemeFileKind.SOUND }
        }
        entry("Simple colors", "ic_accessibility_24dp") {
            ColorListPage(ColorListPage.Type.SIMPLE, json.getJSONObject("simple_colors")) { SIMPLE_KEYS.toList() }
        }
        entry("Colors", "ic_theme_24dp") {
            ColorListPage(ColorListPage.Type.COLORS, json.getJSONObject("colors")) {
                R.c::class.java.declaredFields.map { it.name }.sorted()
            }
        }
        entry("Drawable tints", "ic_emoji_24dp") {
            ColorListPage(ColorListPage.Type.DRAWABLES, json.getJSONObject("drawable_tints")) {
                R.e::class.java.declaredFields.map { it.name }.sorted()
            }
        }
        entry("Icons", "ic_flex_input_image_24dp_dark") {
            IconListPage(json.getJSONObject("icons")) { R.e::class.java.declaredFields.map { it.name }.sorted() }
        }
        divider()
        action("Save", "Write the theme file and apply it if enabled") { save() }
        action("Discard changes", null) {
            ConfirmDialog()
                .apply {
                    setTitle("Discard changes?")
                    setDescription("Unsaved edits to ${this@ThemeEditor.theme.name} will be lost.")
                    setIsDangerous(true)
                    setOnOkListener {
                        dismiss()
                        close()
                    }
                }.show(parentFragmentManager, "ThemesDiscard")
        }
    }

    private fun entry(title: String, icon: String, page: () -> SettingsPage) {
        val ctx = requireContext()
        add(
            TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                text = title
                ContextCompat.getDrawable(ctx, Utils.getResId(icon, "drawable"))?.mutate()?.let {
                    it.setTint(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(it, null, null, null)
                }
                setOnClickListener { Utils.openPageWithProxy(it.context, page()) }
            },
        )
    }

    private fun save() {
        try {
            theme.write(json)
            ThemeStore.loadThemes()
            if (ThemeStore.isEnabled(theme)) plugin.reload()
            Utils.showToast("Saved ${theme.name}")
        } catch (error: Throwable) {
            logger.errorToast("Failed to save ${theme.name}", error)
        }
    }
}
