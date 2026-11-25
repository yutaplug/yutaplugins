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
 * Each font uses the weight and letter spacing Discord's own `@font-face` and display name CSS
 * give it. Discord turns off synthesized bold, so a single-weight font is never thickened.
 */
internal object NameFonts {
    /** OFL-licensed mirror with static, Android-readable files of every font. */
    private const val MIRROR =
        "https://cdn.jsdelivr.net/gh/maj113/MaiCord@b7b784a4a1e8fce788c1bfea7217bb9ba3ac82ab/assets/fonts/"

    /** Google Fonts' own repository, for the weights the mirror doesn't have. */
    private const val GOOGLE =
        "https://raw.githubusercontent.com/google/fonts/6ebe737e44fd64ee736bbc6feed6136a6dbfd222/ofl/"

    /** The type foundries' own repositories, for static files at Discord's weight on Android 7 and older. */
    private const val PLAYPEN =
        "https://raw.githubusercontent.com/TypeTogether/Playpen-Sans/fe37f87a92827003ac26b2348093e00e914d9cfd/fonts/ttf/"
    private const val MUSEO_MODERNO =
        "https://raw.githubusercontent.com/Omnibus-Type/MuseoModerno/037e64e09440633e52e66fef19f7625b95996bcb/fonts/ttf/"
    private const val PIXELIFY =
        "https://raw.githubusercontent.com/eifetx/Pixelify-Sans/39df74aba80df8157546034b878e8be1eb565ced/fonts/ttf/"

    private const val RETRY_AFTER = 5 * 60 * 1000L
    private const val MAX_FILE = 4 * 1024 * 1024

    /**
     * [variable] is a Google Fonts variable font, set to [weight] on Android 8+ (older versions can't
     * set font weights). Otherwise [static], a full URL to a file at Discord's weight, is used, and
     * [file], the mirror's file, when neither works. [spacing] is Discord's letter spacing, in em.
     * [scale] makes glyphs the size Discord's own file draws them: some of Discord's files have more
     * units per em than ours (Mainframe 1220, Monkey Bars 1100, Modern 1086 against 1000).
     */
    private class Spec(
        val file: String,
        val static: String? = null,
        val variable: String? = null,
        val weight: Int = 400,
        val spacing: Float = 0f,
        val scale: Float = 1f,
    )

    // IDs 1, 2, 5 and 9 are retired: Discord draws them in its default font, and so does this plugin.
    private val specs = mapOf(
        3 to Spec("CherryBomb.ttf", spacing = 0.04f), // Sakura, 400
        4 to Spec("Chicle.ttf"), // Jellybean, 400
        6 to Spec(
            "MuseoModerno.ttf",
            static = MUSEO_MODERNO + "MuseoModerno-Medium.ttf",
            variable = "museomoderno/MuseoModerno%5Bwght%5D.ttf",
            weight = 500,
            spacing = 0.01f,
            scale = 1000f / 1086f,
        ),
        7 to Spec("NeoCastel.otf", spacing = 0.02f), // Medieval, 400
        8 to Spec( // 8Bit
            "Pixelify.ttf",
            static = PIXELIFY + "PixelifySans-Medium.ttf",
            variable = "pixelifysans/PixelifySans%5Bwght%5D.ttf",
            weight = 500,
            spacing = 0.02f,
        ),
        10 to Spec("Sinistre.otf", spacing = 0.01f), // Vampyre, 400
        12 to Spec("ZillaSlab.ttf", static = GOOGLE + "zillaslab/ZillaSlab-SemiBold.ttf", spacing = 0.03f), // Tempo, 600
        13 to Spec( // Monkey Bars
            "Playpen.ttf",
            static = PLAYPEN + "PlaypenSans-Bold.ttf",
            variable = "playpensans/PlaypenSans%5Bwght%5D.ttf",
            weight = 700,
            scale = 1000f / 1100f,
        ),
        14 to Spec("Orbitron.ttf", spacing = 0.03f, scale = 1000f / 1220f), // Mainframe; the mirror's file is the 700 cut
        15 to Spec("NewRocker.ttf"), // Headbang, 400
        16 to Spec("Kalam.ttf", static = GOOGLE + "kalam/Kalam-Bold.ttf"), // Journal, 700
        // Discord's Pricedown is licensed to Discord only; Teko at 700 stands in for it.
        17 to Spec("Teko.ttf"),
    )

    private val main = Handler(Looper.getMainLooper())
    private val loaded = HashMap<Int, Typeface>()
    private val loading = HashSet<Int>()
    private val failedAt = HashMap<Int, Long>()
    private var listener: (() -> Unit)? = null

    /** Reports fonts that fail to download or load. */
    var log: ((String, Throwable) -> Unit)? = null

    fun supports(fontId: Int) = specs.containsKey(fontId)

    /** Discord's extra letter spacing for this font, in em. */
    fun spacing(fontId: Int) = specs[fontId]?.spacing ?: 0f

    /** How much smaller or larger than the name's own size Discord draws this font. */
    fun scale(fontId: Int) = specs[fontId]?.scale ?: 1f

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
                } catch (error: Throwable) {
                    log?.invoke("Could not load name font $fontId", error)
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

    /** Worker thread. Prefers Discord's weight, falling back to the mirror's file if it can't be used. */
    private fun load(dir: File, spec: Spec): Typeface? {
        // Earlier versions cached a different weight under "bold-"; those files are never used again.
        dir.listFiles()?.forEach { if (it.name.startsWith("bold-")) it.delete() }
        if (spec.variable != null && Build.VERSION.SDK_INT >= 26) {
            try {
                val file = fetch(dir, GOOGLE + spec.variable, "variable-" + spec.file)
                val typeface = Typeface.Builder(file).setFontVariationSettings("'wght' ${spec.weight}").build()
                if (typeface != null) return typeface
            } catch (_: Exception) {
                // Use the mirror's file below.
            }
        }
        if (spec.static != null) {
            try {
                return typeface(fetch(dir, spec.static, "static-" + spec.file))
            } catch (_: Exception) {
                // Use the mirror's file below.
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
