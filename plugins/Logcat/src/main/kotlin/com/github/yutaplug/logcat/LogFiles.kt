package com.github.yutaplug.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.aliucord.Utils
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies, saves and shares a log as text. Shared by the Logcat and Debug log pages. */
internal object LogFiles {
    /** Sizes to try, largest first; the clipboard's binder buffer is ~1 MB shared with other calls. */
    private val CLIPBOARD_SIZES = intArrayOf(100_000, 40_000, 15_000)

    /**
     * The clipboard goes through a binder transaction with a size limit that differs per device, and
     * a too-big clip throws, so try smaller and smaller parts of the log until one fits.
     * [export] returns the log trimmed from the start to at most the given number of characters.
     */
    fun copy(context: Context, label: String, export: (Int) -> String) {
        val app = context.applicationContext
        Utils.threadPool.execute {
            val attempts = try {
                CLIPBOARD_SIZES.map { export(it) }
            } catch (e: Throwable) {
                Utils.mainThread.post { Utils.showToast("Could not copy the log: ${describe(e)}", true) }
                return@execute
            }
            Utils.mainThread.post {
                val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                var error: Throwable? = null
                for ((i, text) in attempts.withIndex()) {
                    try {
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                        Utils.showToast(if (i == 0) "Log copied" else "Copied the latest part of the log (too big for the clipboard)")
                        return@post
                    } catch (e: Throwable) {
                        error = e
                    }
                }
                Utils.showToast("Could not copy the log: ${describe(error)}. Try Share log instead.", true)
            }
        }
    }

    /**
     * Shares the log as a file through Discord's own FileProvider. Putting the text in EXTRA_TEXT
     * goes over the binder size limit for big logs, and the share sheet then never opens.
     */
    fun share(fragment: Fragment, prefix: String, subject: String, export: () -> String) {
        val context = fragment.requireContext().applicationContext
        Utils.threadPool.execute {
            val result = try {
                val dir = File(context.filesDir, "logcat-share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, fileName(prefix))
                file.writeText(export(), Charsets.UTF_8)
                FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
            } catch (e: Throwable) {
                e
            }
            Utils.mainThread.post {
                if (result is Throwable) {
                    Utils.showToast("Could not share the log: ${describe(result)}", true)
                    return@post
                }
                val uri = result as Uri
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(subject, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val activity = fragment.activity ?: Utils.appActivity
                try {
                    activity.startActivity(
                        Intent.createChooser(intent, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (e: Throwable) {
                    Utils.showToast("Could not share the log: ${describe(e)}", true)
                }
            }
        }
    }

    fun save(context: Context, prefix: String, export: () -> String) {
        val app = context.applicationContext
        Utils.threadPool.execute {
            val result = try {
                "Saved to Downloads/" + writeToDownloads(app, fileName(prefix), export())
            } catch (e: Throwable) {
                "Could not save the log: ${describe(e)}. Try Share log instead."
            }
            Utils.mainThread.post { Utils.showToast(result, true) }
        }
    }

    /** Aliucord installs can rename the authority, so read it from the installed manifest. */
    private fun fileProviderAuthority(context: Context): String {
        @Suppress("DEPRECATION")
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers
        providers?.forEach { if (it.name == "androidx.core.content.FileProvider") return it.authority }
        return "com.discord.file-provider"
    }

    private fun fileName(prefix: String) =
        prefix + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"

    private fun describe(e: Throwable?) = e?.message ?: e?.javaClass?.simpleName ?: "unknown error"

    private fun writeToDownloads(context: Context, name: String, text: String): String {
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
        val file = File(dir, name)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        return file.name
    }
}
