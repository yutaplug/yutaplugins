package com.github.yutaplug.themes

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.SparseArray
import android.util.SparseIntArray
import com.aliucord.Constants
import com.aliucord.Utils
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

/**
 * Flattens the enabled themes into a [ThemeState], following Themer's semantics:
 * - themes layer in enabled order, later ones winning key by key;
 * - within a theme: `simple_colors` first, then `colors`, then `drawable_tints`;
 * - `simple_colors` keys expand into the same color, attribute and tint lists as Themer.
 *
 * Unlike Themer, everything is keyed by resource id. Themer's by-value swap is kept only as a
 * fallback ([ThemeState.valueMap]) for colors that reach the screen without an id.
 */
internal object ThemeResolver {
    private class Collected {
        val colors = LinkedHashMap<String, Int>()
        val attrs = LinkedHashMap<String, Int>()
        val tints = LinkedHashMap<String, Int>()
        val specials = LinkedHashMap<String, Int>()
        val fonts = LinkedHashMap<String, String>()
        val raws = LinkedHashMap<String, String>()
        val icons = LinkedHashMap<String, IconEntry>()
        var background: JSONObject? = null
        val warnings = ArrayList<String>()
    }

    /**
     * Builds the state for [themes]. With [download] false, nothing touches the network and missing
     * files are listed in [ThemeState.pendingDownloads]. Must run with [ColorEngine] bypassed for the
     * calling thread so that original resource values are read.
     */
    fun resolve(context: Context, themes: List<ThemeFile>, isLight: Boolean, download: Boolean): ThemeState {
        if (themes.isEmpty()) return ThemeState.EMPTY
        val c = Collected()
        val mappings = attrMappings(isLight)
        themes.forEach { theme ->
            try {
                theme.convertIfLegacy()
                collect(theme, theme.json(), mappings, c)
            } catch (error: Throwable) {
                c.warnings += "${theme.name}: failed to load (${error.message})"
                logger.error("Failed to load theme ${theme.name}", error)
            }
        }
        return ColorEngine.bypass { build(context, c, download) }
    }

    private fun collect(theme: ThemeFile, json: JSONObject, mappings: Map<String, List<String>>, c: Collected) {
        fun warn(message: String) {
            c.warnings += "${theme.name}: $message"
        }

        fun putAttrsFor(name: String, color: Int) = mappings[name]?.forEach { c.attrs[it] = color }

        fun putColor(name: String, color: Int) {
            if (name in SPECIAL_COLORS) {
                c.specials[name] = color
                SPECIAL_ATTRS[name]?.forEach { c.attrs[it] = color }
            } else {
                c.colors[name] = color
            }
        }

        fun colorsOf(section: String, each: (String, Int) -> Unit) {
            val obj = json.optJSONObject(section) ?: return
            obj.keys().forEach { key ->
                val color = parseColor(obj.opt(key))
                if (color == null) warn("invalid color for $section.$key") else each(key, color)
            }
        }

        json.optJSONObject("background")?.let { if (it.has("url")) c.background = it }
        json.optJSONObject("fonts")?.let { fonts -> fonts.keys().forEach { c.fonts[it] = fonts.getString(it) } }
        json.optJSONObject("raws")?.let { raws -> raws.keys().forEach { c.raws[it] = raws.getString(it) } }
        json.optJSONObject("icons")?.let { icons ->
            icons.keys().forEach { name ->
                val entry = IconEntry.parse(icons.opt(name))
                if (entry == null) warn("invalid icon for icons.$name") else c.icons[name] = entry
            }
        }

        colorsOf("simple_colors") { key, v ->
            when (key) {
                "accent" -> {
                    SIMPLE_ACCENT_NAMES.forEach { putColor(it, v) }
                    SIMPLE_ACCENT_ATTRS.forEach { putSimpleAttr(c, it, v) }
                    c.tints["ic_nitro_rep"] = v
                    c.tints["drawable_voice_indicator_speaking"] = v
                }

                "background" -> {
                    SIMPLE_BG_NAMES.forEach { putColor(it, v) }
                    SIMPLE_BG_ATTRS.forEach { putSimpleAttr(c, it, v) }
                }

                "background_secondary" -> {
                    SIMPLE_BG_SECONDARY_NAMES.forEach { putColor(it, v) }
                    SIMPLE_BG_SECONDARY_ATTRS.forEach { putSimpleAttr(c, it, v) }
                    CHANNEL_OVERLAYS.forEach { c.tints[it] = v }
                }

                "mention_highlight" -> {
                    putColor("status_yellow_500", v or (0xff shl 24))
                    putAttrsFor(key, v)
                }

                "active_channel" -> {
                    CHANNEL_OVERLAYS.forEach { c.tints[it] = v }
                    putColor(key, v)
                }

                "statusbar", "input_background", "blocked_bg" -> {
                    putColor(key, v)
                }

                else -> {
                    warn("unknown simple color $key")
                }
            }
        }
        colorsOf("colors") { key, v ->
            if (key == "brand_500") c.tints["ic_nitro_rep"] = v
            putColor(key, v)
            putAttrsFor(key, v)
        }
        colorsOf("drawable_tints") { key, v -> c.tints[key] = v }
    }

    private fun putSimpleAttr(c: Collected, name: String, color: Int) {
        val prefix = "__alpha_10_"
        if (name.startsWith(prefix)) {
            c.attrs[name.removePrefix(prefix)] = (color and 0x00ffffff) or (0x1a shl 24)
        } else {
            c.attrs[name] = color
        }
    }

    private fun build(context: Context, c: Collected, download: Boolean): ThemeState {
        val warnings = c.warnings
        val pending = ArrayList<String>()

        // By name: only direct Resources.getColor(id) calls, as in Themer.
        val colorById = SparseIntArray()
        c.colors.forEach { (name, color) ->
            val id = Utils.getResId(name, "color")
            if (id == 0) warnings += "Unknown color $name" else colorById.put(id, color)
        }

        // By value, everywhere else: a value is swapped only when the theme sets the one name
        // Themer associates with it, so colors that merely share a value with a themed one keep it.
        val valueMap = SparseIntArray()
        CanonicalColors.get(context).forEach { (original, name) ->
            c.colors[name]?.let { valueMap.put(original, it) }
        }

        val attrById = SparseIntArray()
        c.attrs.forEach { (name, color) ->
            val id = Utils.getResId(name, "attr")
            if (id == 0) warnings += "Unknown attribute $name" else attrById.put(id, color)
        }

        val tintById = SparseIntArray()
        c.tints.forEach { (name, color) ->
            val id = Utils.getResId(name, "drawable")
            if (id == 0) warnings += "Unknown drawable $name" else tintById.put(id, color)
        }

        fun file(url: String, what: String): File? = try {
            if (download) ThemeStore.fetch(url) else ThemeStore.localFile(url) ?: null.also { pending += url }
        } catch (error: Throwable) {
            warnings += "Failed to load $what: ${error.message}"
            null
        }

        if (download) {
            // Renew every Discord attachment link up front, in as few requests as possible.
            val links = ArrayList<String>()
            if (ThemeStore.customFonts) links += c.fonts.values
            if (ThemeStore.customSounds) links += c.raws.values
            if (ThemeStore.customIcons) c.icons.values.filter { !it.isDrawable }.mapTo(links) { it.source }
            c.background?.optString("url")?.let { links += it }
            val missing = links.filter { DiscordLinks.isDiscordUrl(it) && ThemeStore.localFile(it) == null }
            if (missing.isNotEmpty()) {
                runCatching { DiscordLinks.renew(missing) }.onFailure { logger.error("Failed to renew Discord links", it) }
            }
        }

        val fontById = SparseArray<Typeface>()
        var defaultFont: Typeface? = null
        if (ThemeStore.customFonts) {
            val fontIds = fontIds()
            c.fonts.forEach { (name, url) ->
                val id = if (name == "*") -1 else fontIds[name]
                if (id == null) {
                    warnings += "Unknown font $name"
                    return@forEach
                }
                val typeface = file(url, "font $name")?.let {
                    runCatching { Typeface.createFromFile(it) }
                        .onFailure { error ->
                            warnings += "Font $name is not a valid font file (${error.message})"
                        }.getOrNull()
                } ?: return@forEach
                if (id == -1) defaultFont = typeface else fontById.put(id, typeface)
            }
        }

        val rawById = SparseArray<File>()
        if (ThemeStore.customSounds) {
            c.raws.forEach { (name, url) ->
                val id = Utils.getResId(name, "raw")
                if (id == 0) {
                    warnings += "Unknown sound $name"
                    return@forEach
                }
                file(url, "sound $name")?.let { rawById.put(id, it) }
            }
        }

        val iconById = SparseArray<ThemeIcon>()
        if (ThemeStore.customIcons) {
            c.icons.forEach { (name, entry) ->
                val id = Utils.getResId(name, "drawable")
                if (id == 0) {
                    warnings += "Unknown drawable $name"
                    return@forEach
                }
                if (entry.isDrawable) {
                    val target = Utils.getResId(entry.source, "drawable")
                    when {
                        target == 0 -> warnings += "Icon $name: unknown drawable ${entry.source}"
                        target != id -> iconById.put(id, ThemeIcon(name, null, target, entry.keepColors))
                    }
                    return@forEach
                }
                val source = file(entry.source, "icon $name") ?: return@forEach
                if (!ThemeIcon.isImage(source)) {
                    warnings += "Icon $name is not a PNG, JPEG, WebP or GIF image"
                    return@forEach
                }
                iconById.put(id, ThemeIcon(name, source, 0, entry.keepColors))
            }
        }

        var wallpaper: android.graphics.Bitmap? = null
        var animated: Uri? = null
        var overlayAlpha = 0
        val bg = c.background
        if (bg != null) {
            val url = bg.optString("url")
            val alpha = bg.optInt("overlay_alpha", DEFAULT_OVERLAY_ALPHA)
            val blur = bg.optDouble("blur_radius", 0.0).takeIf { !it.isNaN() } ?: 0.0
            if (alpha !in 0..0xff) {
                warnings += "overlay_alpha must be 0-255, was $alpha"
            } else if (blur !in 0.0..25.0) {
                warnings += "blur_radius must be 0-25, was $blur"
            } else if (url.isNotEmpty() && alpha != 0xff) {
                file(url, "background")?.let { source ->
                    if (url.substringBefore('?').endsWith(".gif", true)) {
                        animated = Uri.fromFile(source)
                        overlayAlpha = alpha
                    } else {
                        wallpaper = runCatching { Wallpaper.prepare(source, url, alpha, blur) }
                            .onFailure { warnings += "Failed to load background: ${it.message}" }
                            .getOrNull()
                    }
                }
            }
        }

        val outputs = HashSet<Int>()
        listOf(colorById, attrById, valueMap).forEach { map ->
            for (i in 0 until map.size()) outputs += map.valueAt(i)
        }
        c.specials.values.forEach { outputs += it }

        return ThemeState(
            colorById = colorById,
            attrById = attrById,
            valueMap = valueMap,
            outputs = outputs.toIntArray().apply { sort() },
            tintById = tintById,
            fontById = fontById,
            defaultFont = defaultFont,
            rawById = rawById,
            iconById = iconById,
            statusBar = c.specials["statusbar"],
            inputBackground = c.specials["input_background"],
            blockedBackground = c.specials["blocked_bg"],
            wallpaper = wallpaper,
            animatedWallpaper = animated,
            overlayAlpha = overlayAlpha,
            warnings = warnings.distinct(),
            pendingDownloads = pending.distinct(),
        )
    }

    /** Font names Themer accepts, from Aliucord's [Constants.Fonts]. */
    fun fontIds(): Map<String, Int> = Constants.Fonts::class.java.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                Modifier.isPublic(it.modifiers) &&
                it.type == Int::class.javaPrimitiveType
        }.associate { it.name to it.getInt(null) }
}
