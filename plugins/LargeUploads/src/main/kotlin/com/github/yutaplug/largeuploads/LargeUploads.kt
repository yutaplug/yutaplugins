package com.github.yutaplug.largeuploads

import android.content.ContentResolver
import android.content.Context
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook
import com.aliucord.utils.ReflectUtils
import com.discord.restapi.utils.CountingRequestBody
import com.discord.stores.StoreStream
import com.discord.utilities.messagesend.`MessageQueue$doSend$2`
import com.discord.utilities.rest.AttachmentRequestBody
import com.discord.utilities.rest.SendUtils
import com.discord.utilities.rest.SendUtilsKt
import com.discord.widgets.chat.MessageManager
import com.lytefast.flexinput.model.Attachment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XCallback
import rx.Observable
import rx.subjects.BehaviorSubject
import java.io.IOException

@AliucordPlugin
class LargeUploads : Plugin() {
    init {
        settingsTab = SettingsTab(LargeUploadsSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val countingDelegate = CountingRequestBody::class.java.getDeclaredField("delegate").apply {
        isAccessible = true
    }
    private val countingSubject = CountingRequestBody::class.java.getDeclaredField("bytesWrittenSubject").apply {
        isAccessible = true
    }
    private val bodyResolver = AttachmentRequestBody::class.java.getDeclaredField("contentResolver").apply {
        isAccessible = true
    }
    private val bodyAttachment = AttachmentRequestBody::class.java.getDeclaredField("attachment").apply {
        isAccessible = true
    }

    /** The selected file host. Falls back to the default while the saved one is unavailable. */
    internal var host: Host
        get() = Host.fromId(settings.getString(KEY_HOST, Host.DEFAULT.id)).takeIf { it.available } ?: Host.DEFAULT
        set(value) = settings.setString(KEY_HOST, value.id)

    var catboxUserHash: String
        get() = settings.getString(KEY_CATBOX_USER_HASH, "").orEmpty().trim()
        set(value) = settings.setString(KEY_CATBOX_USER_HASH, value.trim())

    /** How long Litterbox keeps files, one of [FileHosts.LITTERBOX_TIMES]. */
    var litterboxTime: String
        get() {
            val time = settings.getString(KEY_LITTERBOX_TIME, DEFAULT_LITTERBOX_TIME)
            return if (time != null && FileHosts.LITTERBOX_TIMES.contains(time)) time else DEFAULT_LITTERBOX_TIME
        }
        set(value) = settings.setString(KEY_LITTERBOX_TIME, value)

    override fun start(context: Context) {
        patchValidation(context)
        patchSend()
    }

    /**
     * Discord (and Aliucord's UploadSize) refuse to send a message when an attachment is over the limit.
     * Raise the limit for this request when every oversized file can go to the file host instead.
     */
    private fun patchValidation(context: Context) {
        patcher.patch(
            MessageManager::class.java,
            "sendMessage",
            arrayOf(
                String::class.java,
                List::class.java,
                MessageManager.AttachmentsRequest::class.java,
                Long::class.javaObjectType,
                List::class.java,
                Boolean::class.javaPrimitiveType!!,
                kotlin.jvm.functions.Function2::class.java,
                kotlin.jvm.functions.Function2::class.java,
                kotlin.jvm.functions.Function1::class.java,
            ),
            PreHook { call ->
                val request = call.args[2] as? MessageManager.AttachmentsRequest ?: return@PreHook
                val attachments = request.attachments
                if (attachments.isNullOrEmpty()) return@PreHook
                val channelId = call.args[3] as? Long ?: StoreStream.getChannelsSelected().id
                val limit = UploadLimits.forChannel(channelId)
                val resolver = context.contentResolver
                val maxHost = host.maxBytes
                var oversized = false
                for (attachment in attachments) {
                    val size = SendUtilsKt.computeFileSizeBytes(attachment.uri, resolver)
                    if (size <= limit) continue
                    // Too large for the file host as well: keep Discord's native "file too large" dialog.
                    if (size > maxHost) return@PreHook
                    oversized = true
                }
                if (!oversized) return@PreHook
                call.args[2] = MessageManager.AttachmentsRequest(
                    request.currentFileSizeMB,
                    Int.MAX_VALUE,
                    attachments,
                )
            },
        )
    }

    /**
     * Swap oversized uploads for file host links right before the message is sent. This runs on Discord's
     * REST thread while the pending message shows its upload progress. It has to run before UploadSize's
     * hook on the same method, which would otherwise send the files to Discord.
     */
    private fun patchSend() {
        patcher.patch(
            `MessageQueue$doSend$2`::class.java,
            "call",
            arrayOf(SendUtils.SendPayload.ReadyToSend::class.java),
            object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val payload = param.args[0] as? SendUtils.SendPayload.ReadyToSend ?: return
                    if (payload.uploads.isEmpty()) return
                    val channelId = (param.thisObject as `MessageQueue$doSend$2`<*, *>).`$message`.channelId
                    try {
                        divert(payload, channelId)?.let { param.args[0] = it }
                    } catch (error: Throwable) {
                        logger.error("Upload failed", error)
                        toast("Upload failed: ${error.message ?: error.javaClass.simpleName}")
                        // Fail the send, so the message can be retried instead of going out without its files.
                        param.result = Observable.x<Any>(error)
                    }
                }
            },
        )
    }

    /** Uploads the files Discord would reject and returns the rewritten payload, or null if nothing changes. */
    private fun divert(payload: SendUtils.SendPayload.ReadyToSend, channelId: Long): SendUtils.SendPayload.ReadyToSend? {
        val host = host
        val limit = UploadLimits.forChannel(channelId)
        val keep = payload.uploads.toMutableList()
        val divert = keep.filter { it.contentLength > limit && it.contentLength <= host.maxBytes }
        keep.removeAll(divert)
        val moved = divert.toMutableList()
        // Discord also caps the total per message. Move the largest remaining files until it fits.
        var total = keep.sumOf { maxOf(it.contentLength, 0L) }
        if (total > UploadLimits.TOTAL_BYTES) {
            for (upload in keep.sortedByDescending { it.contentLength }) {
                if (total <= UploadLimits.TOTAL_BYTES) break
                if (upload.contentLength > host.maxBytes) continue
                moved += upload
                total -= upload.contentLength
            }
            keep.removeAll(moved)
        }
        if (moved.isEmpty()) return null

        val message = payload.message
        val content = message.content.orEmpty()
        // Each link is at most ~28 characters of host and path + an 8 character id + the extension, on its own line.
        val estimate = content.length + moved.sumOf { 38 + it.name.substringAfterLast('.', "").length }
        if (estimate > UploadLimits.maxMessageLength()) {
            throw IOException("the message is too long to add the file links")
        }

        toast(
            if (moved.size == 1) "Uploading ${moved[0].name} to ${host.label}" else "Uploading ${moved.size} files to ${host.label}",
        )
        val links = moved.map { upload(it, host) }
        logger.info("Uploaded ${links.size} file(s) to ${host.label}")

        val text = links.joinToString("\n")
        ReflectUtils.setField(message, "content", if (content.isEmpty()) text else content + "\n" + text)
        return SendUtils.SendPayload.ReadyToSend(message, keep)
    }

    @Suppress("UNCHECKED_CAST")
    private fun upload(upload: SendUtils.FileUpload, host: Host): String {
        val counting = upload.part.b as CountingRequestBody
        val body = countingDelegate[counting] as AttachmentRequestBody
        val resolver = bodyResolver[body] as ContentResolver
        val attachment = bodyAttachment[body] as Attachment<*>
        val subject = countingSubject[counting] as BehaviorSubject<Long>
        val progress = { sent: Long -> subject.onNext(sent) }
        val file = FileHosts.UploadFile(upload.name, upload.mimeType, upload.contentLength) {
            resolver.openInputStream(attachment.uri) ?: throw IOException("could not open ${upload.name}")
        }
        return try {
            when (host) {
                Host.POMF -> FileHosts.uploadPomf(file, progress)
                Host.CATBOX -> FileHosts.uploadCatbox(file, catboxUserHash, progress)
                Host.LITTERBOX -> FileHosts.uploadLitterbox(file, litterboxTime, progress)
            }
        } catch (error: Exception) {
            throw IOException("${host.label}: ${error.message}", error)
        }
    }

    private fun toast(text: String) {
        Utils.mainThread.post { Utils.showToast(text) }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    internal companion object {
        const val KEY_HOST = "host"
        const val KEY_CATBOX_USER_HASH = "catboxUserHash"
        const val KEY_LITTERBOX_TIME = "litterboxTime"
        const val DEFAULT_LITTERBOX_TIME = "72h"
    }
}
