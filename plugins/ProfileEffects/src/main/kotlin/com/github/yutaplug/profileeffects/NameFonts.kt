package com.github.yutaplug.profileeffects

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.RejectedExecutionException

/**
 * Display name fonts. Discord's apps bundle these, so they are downloaded once (from a pinned
 * commit of an OFL-licensed mirror with static, Android-readable TTF/OTF files) into the app's
 * files folder.
 */
internal object NameFonts {
    private const val BASE =
        "https://cdn.jsdelivr.net/gh/maj113/MaiCord@b7b784a4a1e8fce788c1bfea7217bb9ba3ac82ab/assets/fonts/"
    private const val RETRY_AFTER = 5 * 60 * 1000L
    private const val MAX_FILE = 4 * 1024 * 1024

    private val files = mapOf(
        1 to "Bangers.ttf",
        2 to "BioRhyme.ttf",
        3 to "CherryBomb.ttf",
        4 to "Chicle.ttf",
        5 to "Compagnon.otf",
        6 to "MuseoModerno.ttf",
        7 to "NeoCastel.otf",
        8 to "Pixelify.ttf",
        9 to "Ribes.otf",
        10 to "Sinistre.otf",
        12 to "ZillaSlab.ttf",
        13 to "Playpen.ttf",
        14 to "Orbitron.ttf",
        15 to "NewRocker.ttf",
        16 to "Kalam.ttf",
        17 to "Teko.ttf",
    )

    private val main = Handler(Looper.getMainLooper())
    private val loaded = HashMap<Int, Typeface>()
    private val loading = HashSet<Int>()
    private val failedAt = HashMap<Int, Long>()
    private var listener: (() -> Unit)? = null

    fun supports(fontId: Int) = files.containsKey(fontId)

    /** Called on the main thread whenever a font finishes loading. */
    fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Main thread. Returns the font if it is ready, otherwise starts loading it and returns null. */
    fun get(context: Context, fontId: Int): Typeface? {
        loaded[fontId]?.let { return it }
        val name = files[fontId] ?: return null
        if (loading.contains(fontId)) return null
        val failed = failedAt[fontId]
        if (failed != null && now() - failed < RETRY_AFTER) return null
        loading.add(fontId)
        val dir = File(context.filesDir, "profile_effects_fonts")
        try {
            EffectAssets.downloads.execute {
                val typeface = try {
                    load(dir, name)
                } catch (_: Throwable) {
                    null
                }
                main.post {
                    loading.remove(fontId)
                    if (typeface == null) {
                        failedAt[fontId] = now()
                    } else {
                        loaded[fontId] = typeface
                        failedAt.remove(fontId)
                        listener?.invoke()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            loading.remove(fontId)
        }
        return null
    }

    private fun load(dir: File, name: String): Typeface? {
        val file = File(dir, name)
        if (file.isFile && file.length() > 0) {
            try {
                return Typeface.createFromFile(file)
            } catch (_: Exception) {
                file.delete()
            }
        }
        dir.mkdirs()
        val temp = File(dir, "$name.part")
        val connection = URL(BASE + name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FILE) throw IOException("Font too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: IOException) {
            temp.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            return null
        }
        return try {
            Typeface.createFromFile(file)
        } catch (error: Exception) {
            file.delete()
            null
        }
    }

    private fun now() = System.nanoTime() / 1_000_000L
}
