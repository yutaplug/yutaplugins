package com.github.yutaplug.gamementions

import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import com.aliucord.Logger
import com.discord.api.activity.ActivityType
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

internal class Game(
    val id: Long,
    val name: String,
    val icon: String?,
    val aliases: List<String>,
) {
    // Matches Discord's own autocomplete normalization: case-, accent-, and space-insensitive.
    val keys = (listOf(name) + aliases).map(GameIndex::normalize)
}

/** Discord's public detectable-games catalog, cached on disk and searched locally (there is no search endpoint). */
internal class GameIndex(private val onLoaded: () -> Unit) {
    private val log = Logger("GameMentions")

    @Volatile private var games = emptyList<Game>()

    @Volatile private var active = true

    @Volatile private var playing = emptySet<Long>()
    private var playingAt = 0L

    val loaded get() = games.isNotEmpty()

    fun load(cache: File) {
        active = true
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL
        if (cache.exists()) readCache(cache)?.takeIf { it.isNotEmpty() }?.let(::publish)
        if (fresh && loaded) return
        try {
            val downloaded = download()
            if (!active) return
            publish(downloaded)
            writeCache(cache, downloaded)
        } catch (e: Exception) {
            log.error("Failed to fetch detectable games", e)
        }
    }

    private fun publish(list: List<Game>) {
        if (!active) return
        games = list
        onLoaded()
    }

    fun clear() {
        active = false
        games = emptyList()
        playing = emptySet()
        playingAt = 0L
    }

    /** Games matching [query]; with a blank query, games someone visible to us is playing right now. */
    fun suggestions(query: String, limit: Int): List<Game> {
        val needle = normalize(query)
        val current = playingIds()
        if (needle.isEmpty()) {
            return games.asSequence().filter { it.id in current }.distinctBy { it.name }.take(limit).toList()
        }
        val ranked = ArrayList<Pair<Int, Game>>()
        for (game in games) {
            var tier = -1
            // Index manually: Kotlin range/indexed iterators break under Discord's renamed stdlib.
            var index = 0
            while (index < game.keys.size) {
                val key = game.keys[index]
                val keyTier = when {
                    key.startsWith(needle) -> if (index == 0) 0 else 1
                    key.contains(needle) -> 2
                    else -> -1
                }
                if (keyTier >= 0 && (tier == -1 || keyTier < tier)) tier = keyTier
                index++
            }
            // Games being played right now rank above the rest of the catalog.
            if (tier >= 0) ranked.add((if (game.id in current) tier else tier + 3) to game)
        }
        return ranked
            .sortedWith(compareBy({ it.first }, { it.second.name.length }))
            .asSequence()
            .map { it.second }
            .distinctBy { it.name }
            .take(limit)
            .toList()
    }

    private fun playingIds(): Set<Long> {
        val now = SystemClock.elapsedRealtime()
        if (now - playingAt < PLAYING_TTL) return playing
        playingAt = now
        playing = try {
            @Suppress("UNCHECKED_CAST")
            val presences = presencesField.get(StoreStream.getPresences()) as Map<Long, Presence>
            val ids = HashSet<Long>()
            for (presence in presences.values) {
                for (activity in presence.activities ?: continue) {
                    if (activity.p() == ActivityType.PLAYING) activity.a()?.let(ids::add)
                }
            }
            ids
        } catch (e: Exception) {
            emptySet()
        }
        return playing
    }

    private fun download(): List<Game> {
        val connection = URL(GAMES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            // HttpURLConnection decompresses gzip transparently; stream the ~13 MB payload instead of buffering it.
            JsonReader(connection.inputStream.bufferedReader()).use { reader ->
                val result = ArrayList<Game>()
                reader.beginArray()
                while (reader.hasNext()) readGame(reader)?.let(result::add)
                reader.endArray()
                return result
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readGame(reader: JsonReader): Game? {
        var id: Long? = null
        var name: String? = null
        var icon: String? = null
        val aliases = ArrayList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                continue
            }
            when (field) {
                "id" -> {
                    id = reader.nextString().toLongOrNull()
                }

                "name" -> {
                    name = reader.nextString()
                }

                "icon_hash" -> {
                    icon = reader.nextString()
                }

                "aliases" -> {
                    reader.beginArray()
                    while (reader.hasNext()) aliases.add(reader.nextString())
                    reader.endArray()
                }

                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        val cleanName = name?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
        return Game(id ?: return null, cleanName, icon, aliases.map(::clean).filter { it.isNotEmpty() })
    }

    private fun readCache(cache: File): List<Game>? = try {
        cache.useLines { lines ->
            lines
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                    Game(id, parts[2], parts[1].ifEmpty { null }, parts.drop(3))
                }.toList()
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(cache: File, list: List<Game>) {
        try {
            val temp = File(cache.parentFile, "${cache.name}.tmp")
            temp.bufferedWriter().use { writer ->
                for (game in list) {
                    writer.write("${game.id}\t${game.icon.orEmpty()}\t${game.name}")
                    for (alias in game.aliases) writer.write("\t$alias")
                    writer.write("\n")
                }
            }
            if (!temp.renameTo(cache)) temp.delete()
        } catch (e: Exception) {
            log.error("Failed to cache detectable games", e)
        }
    }

    companion object {
        private const val GAMES_URL = "https://discord.com/api/v9/games/detectable"
        private const val CACHE_TTL = 7L * 24 * 60 * 60 * 1000
        private const val PLAYING_TTL = 10_000L

        private val presencesField = StoreUserPresence::class.java.getDeclaredField("presencesSnapshot").apply {
            isAccessible = true
        }
        private val accents = Regex("\\p{Mn}+")

        private fun clean(value: String) = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

        fun normalize(value: String) = Normalizer
            .normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(accents, "")
            .replace(" ", "")
    }
}
