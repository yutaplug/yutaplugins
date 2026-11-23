package com.github.yutaplug.profileeffects

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Effect layer downloads (with a small disk cache) and the shared frame decode threads. */
internal object EffectAssets {
    private const val CACHE_LIMIT = 64L * 1024 * 1024
    private const val MAX_FILE = 32 * 1024 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    val downloads: ThreadPoolExecutor = pool("ProfileEffectsDownload", 3)
    val decoders: ThreadPoolExecutor = pool("ProfileEffectsDecode", 2)

    private fun pool(name: String, size: Int): ThreadPoolExecutor {
        val pool = ThreadPoolExecutor(size, size, 15, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, name).apply { isDaemon = true }
        }
        // Idle threads exit, so nothing lingers after profiles close or the plugin stops.
        pool.allowCoreThreadTimeOut(true)
        return pool
    }

    /** Worker thread only. */
    fun load(cacheDir: File, url: String): ByteArray {
        val dir = File(cacheDir, "profile_effects")
        val file = File(dir, hash(url))
        if (file.isFile) {
            try {
                val bytes = FileInputStream(file).use { read(it) }
                file.setLastModified(System.currentTimeMillis())
                return bytes
            } catch (_: IOException) {
                file.delete()
            }
        }
        val bytes = download(url)
        try {
            val temps = File(dir, "partial")
            temps.mkdirs()
            val temp = File.createTempFile("asset", null, temps)
            FileOutputStream(temp).use { it.write(bytes) }
            if (!temp.renameTo(file)) temp.delete()
            trim(dir, temps)
        } catch (_: IOException) {
            // Caching is best effort; the bytes are still usable.
        }
        return bytes
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "image/apng,image/png,image/*;q=0.8")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            if (connection.contentLength > MAX_FILE) throw IOException("Effect asset too large: $url")
            return connection.inputStream.use { read(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > MAX_FILE) throw IOException("Effect asset too large")
        }
        return out.toByteArray()
    }

    private fun trim(dir: File, temps: File) {
        val now = System.currentTimeMillis()
        // Left behind by downloads that died mid-write.
        temps.listFiles()?.let { stale ->
            for (file in stale) if (now - file.lastModified() > 3_600_000L) file.delete()
        }
        val files = dir.listFiles() ?: return
        val kept = ArrayList<File>()
        var total = 0L
        for (file in files) {
            if (!file.isFile) continue
            kept.add(file)
            total += file.length()
        }
        if (total <= CACHE_LIMIT) return
        kept.sortWith(Comparator { left, right -> left.lastModified().compareTo(right.lastModified()) })
        for (file in kept) {
            if (total <= CACHE_LIMIT) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        var i = 0
        while (i < digest.size) {
            val value = digest[i].toInt() and 0xff
            chars[i * 2] = HEX[value ushr 4]
            chars[i * 2 + 1] = HEX[value and 0x0f]
            i++
        }
        return String(chars)
    }
}
