package com.github.yutaplug.fixes.screenrefresh

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary diagnostics for [ScreenRefreshFix]: a small log file in the app's own storage, so it survives the
 * process being killed, that the user can save to Downloads from the Fixes settings. Remove once the fix works.
 */
internal object RefreshLog {
    private const val FILE_NAME = "screenrefresh-log.txt"
    private const val MAX_CHARS = 400_000L
    private val lock = Any()
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    fun add(message: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_CHARS) {
                    // Keep the newer half.
                    val text = f.readText(Charsets.UTF_8)
                    val cut = text.indexOf('\n', text.length / 2)
                    f.writeText(if (cut < 0) "" else text.substring(cut + 1), Charsets.UTF_8)
                }
                f.appendText(time.format(Date()) + "  " + message + "\n", Charsets.UTF_8)
            } catch (_: Throwable) {
            }
        }
    }

    fun read(): String = synchronized(lock) {
        try {
            file?.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear() {
        synchronized(lock) {
            file?.delete()
        }
    }

    /** Writes [text] to Downloads and returns the file name it got. */
    fun saveToDownloads(context: Context, text: String): String {
        val name = "screen-refresh-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("could not create the file")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("could not open the file")).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else name
                } ?: name
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not open Downloads")
        val out = File(dir, name)
        out.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf("text/plain"), null)
        return out.name
    }
}
