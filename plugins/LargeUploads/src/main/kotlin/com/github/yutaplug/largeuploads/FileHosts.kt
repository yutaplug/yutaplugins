package com.github.yutaplug.largeuploads

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/** The file hosts the plugin can upload to. */
internal enum class Host(
    val id: String,
    val label: String,
    val description: String,
    /** Largest accepted file, in bytes. */
    val maxBytes: Long,
    /** Why the host can't be used right now, or null if it works. */
    val unavailableReason: String?,
) {
    POMF(
        "pomf",
        "pomf.lain.la",
        "Up to 1 GB, files are kept permanently",
        1024L * 1024L * 1024L,
        null,
    ),
    CATBOX(
        "catbox",
        "catbox.moe",
        "Up to 200 MB, files are kept permanently",
        200L * 1000L * 1000L,
        "catbox.moe has paused uploads because of storage issues",
    ),
    LITTERBOX(
        "litterbox",
        "Litterbox",
        "Up to 1 GB, files are deleted after a set time",
        1000L * 1000L * 1000L,
        "Litterbox has paused uploads because of storage issues",
    ),
    ;

    val available: Boolean get() = unavailableReason == null

    companion object {
        val DEFAULT = POMF

        fun fromId(id: String?): Host {
            for (host in values()) if (host.id == id) return host
            return DEFAULT
        }
    }
}

/** Minimal clients for the hosts' upload APIs. */
internal object FileHosts {
    private const val POMF_API = "https://pomf.lain.la/upload.php"
    private const val CATBOX_API = "https://catbox.moe/user/api.php"
    private const val LITTERBOX_API = "https://litterbox.catbox.moe/resources/internals/api.php"

    /** How long Litterbox keeps a file. The API accepts exactly these values. */
    val LITTERBOX_TIMES = listOf("1h", "12h", "24h", "72h")
    val LITTERBOX_TIME_LABELS = arrayOf("1 hour", "12 hours", "24 hours", "72 hours")

    private val TITLE = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE)

    /** A file to upload. [open] is called once per attempt. */
    class UploadFile(
        val name: String,
        val mimeType: String,
        /** Size in bytes, or a negative value if unknown. */
        val length: Long,
        val open: () -> InputStream,
    )

    /** Uploads to pomf.lain.la, which answers with JSON: {"success":true,"files":[{"url":"..."}]}. */
    fun uploadPomf(file: UploadFile, progress: (Long) -> Unit): String {
        val body = upload(POMF_API, emptyMap(), "files[]", file, progress)
        val json = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw IOException(summarize(body).let { if (it.isEmpty()) "Unexpected response" else it })
        }
        if (!json.optBoolean("success")) {
            val reason = json.optString("description").ifEmpty { json.optString("errorcode") }
            throw IOException(if (reason.isEmpty()) "Upload rejected" else reason)
        }
        val url = json.optJSONArray("files")?.optJSONObject(0)?.optString("url").orEmpty()
        if (!url.startsWith("https://")) throw IOException("No link in the response")
        return url
    }

    /** Uploads to catbox.moe. Files are kept permanently in the account of [userHash], if set. */
    fun uploadCatbox(file: UploadFile, userHash: String, progress: (Long) -> Unit): String {
        val fields = linkedMapOf("reqtype" to "fileupload")
        if (userHash.isNotEmpty()) fields["userhash"] = userHash
        return plainUrl(upload(CATBOX_API, fields, "fileToUpload", file, progress))
    }

    /** Uploads to Litterbox, which deletes the file after [time] (one of [LITTERBOX_TIMES]). */
    fun uploadLitterbox(file: UploadFile, time: String, progress: (Long) -> Unit): String {
        val fields = linkedMapOf("reqtype" to "fileupload", "time" to time)
        return plainUrl(upload(LITTERBOX_API, fields, "fileToUpload", file, progress))
    }

    private fun plainUrl(body: String): String {
        if (!body.startsWith("https://")) throw IOException(summarize(body).let { if (it.isEmpty()) "Empty response" else it })
        return body
    }

    /** Streams [file] as a multipart form to [endpoint] and returns the response body of a successful request. */
    private fun upload(
        endpoint: String,
        fields: Map<String, String>,
        fileField: String,
        file: UploadFile,
        progress: (Long) -> Unit,
    ): String {
        val boundary = "----LargeUploads" + System.nanoTime()
        val head = StringBuilder()
        for ((name, value) in fields) {
            head.append("--").append(boundary).append("\r\n")
            head.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
            head.append(value).append("\r\n")
        }
        head.append("--").append(boundary).append("\r\n")
        head
            .append("Content-Disposition: form-data; name=\"")
            .append(fileField)
            .append("\"; filename=\"")
            .append(quote(file.name))
            .append("\"\r\n")
        head.append("Content-Type: ").append(file.mimeType).append("\r\n\r\n")
        val prefix = head.toString().toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        progress(0)
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.connectTimeout = 30_000
            // Large files can take a while to be processed after the upload finishes.
            conn.readTimeout = 10 * 60_000
            conn.setRequestProperty("User-Agent", "Aliucord LargeUploads")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Stream the body instead of letting HttpURLConnection buffer the whole file in memory.
            if (file.length >= 0) {
                conn.setFixedLengthStreamingMode(prefix.size + file.length + suffix.size)
            } else {
                conn.setChunkedStreamingMode(64 * 1024)
            }

            file.open().use { input ->
                conn.outputStream.use { out ->
                    out.write(prefix)
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        sent += read
                        if (sent - lastReport >= 256 * 1024) {
                            lastReport = sent
                            progress(sent)
                        }
                    }
                    out.write(suffix)
                    out.flush()
                    progress(sent)
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) {
                val reason = summarize(body)
                throw IOException(if (reason.isEmpty()) "HTTP $code" else "HTTP $code: $reason")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Short, readable error text. Error pages from the hosts' proxies are whole HTML documents. */
    private fun summarize(body: String): String {
        if (body.startsWith("{")) {
            try {
                val json = JSONObject(body)
                val reason = json.optString("description").ifEmpty { json.optString("errorcode") }
                if (reason.isNotEmpty()) return reason
            } catch (_: Exception) {
            }
        }
        if (!body.startsWith("<")) return if (body.length > 200) body.substring(0, 200) + "…" else body
        val title = TITLE.matcher(body)
        return if (title.find()) title.group(1)!!.trim() else ""
    }

    private fun quote(filename: String) =
        filename.replace("\"", "'").replace("\r", " ").replace("\n", " ")
}
