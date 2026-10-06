package com.github.yutaplug.profileboard

import android.net.Uri
import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

internal class GameMedia(
    val image: String,
    val video: String? = null,
    // Shown under a trailer's own thumbnail, which may not exist for every store asset.
    val fallbackImage: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

internal class GameStore(
    val category: Int,
    val label: String,
    val url: String,
)

internal class GameReviews(
    val openCriticScore: Int?,
    val openCriticTier: Int?,
    val openCriticUrl: String?,
    val steamRating: Double?,
    val steamCount: Int?,
    val steamUrl: String?,
)

internal class GameProfile(
    val name: String? = null,
    val icon: String? = null,
    val cover: String? = null,
    val background: String? = null,
    val rank: Int? = null,
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val releaseDate: String? = null,
    val platforms: List<String> = emptyList(),
    val media: List<GameMedia> = emptyList(),
    val stores: List<GameStore> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val reviews: GameReviews? = null,
) {
    companion object {
        private val PLATFORMS =
            listOf("PC", "Xbox", "PlayStation", "iOS", "Android", "Nintendo Switch", "Linux", "macOS")

        /** Website categories shown as store buttons rather than plain links. */
        val STORE_CATEGORIES = setOf(13, 16, 17, 20, 21, 22, 23, 15, 12)

        private val WEBSITE_LABELS = mapOf(
            1 to "Official website",
            2 to "Fandom",
            3 to "Wikipedia",
            4 to "Facebook",
            5 to "Twitter",
            6 to "Twitch",
            8 to "Instagram",
            9 to "YouTube",
            10 to "App Store",
            11 to "App Store",
            12 to "Google Play",
            13 to "Steam",
            14 to "Reddit",
            15 to "itch.io",
            16 to "Epic Games",
            17 to "GOG",
            18 to "Discord server",
            19 to "Bluesky",
            20 to "Battle.net",
            21 to "Riot Games",
            22 to "Roblox",
            23 to "Minecraft",
        )

        private val GENRES = arrayOf(
            "",
            "Action",
            "Action RPG",
            "Brawler",
            "Hack and Slash",
            "Platformer",
            "Stealth",
            "Survival",
            "Adventure",
            "Action Adventure",
            "Metroidvania",
            "Open World",
            "Psychological Horror",
            "Sandbox",
            "Survival Horror",
            "Visual Novel",
            "Driving / Racing",
            "Vehicular Combat",
            "Massively Multiplayer",
            "MMORPG",
            "Role-Playing",
            "Dungeon Crawler",
            "Roguelike",
            "Shooter",
            "Light Gun",
            "Shoot 'Em Up",
            "First-Person Shooter",
            "Dual-Joystick Shooter",
            "Simulation",
            "Flight Simulator",
            "Train Simulator",
            "Life Simulator",
            "Fishing",
            "Sports",
            "Baseball",
            "Basketball",
            "Billiards",
            "Bowling",
            "Boxing",
            "Football",
            "Golf",
            "Hockey",
            "Skateboarding / Skating",
            "Snowboarding / Skiing",
            "Soccer",
            "Track & Field",
            "Surfing / Wakeboarding",
            "Wrestling",
            "Strategy",
            "4X",
            "Artillery",
            "Real-Time Strategy",
            "Tower Defense",
            "Turn-Based Strategy",
            "Wargame",
            "MOBA",
            "Fighting",
            "Puzzle",
            "Card Game",
            "Education",
            "Fitness",
            "Gambling",
            "Music / Rhythm",
            "Party / Mini Game",
            "Pinball",
            "Trivia / Board Game",
            "Tactical",
            "Indie",
            "Arcade",
            "Point-and-Click",
        )

        /** The full game object needs auth; the public RPC application is the fallback. */
        fun load(id: Long): GameProfile? {
            val game = request("/games/$id")
            val source = game ?: request("/applications/$id/rpc") ?: return null
            val data = source.optJSONObject("supplemental_game_data") ?: JSONObject()
            val icon = string(source, "icon_hash") ?: string(source, "icon") ?: string(data, "icon_hash")
            val appCover = (string(source, "cover_image_hash") ?: string(source, "cover_image"))
                ?.let { "https://cdn.discordapp.com/app-icons/$id/$it.png?size=1024" }

            val screenshots = ArrayList<String>()
            for (hash in strings(source.optJSONArray("screenshot_hashes"))) {
                screenshots.add("https://cdn.discordapp.com/app-assets/$id/game/screenshots/$hash.png?size=1024")
            }
            for (url in strings(
                source.optJSONArray("screenshot_urls"),
            ) +
                strings(data.optJSONArray("screenshot_urls"))) {
                screenshots.add(igdb(url, "t_1080p"))
            }
            val screenshotList = screenshots.distinct()
            val artwork = strings(data.optJSONArray("artwork_urls")).firstOrNull()?.let { igdb(it, "t_1080p") }

            val media = ArrayList<GameMedia>()
            val trailerIds = HashSet<String>()
            for (trailers in listOf(source.optJSONArray("trailers"), data.optJSONArray("trailers"))) {
                for (trailer in objects(trailers)) {
                    val assetId = string(trailer, "id") ?: continue
                    if (!trailerIds.add(assetId)) continue
                    val owner = string(trailer, "application_id") ?: id.toString()
                    val base = "https://cdn.discordapp.com/app-assets/$owner/store/$assetId"
                    val mime = string(trailer, "mime_type").orEmpty()
                    val isVideo = mime.startsWith("video/")
                    media.add(
                        GameMedia(
                            image = if (isVideo) "$base.png?size=1024" else "$base.${mime.substringAfter('/', "png")}",
                            video = if (isVideo) "$base.${mime.substringAfter('/', "mp4")}" else null,
                            fallbackImage = screenshotList.firstOrNull() ?: artwork,
                            width = trailer.optInt("width").takeIf { it > 0 },
                            height = trailer.optInt("height").takeIf { it > 0 },
                        ),
                    )
                }
            }
            for (url in screenshotList) media.add(GameMedia(url))

            val stores = ArrayList<GameStore>()
            val links = ArrayList<Pair<String, String>>()
            val seen = HashSet<String>()
            for (websites in listOf(data.optJSONArray("websites"), source.optJSONArray("websites"))) {
                for (website in objects(websites)) {
                    val url = string(website, "url") ?: continue
                    if (!seen.add(url)) continue
                    val category = website.optInt("category")
                    val label = WEBSITE_LABELS[category] ?: host(url)
                    if (category in STORE_CATEGORIES) {
                        if (stores.none { it.category == category }) stores.add(GameStore(category, label, url))
                    } else {
                        links.add(label to url)
                    }
                }
            }
            val steamUrl = string(data, "steam_id")?.let { "https://store.steampowered.com/app/$it" }
            if (steamUrl != null && stores.none { it.category == 13 }) stores.add(0, GameStore(13, "Steam", steamUrl))

            val reviews = data.optJSONObject("reviews")
            val openCritic = reviews?.optJSONObject("opencritic")
            val steam = reviews?.optJSONObject("steam")
            val gameReviews = GameReviews(
                openCriticScore = openCritic?.let { int(it, "top_critic_rating") },
                openCriticTier = openCritic?.let { int(it, "tier") },
                openCriticUrl = string(data, "opencritic_url"),
                steamRating = steam?.let { double(it, "rating") },
                steamCount = steam?.let { int(it, "rating_count") },
                steamUrl = steamUrl ?: stores.firstOrNull { it.category == 13 }?.url,
            ).takeIf { it.openCriticScore != null || it.steamRating != null }

            val genreIds = ints(source.optJSONArray("genres")) + ints(data.optJSONArray("genres"))
            val released = string(data, "first_release_date")
            return GameProfile(
                name = string(source, "name") ?: string(data, "name"),
                icon = icon,
                cover = string(data, "cover_image_url")?.let { igdb(it, "t_cover_big_2x") },
                background = artwork ?: screenshotList.firstOrNull() ?: appCover,
                rank = int(source, "l30_rank") ?: int(data, "l30_rank"),
                genres = genreIds.distinct().mapNotNull { GENRES.getOrNull(it)?.takeIf(String::isNotEmpty) },
                summary = string(data, "summary_localized") ?: string(data, "summary") ?: string(source, "summary")
                    ?: string(source, "description"),
                developers = strings(data.optJSONArray("developer_names")),
                publishers = strings(data.optJSONArray("publisher_names")),
                releaseDate = released?.let(::formatDate),
                platforms = (ints(data.optJSONArray("platforms")) + ints(source.optJSONArray("platforms")))
                    .distinct()
                    .mapNotNull { PLATFORMS.getOrNull(it) },
                media = media,
                stores = stores,
                links = links,
                reviews = gameReviews,
            )
        }

        private fun request(route: String): JSONObject? = try {
            val request = try {
                Http.Request.newDiscordRNRequest(route)
            } catch (_: LinkageError) {
                Http.Request.newDiscordRequest(route)
            }
            request.use {
                it.setRequestTimeout(15_000)
                it.execute().use { response -> if (response.ok()) JSONObject(response.text()) else null }
            }
        } catch (_: Exception) {
            null
        }

        private fun string(json: JSONObject, key: String) =
            json.optString(key).takeIf { !json.isNull(key) && hasText(it) && it != "null" }

        // String.isBlank() iterates an IntRange internally, which crashes under Discord's renamed stdlib.
        private fun hasText(value: String): Boolean {
            var index = 0
            while (index < value.length) {
                if (!Character.isWhitespace(value[index])) return true
                index++
            }
            return false
        }

        private fun int(json: JSONObject, key: String) = if (json.has(key) &&
            !json.isNull(key)
        ) {
            json.optInt(key)
        } else {
            null
        }

        private fun double(json: JSONObject, key: String) =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key).takeIf { !it.isNaN() } else null

        // Index manually: iterating an IntRange crashes under Discord's renamed Kotlin stdlib.
        private fun strings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val result = ArrayList<String>(array.length())
            var index = 0
            while (index < array.length()) {
                val value = array.optString(index)
                if (hasText(value) && value != "null") result.add(value)
                index++
            }
            return result
        }

        private fun ints(array: JSONArray?): List<Int> {
            if (array == null) return emptyList()
            val result = ArrayList<Int>(array.length())
            var index = 0
            while (index < array.length()) {
                if (!array.isNull(index)) result.add(array.optInt(index))
                index++
            }
            return result
        }

        private fun objects(array: JSONArray?): List<JSONObject> {
            if (array == null) return emptyList()
            val result = ArrayList<JSONObject>(array.length())
            var index = 0
            while (index < array.length()) {
                array.optJSONObject(index)?.let(result::add)
                index++
            }
            return result
        }

        private val IGDB_SIZE = Regex("/t_[a-z0-9_]+/")

        /** IGDB image URLs are protocol-relative and default to thumbnail sizes. */
        private fun igdb(url: String, size: String): String {
            val absolute = if (url.startsWith("//")) "https:$url" else url
            return if ("igdb.com" in absolute) absolute.replace(IGDB_SIZE, "/$size/") else absolute
        }

        private fun host(url: String) = try {
            Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }

        private fun formatDate(iso: String): String = try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso.take(10))
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(date!!)
        } catch (_: Exception) {
            iso.take(10)
        }
    }
}
