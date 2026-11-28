package com.github.yutaplug.fixes.voice

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.GatewayAPI
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.SerializedName
import com.aliucord.widgets.BottomSheet
import com.discord.api.channel.ChannelUtils
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.api.premium.PremiumTier
import com.discord.models.user.MeUser
import com.discord.widgets.voice.controls.VoiceControlsSheetView
import com.discord.widgets.voice.model.CallModel
import com.github.yutaplug.fixes.FixPatcher
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The soundboard, as on current Discord. Sounds are sent with POST /channels/{id}/send-soundboard-sound; the server
 * then sends VOICE_CHANNEL_EFFECT_SEND to everyone in the channel (the sender included), and each client plays the
 * sound itself from the CDN. So this plays those events for the voice channel you're in, and adds a soundboard
 * button to the call controls that opens a picker laid out like the emoji picker: Discord's default sounds (GET
 * /soundboard-default-sounds), the server's own, and with Nitro the other servers' (asked for over the gateway with
 * opcode 31, Request Soundboard Sounds, answered with SOUNDBOARD_SOUNDS). Part of [VoiceFix].
 */
internal class Soundboard(
    private val patcher: FixPatcher,
    private val logger: Logger,
    private val pluginResources: Resources?,
) {
    // Gson models for the gateway events (snowflakes arrive as strings, which Gson reads into Long)
    class EffectSend(
        @SerializedName("channel_id") val channelId: Long = 0,
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("user_id") val userId: Long = 0,
        @SerializedName("sound_id") val soundId: Long = 0,
        @SerializedName("sound_volume") val soundVolume: Float? = null,
    )

    class SoundJson(
        @SerializedName("sound_id") val soundId: Long = 0,
        @SerializedName("name") val name: String? = null,
        @SerializedName("volume") val volume: Float? = null,
        @SerializedName("emoji_id") val emojiId: Long? = null,
        @SerializedName("emoji_name") val emojiName: String? = null,
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("available") val available: Boolean? = null,
    )

    class GuildSounds(
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("soundboard_sounds") val sounds: Array<SoundJson>? = null,
    )

    class SoundDelete(
        @SerializedName("guild_id") val guildId: Long = 0,
        @SerializedName("sound_id") val soundId: Long = 0,
    )

    class Sound(
        val id: Long,
        val name: String,
        val emoji: String?,
        val emojiId: Long,
        val guildId: Long,
        val volume: Float,
    )

    @Volatile private var running = false

    /** Discord's default sounds, once loaded. */
    @Volatile private var defaults: List<Sound>? = null
    private var loadingDefaults = false

    /** Each server's own sounds, by server ID, once the gateway has sent them. */
    private val guildSounds = ConcurrentHashMap<Long, MutableMap<Long, Sound>>()
    private val requestedGuilds = ConcurrentHashMap.newKeySet<Long>()

    private val players = java.util.Collections.newSetFromMap(ConcurrentHashMap<MediaPlayer, Boolean>())
    private var sheet: WeakReference<SoundboardSheet>? = null
    private val buttons = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ImageView, Boolean>())

    fun start() {
        running = true
        current = this
        registerEvents()

        // The call controls at the bottom of the call screen (video, audio output, mute, disconnect). Its public
        // configureUI runs on every call state change; the button goes before the audio output button.
        val configure = VoiceControlsSheetView::class.java.declaredMethods.first { it.name.startsWith("configureUI") }
        patcher.patch(
            configure,
            Hook { param ->
                val view = param.thisObject as VoiceControlsSheetView
                val call = param.args[0] as? CallModel ?: return@Hook
                configureButton(view, call)
            },
        )
    }

    fun stop() {
        running = false
        if (current === this) current = null
        unregisterEvents()
        for (p in players) {
            try {
                p.release()
            } catch (_: Throwable) {
            }
        }
        players.clear()
        sheet?.get()?.dismissAllowingStateLoss()
        for (b in ArrayList(buttons)) (b.parent as? ViewGroup)?.removeView(b)
        buttons.clear()
        guildSounds.clear()
        requestedGuilds.clear()
    }

    // region gateway

    private fun registerEvents() {
        GatewayAPI.onEvent("VOICE_CHANNEL_EFFECT_SEND", EffectSend::class.java) { e ->
            if (running && e.soundId != 0L) onEffect(e)
        }
        GatewayAPI.onEvent("SOUNDBOARD_SOUNDS", GuildSounds::class.java) { e ->
            if (running) setGuildSounds(e.guildId, e.sounds)
        }
        GatewayAPI.onEvent("GUILD_SOUNDBOARD_SOUNDS_UPDATE", GuildSounds::class.java) { e ->
            if (running) setGuildSounds(e.guildId, e.sounds)
        }
        GatewayAPI.onEvent("GUILD_SOUNDBOARD_SOUND_CREATE", SoundJson::class.java) { e -> if (running) putSound(e) }
        GatewayAPI.onEvent("GUILD_SOUNDBOARD_SOUND_UPDATE", SoundJson::class.java) { e -> if (running) putSound(e) }
        GatewayAPI.onEvent("GUILD_SOUNDBOARD_SOUND_DELETE", SoundDelete::class.java) { e ->
            if (!running) return@onEvent
            guildSounds[e.guildId]?.remove(e.soundId)
            refreshSheet()
        }
    }

    private fun unregisterEvents() {
        try {
            val ours = setOf(EffectSend::class.java, SoundJson::class.java, GuildSounds::class.java, SoundDelete::class.java)
            val list = GatewayAPI::class.java.getMethod("getRegisteredEventListeners").invoke(GatewayAPI)
                as MutableList<*>
            val typeGetter = Class.forName("com.aliucord.api.GatewayAPI\$RegisteredEventListener").getMethod("getType")
            list.removeAll { it != null && typeGetter.invoke(it) in ours }
        } catch (e: Throwable) {
            logger.error("Couldn't remove the soundboard gateway listeners", e)
        }
    }

    private fun toSound(json: SoundJson, guildId: Long): Sound? {
        if (json.soundId == 0L || json.available == false) return null
        val emoji = json.emojiName?.takeIf { it.isNotEmpty() }
        val volume = (json.volume ?: 1f).coerceIn(0f, 1f)
        return Sound(json.soundId, json.name ?: "Sound", emoji, json.emojiId ?: 0L, guildId, volume)
    }

    private fun setGuildSounds(guildId: Long, sounds: Array<SoundJson>?) {
        if (guildId == 0L) return
        val map = LinkedHashMap<Long, Sound>()
        sounds?.forEach { json -> toSound(json, guildId)?.let { map[it.id] = it } }
        guildSounds[guildId] = map
        refreshSheet()
    }

    private fun putSound(json: SoundJson) {
        val map = guildSounds[json.guildId] ?: return // not loaded yet; it comes with the whole list
        val sound = toSound(json, json.guildId)
        if (sound == null) map.remove(json.soundId) else map[sound.id] = sound
        refreshSheet()
    }

    /** Asks the gateway for these servers' sounds (opcode 31), once per server. */
    private fun requestGuildSounds(guildIds: Collection<Long>) {
        val wanted = guildIds.filter { it != 0L && requestedGuilds.add(it) }
        if (wanted.isEmpty()) return
        try {
            val payload = HashMap<String, Any>()
            payload["guild_ids"] = wanted.map { it.toString() }
            if (!GatewaySend.send(REQUEST_SOUNDBOARD_SOUNDS, payload)) requestedGuilds.removeAll(wanted)
        } catch (e: Throwable) {
            requestedGuilds.removeAll(wanted)
            logger.error("Couldn't request soundboard sounds", e)
        }
    }

    private fun loadDefaults() {
        synchronized(this) {
            if (defaults != null || loadingDefaults) return
            loadingDefaults = true
        }
        Utils.threadPool.execute {
            try {
                val text = Http.Request.newDiscordRNRequest("/soundboard-default-sounds").execute().use { it.text() }
                val array = JSONArray(text)
                val list = ArrayList<Sound>()
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("sound_id").toLongOrNull() ?: continue
                    if (!o.optBoolean("available", true)) continue
                    val emoji = if (o.isNull("emoji_name")) null else o.optString("emoji_name").takeIf { it.isNotEmpty() }
                    val emojiId = if (o.isNull("emoji_id")) 0L else o.optString("emoji_id").toLongOrNull() ?: 0L
                    val volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)
                    list.add(Sound(id, o.optString("name", "Sound"), emoji, emojiId, 0L, volume))
                }
                defaults = list
                refreshSheet()
            } catch (e: Throwable) {
                logger.error("Couldn't load the default soundboard sounds", e)
            } finally {
                synchronized(this) { loadingDefaults = false }
            }
        }
    }

    // endregion

    // region playback

    private fun onEffect(e: EffectSend) {
        val selected = StoreStream.getVoiceChannelSelected().selectedVoiceChannelId
        if (selected == 0L || e.channelId != selected) return
        val deafened = try {
            StoreStream.getMediaSettings().voiceConfigurationBlocking.isSelfDeafened
        } catch (_: Throwable) {
            false
        }
        if (deafened) return
        play(e.soundId, (e.soundVolume ?: 1f).coerceIn(0f, 1f))
    }

    /**
     * Downloads the sound (once; it's kept in the cache folder) and plays it from the file. Streaming it from the
     * CDN made Android's media server die on some uploaded sounds (MediaPlayer error 100, MEDIA_ERROR_SERVER_DIED).
     */
    private fun play(soundId: Long, volume: Float) {
        Utils.threadPool.execute {
            val file = try {
                soundFile(soundId)
            } catch (e: Throwable) {
                logger.warn("Couldn't download soundboard sound $soundId: ${e.javaClass.simpleName}")
                return@execute
            }
            playFile(soundId, file, volume)
        }
    }

    private fun soundFile(soundId: Long): java.io.File {
        val dir = java.io.File(Utils.appContext.cacheDir, "soundboard").apply { mkdirs() }
        val file = java.io.File(dir, soundId.toString())
        if (file.length() > 0) return file
        val partial = java.io.File(dir, "$soundId.part")
        Http.Request("$CDN/soundboard-sounds/$soundId").execute().use { response ->
            response.assertOk()
            partial.outputStream().use { response.pipe(it) }
        }
        if (!partial.renameTo(file)) throw java.io.IOException("rename failed")
        trimCache(dir)
        return file
    }

    /** Keeps the newest sounds; sounds are small (512 KiB at most), so this stays a few MB. */
    private fun trimCache(dir: java.io.File) {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        if (files.size <= MAX_CACHED_SOUNDS) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_CACHED_SOUNDS).forEach { it.delete() }
    }

    /** Plays on the call's audio stream, so it follows the call's volume and output. */
    private fun playFile(soundId: Long, file: java.io.File, volume: Float) {
        Utils.mainThread.post {
            if (!running) return@post
            val player = MediaPlayer()
            players.add(player)
            val release = {
                players.remove(player)
                try {
                    player.release()
                } catch (_: Throwable) {
                }
            }
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                java.io.FileInputStream(file).use { player.setDataSource(it.fd) }
                player.setVolume(volume, volume)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { release() }
                player.setOnErrorListener { _, what, extra ->
                    // Uploaded sounds are Ogg Opus, which MediaPlayer can choke on (e.g. 100, server died);
                    // decode it ourselves instead.
                    logger.warn("MediaPlayer couldn't play soundboard sound $soundId ($what/$extra), decoding it instead")
                    release()
                    Utils.threadPool.execute { decodeAndPlay(soundId, file, volume) }
                    true
                }
                player.prepareAsync()
            } catch (t: Throwable) {
                logger.error("Couldn't play soundboard sound $soundId", t)
                release()
            }
        }
    }

    /**
     * Decodes the sound with MediaExtractor + MediaCodec into 16-bit PCM and plays it with an AudioTrack, on the
     * call's audio stream. Used when MediaPlayer fails on a sound.
     */
    private fun decodeAndPlay(soundId: Long, file: java.io.File, volume: Float) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            var track = -1
            var i = 0
            while (i < extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    break
                }
                i++
            }
            if (track < 0) throw java.io.IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val pcm = java.io.ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = decoder.getOutputBuffer(outIndex)!!
                            val bytes = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(bytes)
                            pcm.write(bytes)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                if (pcm.size() > MAX_PCM_BYTES) break // 5.2 s sounds never get near this
            }
            val data = pcm.toByteArray()
            if (data.isEmpty() || !running) return
            val track2 = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                data.size,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            track2.write(data, 0, data.size)
            track2.setVolume(volume)
            track2.play()
            val frames = data.size / (2 * (if (channels >= 2) 2 else 1))
            val ms = frames * 1000L / sampleRate.coerceAtLeast(1)
            Utils.mainThread.postDelayed({
                try {
                    track2.release()
                } catch (_: Throwable) {
                }
            }, ms + 500)
        } catch (e: Throwable) {
            logger.error("Couldn't decode soundboard sound $soundId", e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    // endregion

    // region call controls button

    private fun permissions(channelId: Long): Long = StoreStream.getPermissions().permissionsByChannel[channelId] ?: 0L

    /**
     * Server owners and Administrators get Discord 126.21's Permission.ALL, which predates the soundboard
     * permissions, so Administrator counts as having them.
     */
    private fun has(channelId: Long, permission: Long) = permissions(channelId) and (permission or ADMINISTRATOR) != 0L

    fun soundboardIcon(context: Context): Drawable? {
        val res = pluginResources ?: return null
        val id = res.getIdentifier("ic_soundboard_24dp", "drawable", "com.github.yutaplug.fixes")
        return if (id == 0) null else ResourcesCompat.getDrawable(res, id, context.theme)?.mutate()
    }

    private fun configureButton(view: VoiceControlsSheetView, call: CallModel) {
        val mute = view.findViewById<View>(Utils.getResId("mute_button", "id")) ?: return
        val row = mute.parent as? ViewGroup ?: return
        var button = row.findViewWithTag<ImageView>(BUTTON_TAG)
        if (button == null) {
            button = makeButton(view.context) ?: return
            val output = view.findViewById<View>(Utils.getResId("audio_output_container", "id"))
            val index = row.indexOfChild(output ?: mute)
            row.addView(button, if (index >= 0) index else row.childCount)
            buttons.add(button)
        }
        val channel = call.channel
        val usable = running && call.isConnected && channel.i() != 0L && !ChannelUtils.D(channel) &&
            has(channel.k(), USE_SOUNDBOARD)
        button.visibility = if (usable) View.VISIBLE else View.GONE
        button.setOnClickListener { v -> openSheet(v.context, channel.k(), channel.i()) }
    }

    /** A copy of the call controls' own invite button (a circle button), with the soundboard icon. */
    private fun makeButton(context: Context): ImageView? {
        val holder = FrameLayout(context)
        LayoutInflater.from(context).inflate(Utils.getResId("voice_controls_sheet_view", "layout"), holder, true)
        val template = holder.findViewById<ImageView>(Utils.getResId("invite_button", "id")) ?: return null
        (template.parent as ViewGroup).removeView(template)
        template.id = View.generateViewId()
        template.tag = BUTTON_TAG
        template.contentDescription = "Soundboard"
        template.setImageDrawable(soundboardIcon(context) ?: return null)
        template.visibility = View.GONE
        return template
    }

    private fun openSheet(context: Context, channelId: Long, guildId: Long) {
        val activity = activityOf(context) ?: Utils.appActivity ?: return
        loadDefaults()
        val premium = hasFullNitro()
        // Every server's sounds are listed, like on current Discord; the ones you can't use are marked.
        requestGuildSounds(listOf(guildId) + StoreStream.getGuilds().guilds.keys)
        val s = SoundboardSheet()
        s.channelId = channelId
        s.guildId = guildId
        s.premium = premium
        s.externalAllowed = has(channelId, USE_EXTERNAL_SOUNDS)
        sheet = WeakReference(s)
        s.show(activity.supportFragmentManager, "Soundboard")
    }

    /**
     * Sounds from other servers need full Nitro: the web client gives SOUNDBOARD_EVERYWHERE to TIER_2 only, while
     * 126.21's UserUtils.isPremium counts every tier. The tier is read from MeUser's own field, since plugins that
     * fake Nitro patch its getter.
     */
    private fun hasFullNitro(): Boolean = try {
        val me = StoreStream.getUsers().me
        val field = MeUser::class.java.getDeclaredField("premiumTier").apply { isAccessible = true }
        field.get(me) == PremiumTier.TIER_2
    } catch (_: Throwable) {
        false
    }

    private fun activityOf(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }

    // endregion

    // region sending

    private fun refreshSheet() {
        Utils.mainThread.post { sheet?.get()?.takeIf { it.isAdded }?.build() }
    }

    fun send(sound: Sound, channelId: Long, guildId: Long) {
        Utils.threadPool.execute {
            try {
                // The same body as the web client: the sound's emoji, and its server for every server sound,
                // this server's included (without it, this server's own sounds don't play).
                val body = JSONObject().put("sound_id", sound.id.toString())
                body.put("emoji_id", if (sound.emojiId != 0L) sound.emojiId.toString() else JSONObject.NULL)
                body.put("emoji_name", sound.emoji ?: JSONObject.NULL)
                if (sound.guildId != 0L) body.put("source_guild_id", sound.guildId.toString())
                Http.Request.newDiscordRNRequest("/channels/$channelId/send-soundboard-sound", "POST").use { request ->
                    request.setHeader("Content-Type", "application/json")
                    request.executeWithBody(body.toString()).use { response ->
                        if (response.ok()) return@execute
                        val text = try {
                            request.conn.errorStream?.bufferedReader()?.use { it.readText() }
                        } catch (_: Throwable) {
                            null
                        }
                        logger.warn("Sending soundboard sound failed (HTTP ${response.statusCode}): $text")
                        val message = try {
                            JSONObject(text ?: "").optString("message")
                        } catch (_: Throwable) {
                            ""
                        }
                        Utils.showToast(if (message.isNotEmpty()) message else "Couldn't play the sound")
                    }
                }
            } catch (e: Throwable) {
                logger.error("Sending soundboard sound failed", e)
                Utils.showToast("Couldn't play the sound")
            }
        }
    }

    /** The picker's sections: Discord's default sounds, this server's, then every other server's. */
    fun sections(guildId: Long): List<Pair<String, List<Sound>?>> {
        val out = ArrayList<Pair<String, List<Sound>?>>()
        val guilds = StoreStream.getGuilds()
        out.add("Default" to defaults)
        out.add((guilds.getGuild(guildId)?.name ?: "This server") to guildSounds[guildId]?.values?.toList())
        for ((id, sounds) in guildSounds) {
            if (id == guildId || sounds.isEmpty()) continue
            val name = guilds.getGuild(id)?.name ?: continue
            out.add(name to sounds.values.toList())
        }
        return out
    }

    /** Plays the sound just for you, without sending it to the call. */
    fun preview(sound: Sound) {
        if (running) play(sound.id, sound.volume)
    }

    fun refreshEmojis() = refreshSheet()

    // endregion

    companion object {
        /** The running instance, for the sheet (which Android may recreate without its arguments). */
        @Volatile var current: Soundboard? = null

        private const val CDN = "https://cdn.discordapp.com"
        private const val REQUEST_SOUNDBOARD_SOUNDS = 31
        private const val ADMINISTRATOR = 8L
        private const val USE_SOUNDBOARD = 1L shl 42
        private const val USE_EXTERNAL_SOUNDS = 1L shl 45
        private const val BUTTON_TAG = "yutaplug.soundboard"
        private const val MAX_CACHED_SOUNDS = 100
        private const val MAX_PCM_BYTES = 8 * 1024 * 1024
    }
}

/**
 * The sound picker, laid out like Discord's emoji picker: its search bar, its section headers, and a grid of
 * sound tiles (Discord's chip text on its rounded background). Tapping a tile plays the sound in the call.
 */
internal class SoundboardSheet : BottomSheet() {
    var channelId = 0L
    var guildId = 0L
    var premium = false
    var externalAllowed = false

    private var query = ""
    private var content: LinearLayout? = null

    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        if (channelId == 0L || Soundboard.current == null) {
            dismissAllowingStateLoss() // recreated by Android without its channel
            return
        }
        val context = view.context
        linearLayout.setBackgroundColor(ColorCompat.getThemedColor(context, Utils.getResId("colorBackgroundSecondary", "attr")))
        searchBar(context)?.let { addView(it) }
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = DimenUtils.dpToPx(12)
            setPadding(side, 0, side, DimenUtils.dpToPx(12))
        }
        addView(content!!)
        build()
    }

    override fun onDestroyView() {
        content = null
        super.onDestroyView()
    }

    /** The emoji picker's own search bar, taken out of its layout, searching sound names. */
    private fun searchBar(context: Context): View? = try {
        val picker = LayoutInflater.from(context)
            .inflate(Utils.getResId("widget_emoji_picker", "layout"), linearLayout, false)
        val bar = picker.findViewById<ViewGroup>(Utils.getResId("emoji_search_bar", "id"))
        (bar.parent as ViewGroup).removeView(bar)
        bar.findViewById<View>(Utils.getResId("emoji_inline_search_button", "id"))?.visibility = View.GONE
        val input = bar.findViewById<EditText>(Utils.getResId("emoji_search_input", "id"))
        input.visibility = View.VISIBLE
        input.hint = "Search sounds"
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                query = s.toString().trim().toLowerCase(Locale.ROOT)
                build()
            }
        })
        bar.findViewById<View>(Utils.getResId("emoji_search_clear", "id"))?.setOnClickListener { input.setText("") }
        bar
    } catch (e: Throwable) {
        null
    }

    fun build() {
        val board = Soundboard.current ?: return
        val box = content ?: return
        val context = box.context
        box.removeAllViews()
        var shown = 0
        for ((title, all) in board.sections(guildId)) {
            val sounds = all?.filter { query.isEmpty() || it.name.toLowerCase(Locale.ROOT).contains(query) }
            if (sounds != null && sounds.isEmpty()) continue
            if (sounds == null && query.isNotEmpty()) continue
            box.addView(header(context, title))
            if (sounds == null) {
                box.addView(tile(context, null, "Loading…"), LinearLayout.LayoutParams(-1, DimenUtils.dpToPx(44)))
                continue
            }
            // A plain loop: `step` would build an IntProgression, which Discord's stripped stdlib copy breaks.
            var start = 0
            while (start < sounds.size) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until COLUMNS) {
                    val sound = sounds.getOrNull(start + i)
                    val cell: View = if (sound == null) View(context) else tile(context, sound, sound.name)
                    row.addView(cell, LinearLayout.LayoutParams(0, DimenUtils.dpToPx(44), 1f).apply {
                        val m = DimenUtils.dpToPx(4)
                        setMargins(m, m, m, m)
                    })
                }
                box.addView(row, LinearLayout.LayoutParams(-1, -2))
                shown++
                start += COLUMNS
            }
        }
        if (shown == 0 && query.isNotEmpty()) box.addView(header(context, "No sounds found"))
    }

    /** The emoji picker's section header (bold, small caps, secondary header color). */
    private fun header(context: Context, title: String): View {
        val view = LayoutInflater.from(context)
            .inflate(Utils.getResId("expression_picker_header_item", "layout"), content, false) as TextView
        view.text = title
        view.setPadding(view.paddingLeft, DimenUtils.dpToPx(12), view.paddingRight, DimenUtils.dpToPx(4))
        return view
    }

    /**
     * Whether you can play the sound here, by the web client's rule: default sounds and this server's always; other
     * servers' only with Nitro, and only where the server allows sounds from other servers.
     */
    private fun usable(sound: Soundboard.Sound) =
        sound.guildId == 0L || sound.guildId == guildId || (premium && externalAllowed)

    private fun themedIcon(context: Context, name: String, attr: String?): Drawable? {
        val d = ContextCompat.getDrawable(context, Utils.getResId(name, "drawable"))?.mutate() ?: return null
        if (attr != null) d.setTint(ColorCompat.getThemedColor(context, Utils.getResId(attr, "attr")))
        return d
    }

    /**
     * A sound tile: Discord's chip text (its font and color) on its rounded background, emoji first, then a Nitro or
     * lock mark if you can't play it here, and a preview button that plays it just for you.
     */
    private fun tile(context: Context, sound: Soundboard.Sound?, text: String): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        themedIcon(context, "drawable_bg_corners_4dp", "colorBackgroundTertiary")?.let { tile.background = it }
        if (Build.VERSION.SDK_INT >= 23) {
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                tile.foreground = ContextCompat.getDrawable(context, ripple.resourceId)
            }
        }
        val chip = LayoutInflater.from(context).inflate(Utils.getResId("view_chip", "layout"), content, false) as ViewGroup
        val view = chip.findViewById<TextView>(Utils.getResId("chip_text", "id"))
        chip.removeView(view)
        view.id = View.NO_ID
        view.gravity = Gravity.CENTER_VERTICAL
        view.ellipsize = TextUtils.TruncateAt.END
        view.setSingleLine(true)
        view.setPadding(DimenUtils.dpToPx(10), 0, 0, 0)
        view.compoundDrawablePadding = DimenUtils.dpToPx(6)
        view.text = text
        tile.addView(view, LinearLayout.LayoutParams(0, -1, 1f))
        if (sound == null) return tile
        val board = Soundboard.current
        val size = DimenUtils.dpToPx(20)
        val image = if (sound.emojiId != 0L) EmojiImages.get(sound.emojiId) { board?.refreshEmojis() } else null
        when {
            image != null -> {
                val d = BitmapDrawable(context.resources, image)
                d.setBounds(0, 0, size, size)
                view.setCompoundDrawablesRelative(d, null, null, null)
            }
            sound.emoji != null && sound.emojiId == 0L -> view.text = "${sound.emoji}  $text"
            else -> board?.soundboardIcon(context)?.let {
                it.setTint(ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr")))
                it.setBounds(0, 0, size, size)
                view.setCompoundDrawablesRelative(it, null, null, null)
            }
        }
        val small = DimenUtils.dpToPx(16)
        if (!usable(sound)) {
            view.alpha = 0.5f
            val lock = if (premium) {
                themedIcon(context, "ic_lock_white_a60_16dp", "colorInteractiveNormal")
            } else {
                themedIcon(context, "ic_nitro_wheel_16dp", null)
            }
            if (lock != null) {
                tile.addView(
                    ImageView(context).apply { setImageDrawable(lock) },
                    LinearLayout.LayoutParams(small, small).apply { marginStart = DimenUtils.dpToPx(4) },
                )
            }
        }
        themedIcon(context, "ic_play_arrow_24dp", "colorInteractiveNormal")?.let { icon ->
            val play = ImageView(context).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Preview"
                val borderless = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, borderless, true)) {
                    setBackgroundResource(borderless.resourceId)
                }
                setOnClickListener { board?.preview(sound) }
            }
            tile.addView(play, LinearLayout.LayoutParams(DimenUtils.dpToPx(36), -1))
        }
        tile.setOnClickListener {
            when {
                usable(sound) -> board?.send(sound, channelId, guildId)
                !premium -> Utils.showToast("Sounds from other servers need Nitro")
                else -> Utils.showToast("This server doesn't allow sounds from other servers")
            }
        }
        return tile
    }

    private companion object {
        const val COLUMNS = 2
    }
}
