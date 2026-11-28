package com.github.yutaplug.profileeffects

import java.math.BigDecimal
import java.net.URI
import java.util.Locale

internal const val CDN = "https://cdn.discordapp.com"
internal const val EFFECT_WIDTH = 450
internal const val MAX_SURFACE_SIZE = 8191

internal data class ProfileKey(val userId: Long, val guildId: Long?) {
    fun route(): String = "/users/$userId/profile" + if (guildId != null) "?guild_id=$guildId" else ""
}

internal data class Profile(
    val effect: Product.Effect? = null,
    val frame: Product.Frame? = null,
    /** Profile theme colors (top, bottom); the guild profile's win over the global ones. */
    val theme: List<Int>? = null,
)

internal data class ProductKey(val sku: Long, val type: Int)

internal data class ProfileProducts(
    val effect: ProductKey?,
    val frame: ProductKey?,
    val theme: List<Int>?,
    val userStyle: NameStyle?,
    val memberStyle: NameStyle?,
    val hasUserStyle: Boolean,
    val hasMemberStyle: Boolean,
)

internal sealed class Product {
    data class Effect(val source: String?, val layers: List<EffectLayer>) : Product()

    data class Frame(
        val sku: Long,
        val metrics: FrameMetrics,
        val layers: List<FrameLayer>,
    ) : Product()
}

internal data class EffectLayer(
    val source: String,
    val start: Long,
    val duration: Long,
    val loop: Boolean,
    val loopDelay: Long,
    val x: Long,
    val y: Long,
    val width: Long,
    val height: Long,
    val zIndex: Long,
    /** Alternative images; Discord shows one variant (the same index on every layer) per view. */
    val variants: List<String> = emptyList(),
)

internal data class FrameMetrics(
    val innerWidth: Long,
    val overflowTop: Long,
    val overflowBottom: Long,
    val overflowHorizontal: Long,
)

// Modern Kotlin enum bytecode depends on EnumEntries, absent from Discord's Kotlin 1.5 runtime.
internal object Anchor {
    const val TOP = 0
    const val BOTTOM = 1
    const val CENTER = 2
}

internal data class FrameLayer(
    val id: String,
    val front: Boolean,
    val anchor: Int,
    val rail: Boolean,
    /** Spans the card from its top edge, clipped to it, instead of sitting above it like a staple. */
    val border: Boolean = false,
)

/** Tolerate older named enums and newer numeric enums without rounding Discord snowflakes. */
internal object ProfileData {
    private const val MAX_COORDINATE = 10_000L
    private const val MAX_TIME = 86_400_000L

    fun products(body: Map<*, *>): ProfileProducts {
        val global = body["user_profile"] as? Map<*, *>
        val guild = body["guild_member_profile"] as? Map<*, *>
        val user = body["user"] as? Map<*, *>
        val member = body["guild_member"] as? Map<*, *>
        return ProfileProducts(
            sku(guild, "profile_effect", 1) ?: sku(global, "profile_effect", 1),
            sku(guild, "profile_frame", 3) ?: sku(global, "profile_frame", 3),
            theme(guild) ?: theme(global),
            NameStyle.parse(user?.get("display_name_styles")),
            NameStyle.parse(member?.get("display_name_styles")),
            user?.containsKey("display_name_styles") == true,
            member?.containsKey("display_name_styles") == true,
        )
    }

    private fun theme(profile: Map<*, *>?): List<Int>? {
        val colors = Json.colors(profile?.get("theme_colors")) ?: return null
        if (colors.size < 2) return null
        return listOf(colors[0], colors[1])
    }

    private fun sku(profile: Map<*, *>?, field: String, type: Int): ProductKey? {
        if (profile == null) return null
        val raw = profile[field]
        val direct = positiveId(if (raw is Map<*, *>) raw["sku_id"] else raw)
        if (direct != null) return ProductKey(direct, type)
        for (item in list(profile["collectibles"])) {
            val collectible = item as? Map<*, *> ?: continue
            if (integer(collectible["type"]) != type.toLong()) continue
            val id = positiveId(collectible["sku_id"]) ?: continue
            return ProductKey(id, type)
        }
        return null
    }

    /**
     * The collectible items of a product, its variants' and its bundled products', with the item
     * whose SKU is the requested one first. Variant groups keep their items under `variants`.
     */
    private fun items(key: ProductKey, body: Map<*, *>): List<Map<*, *>> {
        val result = ArrayList<Map<*, *>>()
        fun collect(product: Map<*, *>, depth: Int) {
            for (raw in list(product["items"])) (raw as? Map<*, *>)?.let { result.add(it) }
            if (depth >= 2) return
            for (raw in list(product["variants"])) (raw as? Map<*, *>)?.let { collect(it, depth + 1) }
            for (raw in list(product["bundled_products"])) (raw as? Map<*, *>)?.let { collect(it, depth + 1) }
        }
        collect(body, 0)
        val exact = result.filter { positiveId(it["sku_id"]) == key.sku }
        return exact + result.filter { positiveId(it["sku_id"]) != key.sku }
    }

    fun product(key: ProductKey, body: Map<*, *>): Product? {
        // Bundles can put a different collectible before the requested effect/frame.
        for (item in items(key, body)) {
            if (integer(item["type"]) != key.type.toLong()) continue
            when (key.type) {
                1 -> {
                    val layers = effects(item["effects"])
                    val source = imageUrl(item["reducedMotionSrc"] ?: item["reduced_motion_src"])
                        ?: imageUrl(item["staticFrameSrc"] ?: item["static_frame_src"])
                    if (source != null || layers.isNotEmpty()) {
                        return Product.Effect(source ?: layers.firstOrNull()?.source, layers)
                    }
                }

                3 -> {
                    val layers = frames(item["layers"])
                    if (layers.isNotEmpty()) {
                        return Product.Frame(
                            key.sku,
                            FrameMetrics(
                                size(item["inner_width"] ?: item["innerWidth"], 1200),
                                coordinate(item["overflow_top"] ?: item["overflowTop"], false),
                                coordinate(item["overflow_bottom"] ?: item["overflowBottom"], false),
                                coordinate(item["overflow_horizontal"] ?: item["overflowHorizontal"], false),
                            ),
                            layers,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun effects(raw: Any?): List<EffectLayer> {
        val result = ArrayList<EffectLayer>()
        for (value in list(raw)) {
            val item = value as? Map<*, *> ?: continue
            val source = imageUrl(item["src"]) ?: continue
            val position = item["position"] as? Map<*, *>
            result.add(
                EffectLayer(
                    source,
                    time(item["start"]),
                    time(item["duration"]),
                    boolean(item["loop"]),
                    time(item["loopDelay"] ?: item["loop_delay"]),
                    coordinate(position?.get("x"), true),
                    coordinate(position?.get("y"), true),
                    size(item["width"], EFFECT_WIDTH.toLong()),
                    size(item["height"], 880),
                    integer(item["zIndex"] ?: item["z_index"]) ?: 0,
                    variants(item["randomizedSources"] ?: item["randomized_sources"]),
                ),
            )
        }
        result.sortWith(Comparator { left, right -> left.zIndex.compareTo(right.zIndex) })
        return result
    }

    private fun variants(raw: Any?): List<String> {
        val result = ArrayList<String>()
        for (value in list(raw)) imageUrl((value as? Map<*, *>)?.get("src") ?: value)?.let { result.add(it) }
        return result
    }

    private fun frames(raw: Any?): List<FrameLayer> {
        val result = ArrayList<FrameLayer>()
        val seen = HashSet<String>()
        for (value in list(raw)) {
            val item = value as? Map<*, *> ?: continue
            val id = assetId(item["id"]) ?: continue
            if (!seen.add(id)) continue
            val anchor = when (enum(item["anchor"])) {
                "1", "bottom" -> Anchor.BOTTOM
                "2", "center", "middle" -> Anchor.CENTER
                else -> Anchor.TOP
            }
            val order = enum(item["order"])
            val type = enum(item["type"] ?: item["layer_type"])
            result.add(
                FrameLayer(
                    id,
                    order == "front" || order == "0",
                    anchor,
                    // Discord types layers as staple, rail or border; older data only marked rails as responsive.
                    type == "rail" || (type != "staple" && type != "border" && (boolean(item["responsive"]) || anchor == Anchor.CENTER)),
                    type == "border",
                ),
            )
        }
        return result
    }

    fun imageUrl(raw: Any?): String? {
        val text = trim(raw as? String ?: return null)
        val url = when {
            text.startsWith("//") -> "https:$text"
            text.startsWith("/") -> CDN + text
            else -> text
        }
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase(Locale.ROOT)
            if (uri.scheme != "https" ||
                uri.userInfo != null ||
                uri.port != -1 ||
                (host != "cdn.discordapp.com" && host != "media.discordapp.net")
            ) {
                null
            } else {
                uri.toASCIIString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun integer(raw: Any?): Long? = try {
        // Gson's generic Map adapter uses Double; the transport instead supplies lazy numbers.
        // Reject rounded doubles for IDs rather than requesting an unrelated product.
        BigDecimal(raw?.toString() ?: "").longValueExact()
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private fun positiveId(raw: Any?): Long? {
        if (raw is Double && kotlin.math.abs(raw) > 9_007_199_254_740_991.0) return null
        if (raw is Float && kotlin.math.abs(raw) > 16_777_215f) return null
        return integer(raw)?.takeIf { it > 0 }
    }

    /**
     * A layer's asset ID, a path segment of its CDN URL. Documented as a snowflake, but kept as text
     * so other IDs still work; only characters safe in a URL path are allowed.
     */
    private fun assetId(raw: Any?): String? {
        if (raw is Double || raw is Float) return positiveId(raw)?.toString()
        val text = integer(raw)?.takeIf { it > 0 }?.toString() ?: trim(raw?.toString() ?: return null)
        if (text.isEmpty() || text.length > 128) return null
        for (c in text) {
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.')) return null
        }
        return text.takeIf { it != "." && it != ".." }
    }

    private fun enum(raw: Any?): String = integer(raw)?.toString() ?: trim(raw?.toString() ?: "")
        .lowercase(Locale.ROOT)

    private fun boolean(raw: Any?) = raw == true || raw?.toString().equals("true", ignoreCase = true)

    private fun time(raw: Any?) = maxOf(0, minOf(integer(raw) ?: 0, MAX_TIME))

    private fun coordinate(raw: Any?, signed: Boolean) =
        maxOf(if (signed) -MAX_COORDINATE else 0, minOf(integer(raw) ?: 0, MAX_COORDINATE))

    private fun size(raw: Any?, fallback: Long) = minOf(integer(raw)?.takeIf { it > 0 } ?: fallback, MAX_COORDINATE)

    private fun list(raw: Any?): List<*> = raw as? List<*> ?: emptyList<Any>()

    private fun trim(value: String): String {
        // Avoid Kotlin text helpers backed by Discord's obfuscated primitive iterators.
        var start = 0
        var end = value.length
        while (start < end && Character.isWhitespace(value[start])) start++
        while (end > start && Character.isWhitespace(value[end - 1])) end--
        return value.substring(start, end)
    }
}
