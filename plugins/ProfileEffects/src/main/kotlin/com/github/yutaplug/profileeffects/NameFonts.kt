package com.github.yutaplug.profileeffects

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.RejectedExecutionException

/**
 * Display name fonts. Discord's apps bundle these, so they are downloaded once into the app's
 * files folder, all from pinned commits so the files never change under the plugin.
 *
 * Discord draws names bold. Where a font family has a bold cut, that cut is used (Android can't
 * thicken a single-weight font on its own); single-weight fonts stay as they are, as on Discord.
 */
internal object NameFonts {
    /** OFL-licensed mirror with static, Android-readable files of every font, regular weights. */
    private const val MIRROR =
        "https://cdn.jsdelivr.net/gh/maj113/MaiCord@b7b784a4a1e8fce788c1bfea7217bb9ba3ac82ab/assets/fonts/"

    /** Google Fonts' own repository, for the bold cuts and variable fonts. */
    private const val GOOGLE =
        "https://raw.githubusercontent.com/google/fonts/6ebe737e44fd64ee736bbc6feed6136a6dbfd222/ofl/"

    private const val RETRY_AFTER = 5 * 60 * 1000L
    private const val MAX_FILE = 4 * 1024 * 1024

    /**
     * [file] is the regular cut; [bold] a static bold cut; [variable] a variable font set to weight
     * 700 on Android 8+ (older versions use [file]).
     */
    private class Spec(
        val file: String,
        val bold: String? = null,
        val variable: String? = null,
    )

    // IDs 1, 2, 5 and 9 are retired: Discord draws them in its default font, and so does this plugin.
    private val specs = mapOf(
        3 to Spec("CherryBomb.ttf"),
        4 to Spec("Chicle.ttf"),
        6 to Spec("MuseoModerno.ttf", variable = "museomoderno/MuseoModerno%5Bwght%5D.ttf"),
        7 to Spec("NeoCastel.otf"),
        8 to Spec("Pixelify.ttf", variable = "pixelifysans/PixelifySans%5Bwght%5D.ttf"),
        10 to Spec("Sinistre.otf"),
        12 to Spec("ZillaSlab.ttf", bold = "zillaslab/ZillaSlab-Bold.ttf"),
        13 to Spec("Playpen.ttf", variable = "playpensans/PlaypenSans%5Bwght%5D.ttf"),
        // The mirror's Orbitron and Teko files are already bold.
        14 to Spec("Orbitron.ttf"),
        15 to Spec("NewRocker.ttf"),
        16 to Spec("Kalam.ttf", bold = "kalam/Kalam-Bold.ttf"),
        17 to Spec("Teko.ttf"),
    )

    private val main = Handler(Looper.getMainLooper())
    private val loaded = HashMap<Int, Typeface>()
    private val loading = HashSet<Int>()
    private val failedAt = HashMap<Int, Long>()
    private var listener: (() -> Unit)? = null

    fun supports(fontId: Int) = specs.containsKey(fontId)


    /** Called on the main thread whenever a font finishes loading. */
    fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Main thread. Returns the font if it is ready, otherwise starts loading it and returns null. */
    fun get(context: Context, fontId: Int): Typeface? {
        loaded[fontId]?.let { return it }
        val spec = specs[fontId] ?: return null
        if (loading.contains(fontId)) return null
        val failed = failedAt[fontId]
        if (failed != null && now() - failed < RETRY_AFTER) return null
        loading.add(fontId)
        val dir = File(context.filesDir, "profile_effects_fonts")
        try {
            EffectAssets.downloads.execute {
                val typeface = try {
                    load(dir, spec)
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

    /** Worker thread. Prefers the bold cut, falling back to the regular file if it can't be used. */
    private fun load(dir: File, spec: Spec): Typeface? {
        if (spec.variable != null && Build.VERSION.SDK_INT >= 26) {
            try {
                val file = fetch(dir, GOOGLE + spec.variable, "bold-" + spec.file)
                val typeface = Typeface.Builder(file).setFontVariationSettings("'wght' 700").build()
                if (typeface != null) return typeface
            } catch (_: Exception) {
                // Use the regular cut below.
            }
        }
        if (spec.bold != null) {
            try {
                return typeface(fetch(dir, GOOGLE + spec.bold, "bold-" + spec.file))
            } catch (_: Exception) {
                // Use the regular cut below.
            }
        }
        return typeface(fetch(dir, MIRROR + spec.file, spec.file))
    }

    private fun typeface(file: File): Typeface = try {
        Typeface.createFromFile(file)
    } catch (error: Exception) {
        file.delete()
        throw error
    }

    /** Returns the cached file, downloading it first if needed. */
    private fun fetch(dir: File, url: String, name: String): File {
        val file = File(dir, name)
        if (file.isFile && file.length() > 0) return file
        dir.mkdirs()
        val temp = File(dir, "$name.part")
        val connection = URL(url).openConnection() as HttpURLConnection
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
            throw IOException("Could not save $name")
        }
        return file
    }

    private fun now() = System.nanoTime() / 1_000_000L
}
