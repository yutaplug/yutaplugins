package com.github.yutaplug.fixes.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.models.domain.emoji.ModelEmojiCustom
import java.util.concurrent.ConcurrentHashMap

/** Custom emoji images (first frame), downloaded once per session, for statuses and soundboard tiles. */
internal object EmojiImages {
    private const val SIZE = 64
    private val logger = Logger("Fixes/EmojiImages")
    private val images = ConcurrentHashMap<Long, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<Long>()

    /** The image if it's loaded; otherwise starts loading it, calls [onLoaded] when it's in, and returns null. */
    fun get(emojiId: Long, onLoaded: () -> Unit): Bitmap? {
        images[emojiId]?.let { return it }
        // Left in `loading` after a failure, so it isn't retried on every refresh this session.
        if (!loading.add(emojiId)) return null
        Utils.threadPool.execute {
            try {
                val url = ModelEmojiCustom.getImageUri(emojiId, false, SIZE)
                val bytes = Http.Request(url).execute().use { it.getBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                images[emojiId] = bitmap
                onLoaded()
            } catch (e: Throwable) {
                logger.warn("Couldn't load emoji $emojiId: ${e.javaClass.simpleName}")
            }
        }
        return null
    }
}
