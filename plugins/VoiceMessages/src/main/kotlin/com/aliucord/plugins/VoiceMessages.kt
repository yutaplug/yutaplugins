package com.aliucord.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils
import com.discord.api.channel.ChannelUtils
import com.discord.api.permission.Permission
import com.discord.app.AppActivity
import com.discord.stores.Dispatcher
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.chat.input.ChatInputViewModel
import com.discord.widgets.chat.input.WidgetChatInput
import com.lytefast.flexinput.widget.FlexEditText
import java.io.File
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

@AliucordPlugin
class VoiceMessages : Plugin() {
    private data class Destination(
        val channel: Long,
        val reply: StorePendingReplies.PendingReply?,
    )

    private data class AudioFormat(
        val extension: String,
        val mime: String,
        val output: Int,
        val encoder: Int,
    )

    private data class Recording(
        val recorder: MediaRecorder,
        val file: File,
        val format: AudioFormat,
        val destination: Destination,
        val started: Long,
    )

    private var recording: Recording? = null
    private var button: AppCompatImageButton? = null
    private var cancelButton: AppCompatImageButton? = null
    private var waveform: WaveFormView? = null
    private var editText: FlexEditText? = null
    private var container: ViewGroup? = null
    private var inputLayout: RelativeLayout? = null
    private var originalContainerParams: RelativeLayout.LayoutParams? = null
    private var originalEditVisibility = View.VISIBLE
    private var attachmentRoot: View? = null
    private var attachmentList: RecyclerView? = null
    private var attachmentAdapter: RecyclerView.Adapter<*>? = null
    private var activityRoot: View? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var sheet: VoiceModeSheet? = null
    private var pendingActivity: AppActivity? = null
    private var pendingObserver: LifecycleEventObserver? = null
    private var pendingAction: Runnable? = null
    private var pickerActivity: AppActivity? = null
    private var pickerDestination: Destination? = null
    private var composerActivity: AppActivity? = null
    private val composerLifecycle =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recording != null) finishRecording(false)
        }
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var clickEligible = false
    private var holdRecording = false

    // Hold to record: recording starts only after a short hold, and presses right after one ends are ignored.
    private var holdStart: Runnable? = null
    private var holdCooldownUntil = 0L
    private var delayedStop: Runnable? = null
    private var sendTask: Future<*>? = null
    private var sending = false

    @Volatile private var running = false

    @Volatile private var generation = 0
    private var amplitudeErrorLogged = false

    private val sample =
        object : Runnable {
            override fun run() {
                val current = recording ?: return
                try {
                    waveform?.addAmplitude(current.recorder.maxAmplitude)
                    amplitudeErrorLogged = false
                } catch (error: RuntimeException) {
                    if (!amplitudeErrorLogged) logger.error(error)
                    amplitudeErrorLogged = true
                }
                if (recording === current) Utils.mainThread.postDelayed(this, 100)
            }
        }
    private val attachmentListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVisibility() }
    private val adapterObserver =
        object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateVisibility()

            override fun onItemRangeInserted(
                positionStart: Int,
                itemCount: Int,
            ) = updateVisibility()

            override fun onItemRangeRemoved(
                positionStart: Int,
                itemCount: Int,
            ) = updateVisibility()

            override fun onItemRangeChanged(
                positionStart: Int,
                itemCount: Int,
            ) = updateVisibility()

            override fun onItemRangeMoved(
                fromPosition: Int,
                toPosition: Int,
                itemCount: Int,
            ) = updateVisibility()
        }
    private val discoverInput =
        object : Runnable {
            override fun run() {
                if (!running || inputLayout != null) return
                val root = chatActivity()?.window?.decorView
                if (root == null) {
                    Utils.mainThread.postDelayed(this, 500)
                    return
                }
                if (activityRoot !== root) {
                    removeGlobalListener()
                    activityRoot = root
                    globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { attach(root) }
                    root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
                }
                attach(root)
            }
        }

    override fun start(context: Context) {
        running = true
        generation++
        instance = this
        settingsTab = SettingsTab(VoiceSettings::class.java, SettingsTab.Type.PAGE).withArgs(settings)
        patcher.patch(
            WidgetChatInput::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook { frame ->
                val view = frame.args[0] as View
                post {
                    // FlexInputFragment may inflate its composer after onViewBound returns.
                    watchForComposer(view)
                    attach(view)
                    post { attach(view) }
                }
            },
        )
        patcher.patch(
            WidgetChatInput::class.java,
            "configureUI",
            arrayOf(ChatInputViewModel.ViewState::class.java),
            Hook { post { updateVisibility() } },
        )
        patcher.patch(
            Class.forName("com.discord.widgets.chat.input.WidgetChatInputEditText\$setOnTextChangedListener\$1"),
            "afterTextChanged",
            arrayOf(Editable::class.java),
            Hook { post { updateVisibility() } },
        )
        commands.registerCommand("voicemessage", "Start or stop recording a voice message") {
            post {
                if (recording != null) {
                    finishRecording(true)
                } else if (startRecording()) {
                    Utils.showToast("Recording started. Run /voicemessage again to send.")
                }
            }
            null
        }
        commands.registerCommand("voicemessage cancel", "Cancel the active voice recording") {
            post {
                if (recording == null) {
                    Utils.showToast("There is no active voice recording")
                } else {
                    finishRecording(false)
                    Utils.showToast("Voice recording cancelled")
                }
            }
            null
        }
        Utils.mainThread.post(discoverInput)
    }

    private fun post(action: () -> Unit) {
        val token = generation
        Utils.mainThread.post { if (running && generation == token) action() }
    }

    private fun chatActivity(): AppActivity? {
        var context: Context? = inputLayout?.context
        while (context is ContextWrapper) {
            if (context is AppActivity) return context
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        return try {
            Utils.appActivity
        } catch (_: RuntimeException) {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createViews(context: Context) {
        waveform = WaveFormView(context).apply { visibility = View.GONE }
        cancelButton = AppCompatImageButton(context).apply {
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setImageDrawable(ContextCompat.getDrawable(context, Utils.getResId("ic_close_24dp", "drawable"))?.mutate())
            drawable?.setTint(themeColor(context, "colorInteractiveNormal", Color.LTGRAY))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    themeColor(context, "colorBackgroundModifierSelected", 0x334f545c),
                ),
                null,
                android.graphics.drawable.ColorDrawable(Color.WHITE),
            )
            contentDescription = "Cancel voice recording"
            visibility = View.GONE
            setOnClickListener {
                if (recording != null) {
                    finishRecording(false)
                    Utils.showToast("Voice recording cancelled")
                }
            }
        }
        button =
            AppCompatImageButton(context).apply {
                id = View.generateViewId()
                tag = BUTTON_TAG
                minimumWidth = 0
                minimumHeight = 0
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setImageDrawable(ContextCompat.getDrawable(context, com.lytefast.flexinput.R.e.ic_mic_grey_24dp)?.mutate())
                visibility = View.GONE
                setOnClickListener {
                    if (recording != null) {
                        finishRecording(true)
                    } else if (settings.getBool("disableSelectionPopup", false)) {
                        startRecording()
                    } else {
                        showOptions()
                    }
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointer = event.getPointerId(0)
                            clickEligible = true
                            if (recording == null && settings.getBool("disableSelectionPopup", false)) {
                                if (SystemClock.elapsedRealtime() < holdCooldownUntil) {
                                    clickEligible = false
                                    return@setOnTouchListener true
                                }
                                view.isPressed = true
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                holdStart = Runnable {
                                    holdStart = null
                                    if (activePointer == MotionEvent.INVALID_POINTER_ID) return@Runnable
                                    holdRecording = startRecording()
                                    view.isPressed = holdRecording
                                    if (!holdRecording) view.parent?.requestDisallowInterceptTouchEvent(false)
                                    updateRecordingUi()
                                }.also { Utils.mainThread.postDelayed(it, HOLD_START_DELAY_MILLIS) }
                            }
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val index = event.findPointerIndex(activePointer)
                            val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                            if (index < 0 || event.getX(index) < -slop || event.getX(index) > view.width + slop ||
                                event.getY(index) < -slop || event.getY(index) > view.height + slop
                            ) {
                                clickEligible = false
                                cancelHoldStart()
                                if (holdRecording) {
                                    finishRecording(false)
                                    Utils.showToast("Voice recording cancelled")
                                }
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (event.getPointerId(event.actionIndex) == activePointer) {
                                val held = holdRecording
                                val canClick = clickEligible
                                // Released before the hold delay: treat it as an accidental tap.
                                val tooShort = cancelHoldStart()
                                clearPress()
                                if (tooShort) {
                                    Utils.showToast("Hold the button to record")
                                } else if (held) {
                                    finishRecording(true)
                                } else if (canClick && (recording != null || !settings.getBool("disableSelectionPopup", false))) {
                                    view.performClick()
                                }
                            }
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            cancelHoldStart()
                            if (holdRecording) finishRecording(false)
                            clearPress()
                            true
                        }

                        MotionEvent.ACTION_POINTER_UP -> {
                            if (event.getPointerId(event.actionIndex) == activePointer) {
                                cancelHoldStart()
                                if (holdRecording) finishRecording(false)
                                clearPress()
                            }
                            true
                        }

                        else -> {
                            true
                        }
                    }
                }
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {}

                        override fun onViewDetachedFromWindow(view: View) {
                            if (recording != null) finishRecording(false)
                            clearPress()
                        }
                    },
                )
            }
        refreshAppearance()
    }

    private fun attach(root: View) {
        if (!running) return
        val input = root.findViewById<FlexEditText>(Utils.getResId("text_input", "id")) ?: return
        val group = root.findViewById<ViewGroup>(Utils.getResId("main_input_container", "id")) ?: return
        val layout = group.parent as? RelativeLayout ?: return
        if (container !== group) {
            detachComposer()
            editText = input
            container = group
            inputLayout = layout
            composerActivity = chatActivity()
            composerActivity?.lifecycle?.addObserver(composerLifecycle)
            originalEditVisibility = input.visibility
            originalContainerParams = (group.layoutParams as? RelativeLayout.LayoutParams)?.let { RelativeLayout.LayoutParams(it) }
            createViews(input.context)
            group.addView(waveform, 0, LinearLayout.LayoutParams(0, dp(30), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
            group.addView(cancelButton, 0, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
            attachmentRoot = (layout.parent as? View) ?: root
            attachmentRoot?.addOnLayoutChangeListener(attachmentListener)
            placeButton()
        }
        updateRecordingUi()
        updateVisibility()
        removeGlobalListener()
        Utils.mainThread.removeCallbacks(discoverInput)
    }

    private fun placeButton() {
        val current = button ?: return
        val target = if (settings.getBool("integratedButton", false)) container else inputLayout
        target ?: return
        val params =
            if (target === container) {
                current.setPadding(0, dp(4), 0, dp(4))
                LinearLayout.LayoutParams(dp(24), dp(36)).apply { gravity = Gravity.CENTER_VERTICAL }
            } else {
                current.setPadding(dp(4), dp(4), dp(4), dp(4))
                RelativeLayout.LayoutParams(dp(36), dp(36)).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    rightMargin = dp(12)
                }
            }
        if (current.parent !== target) {
            if (recording != null) finishRecording(false)
            (current.parent as? ViewGroup)?.removeView(current)
            target.addView(current, params)
        } else {
            current.layoutParams = params
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val current = button ?: return
        val input = editText ?: return
        val selected = StoreStream.getChannelsSelected().id
        val blocked = inputLayout?.findViewById<View>(Utils.getResId("cannot_send_text", "id"))
        if (recording != null && (
                recording?.destination?.channel != selected || !canAttach(selected) || !input.isEnabled ||
                    inputLayout?.let { !treeVisible(it) } != false || (blocked != null && treeVisible(blocked))
            )
        ) {
            finishRecording(false)
        }
        val list = attachmentRoot?.findViewById<RecyclerView>(Utils.getResId("attachment_preview_list", "id"))
        if (attachmentList !== list || attachmentAdapter !== list?.adapter) {
            attachmentAdapter?.unregisterAdapterDataObserver(adapterObserver)
            attachmentList = list
            attachmentAdapter = list?.adapter
            attachmentAdapter?.registerAdapterDataObserver(adapterObserver)
        }
        // Stay in place while uploading so the composer does not jump; the button is disabled until done.
        val visible =
            sending || recording != null || (
                canAttach(selected) && input.isEnabled && input.isFocusable &&
                    treeVisible(input) && (blocked == null || !treeVisible(blocked)) && input.text.isNullOrEmpty() &&
                    (attachmentAdapter?.itemCount ?: 0) == 0
            )
        current.visibility = if (visible) View.VISIBLE else View.GONE
        current.isEnabled = !sending
        current.alpha = if (sending) 0.4f else 1f
        val group = container ?: return
        val params = group.layoutParams as? RelativeLayout.LayoutParams ?: return
        val original = originalContainerParams ?: return
        val separate = visible && !settings.getBool("integratedButton", false)
        // FlexInput uses absolute LEFT_OF/RIGHT_OF rules. Mixing in START_OF makes
        // Android clear the RIGHT_OF anchor that reserves space for gallery/gift buttons.
        val leftAnchor = if (separate) current.id else original.rules[RelativeLayout.LEFT_OF]
        val rightAnchor = original.rules[RelativeLayout.RIGHT_OF]
        val rightMargin = if (separate) dp(8) else original.rightMargin
        if (params.rules[RelativeLayout.LEFT_OF] != leftAnchor || params.rules[RelativeLayout.RIGHT_OF] != rightAnchor ||
            params.rightMargin != rightMargin || params.rules[RelativeLayout.START_OF] != original.rules[RelativeLayout.START_OF]
        ) {
            params.addRule(RelativeLayout.LEFT_OF, leftAnchor)
            params.addRule(RelativeLayout.RIGHT_OF, rightAnchor)
            params.addRule(RelativeLayout.START_OF, original.rules[RelativeLayout.START_OF])
            params.rightMargin = rightMargin
            group.layoutParams = params
        }
    }

    private fun treeVisible(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun canAttach(channelId: Long): Boolean {
        val channel = StoreStream.getChannels().getChannel(channelId) ?: return false
        if (ChannelUtils.B(channel)) return true
        val permissions = StoreStream.getPermissions().permissionsByChannel[channelId]
        val sendPermission = if (ChannelUtils.H(channel)) Permission.SEND_MESSAGES_IN_THREADS else Permission.SEND_MESSAGES
        return PermissionUtils.can(Permission.ATTACH_FILES, permissions) && PermissionUtils.can(sendPermission, permissions)
    }

    internal fun refreshAppearance() {
        val alpha = if (settings.getBool("translucentButton", false)) 160 else 255
        button?.background =
            if (settings.getBool("integratedButton", false)) {
                null
            } else {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    val color = settingColor("buttonColor", DEFAULT_BUTTON_COLOR)
                    setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)))
                }
            }
        updateRecordingUi()
    }

    internal fun refreshSettings() {
        clearPendingAction()
        dismissOptions()
        refreshAppearance()
        placeButton()
    }

    private fun settingColor(
        key: String,
        fallback: Int,
    ): Int =
        settings.getInt(key, fallback).let {
            if (Color.alpha(it) == 0) fallback else it
        }

    private fun updateRecordingUi() {
        editText?.visibility = if (recording == null) originalEditVisibility else View.GONE
        waveform?.visibility = if (recording == null) View.GONE else View.VISIBLE
        cancelButton?.visibility = if (recording != null && !holdRecording) View.VISIBLE else View.GONE
        val integrated = settings.getBool("integratedButton", false)
        button?.drawable?.apply {
            setTint(
                when {
                    recording != null -> Color.rgb(237, 66, 69)
                    integrated -> themeColor(button!!.context, "colorInteractiveNormal", Color.LTGRAY)
                    else -> settingColor("buttonIconColor", DEFAULT_ICON_COLOR)
                },
            )
            alpha = if (settings.getBool("translucentButton", false)) 160 else 255
        }
        button?.contentDescription =
            when {
                recording != null && holdRecording -> "Release to send voice message"
                recording != null -> "Send voice recording"
                settings.getBool("disableSelectionPopup", false) -> "Hold to record voice message"
                else -> "Choose voice message type"
            }
    }

    /** Returns true when a press was still waiting for the hold delay. */
    private fun cancelHoldStart(): Boolean {
        val pending = holdStart ?: return false
        Utils.mainThread.removeCallbacks(pending)
        holdStart = null
        return true
    }

    private fun clearPress() {
        activePointer = MotionEvent.INVALID_POINTER_ID
        clickEligible = false
        button?.isPressed = false
        button?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun whenResumed(action: () -> Unit) {
        clearPendingAction()
        val activity = chatActivity() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val channel = StoreStream.getChannelsSelected().id
        pendingActivity = activity
        pendingAction =
            Runnable {
                if (!running || pendingActivity !== activity) return@Runnable
                if (activity.isFinishing || activity.isDestroyed) {
                    clearPendingAction()
                    return@Runnable
                }
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    activity.supportFragmentManager.isStateSaved
                ) {
                    return@Runnable
                }
                clearPendingAction()
                if (chatActivity() === activity && StoreStream.getChannelsSelected().id == channel) action()
            }
        pendingObserver =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) pendingAction?.let { Utils.mainThread.post(it) }
                if (event == Lifecycle.Event.ON_DESTROY) clearPendingAction()
            }
        activity.lifecycle.addObserver(pendingObserver!!)
        pendingAction?.let { Utils.mainThread.post(it) }
    }

    private fun clearPendingAction() {
        pendingAction?.let { Utils.mainThread.removeCallbacks(it) }
        pendingObserver?.let { pendingActivity?.lifecycle?.removeObserver(it) }
        pendingAction = null
        pendingObserver = null
        pendingActivity = null
    }

    private fun showOptions() {
        if (recording != null || sending || sheet != null) return
        whenResumed {
            if (recording != null || sending || sheet != null) return@whenResumed
            val activity = chatActivity() ?: return@whenResumed
            try {
                val manager = activity.supportFragmentManager
                if (manager.findFragmentByTag(SHEET_TAG) != null) return@whenResumed
                val popup = VoiceModeSheet()
                val selectedChannel = StoreStream.getChannelsSelected().id
                popup.onSelect = { choice ->
                    dismissOptions()
                    if (selectedChannel == StoreStream.getChannelsSelected().id) {
                        if (choice == 1) {
                            startRecording()
                        } else if (choice == 2) {
                            openPicker()
                        }
                    }
                }
                popup.lifecycle.addObserver(
                    LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY && sheet === popup) sheet = null
                    },
                )
                sheet = popup
                popup.showNow(manager, SHEET_TAG)
            } catch (error: RuntimeException) {
                dismissOptions()
                report("Unable to open voice message options", error)
            }
        }
    }

    private fun dismissOptions() {
        val popup = sheet
        sheet = null
        popup?.onSelect = null
        if (popup?.isAdded == true) popup.dismissAllowingStateLoss()
    }

    private fun destination(): Destination? {
        val channel = StoreStream.getChannelsSelected().id
        if (channel == 0L || !canAttach(channel)) {
            Utils.showToast("You cannot attach files in this channel")
            return null
        }
        val layout = inputLayout
        val blocked = layout?.findViewById<View>(Utils.getResId("cannot_send_text", "id"))
        if (layout == null || !treeVisible(layout) || editText?.isEnabled != true || (blocked != null && treeVisible(blocked))) {
            Utils.showToast("The message composer is unavailable")
            return null
        }
        return Destination(channel, pendingReplies().getPendingReply(channel))
    }

    private fun startRecording(): Boolean {
        if (recording != null || sending || pickerDestination != null || !running) return false
        val destination = destination() ?: return false
        val activity = chatActivity() ?: return false
        if (ContextCompat.checkSelfPermission(activity, "android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf("android.permission.RECORD_AUDIO"), 4833)
            Utils.showToast("Grant microphone permission, then start recording again")
            return false
        }
        if (waveform == null) createViews(activity)
        var format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || settings.getBool("legacyOgg", false)) OGG else M4A
        var file: File? = null
        try {
            file = temporaryFile(format.extension)
            val recorder =
                try {
                    createRecorder(file, format)
                } catch (error: Exception) {
                    if (format != OGG) throw error
                    deleteFile(file)
                    logger.debug("Ogg/Opus unavailable; falling back to M4A")
                    format = M4A
                    file = temporaryFile(format.extension)
                    createRecorder(file, format)
                }
            waveform?.reset()
            recording = Recording(recorder, file, format, destination, SystemClock.elapsedRealtime())
            amplitudeErrorLogged = false
            updateRecordingUi()
            updateVisibility()
            Utils.mainThread.post(sample)
            return true
        } catch (error: Exception) {
            deleteFile(file)
            report("Unable to start voice recording", error)
            return false
        }
    }

    private fun createRecorder(
        file: File,
        format: AudioFormat,
    ): MediaRecorder {
        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(format.output)
            recorder.setAudioEncoder(format.encoder)
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(settings.getInt("audioQuality", 128).coerceIn(32, 192) * 1000)
            recorder.setAudioSamplingRate(48000)
            recorder.setMaxFileSize(availableAudioBytes())
            recorder.setOnInfoListener { source, info, _ ->
                if (info == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    post {
                        if (recording?.recorder === source) {
                            finishRecording(false)
                            Utils.showToast("Recording cancelled: device storage is low")
                        }
                    }
                }
            }
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            return recorder
        } catch (error: Exception) {
            release(recorder)
            throw error
        }
    }

    private fun finishRecording(send: Boolean) {
        val current = recording ?: return
        if (send && SystemClock.elapsedRealtime() - current.started < MIN_RECORDING_MILLIS) {
            if (delayedStop == null) {
                delayedStop =
                    Runnable {
                        delayedStop = null
                        if (recording === current) finishRecording(true)
                    }.also { Utils.mainThread.postDelayed(it, MIN_RECORDING_MILLIS - (SystemClock.elapsedRealtime() - current.started)) }
            }
            return
        }
        delayedStop?.let { Utils.mainThread.removeCallbacks(it) }
        delayedStop = null
        recording = null
        if (holdRecording) holdCooldownUntil = SystemClock.elapsedRealtime() + HOLD_COOLDOWN_MILLIS
        holdRecording = false
        clearPress()
        Utils.mainThread.removeCallbacks(sample)
        var stopped = false
        try {
            if (send) {
                current.recorder.stop()
                stopped = true
            }
        } catch (error: RuntimeException) {
            report("No audio was recorded", error)
        } finally {
            release(current.recorder)
        }
        val waves = waveform?.waveform() ?: "AQ=="
        updateRecordingUi()
        updateVisibility()
        if (!send || !stopped || current.file.length() == 0L) {
            deleteFile(current.file)
            return
        }
        sendFile(
            current.file,
            current.format,
            current.destination,
            waves,
            (SystemClock.elapsedRealtime() - current.started) / 1000f,
        )
    }

    private fun openPicker() {
        if (sending || recording != null || pickerDestination != null) return
        whenResumed {
            val destination = destination() ?: return@whenResumed
            val activity = chatActivity() ?: return@whenResumed
            try {
                val manager = activity.supportFragmentManager
                if (manager.findFragmentByTag(PICKER_TAG) != null) return@whenResumed
                pickerDestination = destination
                pickerActivity = activity
                val picker = AudioFilePickerFragment()
                manager.beginTransaction().add(picker, PICKER_TAG).commitNow()
                picker.open()
            } catch (error: RuntimeException) {
                pickerDestination = null
                removePicker()
                report("Unable to open the audio file picker", error)
            }
        }
    }

    internal fun onAudioFilePicked(uri: Uri?) {
        val destination = pickerDestination
        pickerDestination = null
        pickerActivity = null
        if (uri == null || destination == null || sending || !running) return
        launchSend { checkActive ->
            var file: File? = null
            try {
                checkActive()
                val copied = temporaryFile(".audio")
                file = copied
                Utils.appContext.contentResolver.openInputStream(uri)?.use { input ->
                    copied.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        val maximumBytes = availableAudioBytes()
                        var copiedBytes = 0L
                        var storageCheckedAt = 0L
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count.toLong() > maximumBytes - copiedBytes) throw IOException("Not enough storage for this audio file")
                            if (copiedBytes - storageCheckedAt >= 1024 * 1024) {
                                availableAudioBytes()
                                storageCheckedAt = copiedBytes
                            }
                            output.write(buffer, 0, count)
                            copiedBytes += count
                        }
                    }
                } ?: throw IOException("Could not read the selected audio file")
                val format = detectFormat(copied)
                val duration = duration(copied) ?: throw IOException("Could not determine the audio duration")
                transmit(copied, format, destination, "AQ==", duration, checkActive)
            } finally {
                deleteFile(file)
            }
        }
    }

    private fun sendFile(
        file: File,
        format: AudioFormat,
        destination: Destination,
        waves: String,
        fallbackDuration: Float,
    ) {
        launchSend(onNotStarted = { deleteFile(file) }) { checkActive ->
            try {
                checkActive()
                transmit(file, format, destination, waves, duration(file) ?: fallbackDuration.coerceAtLeast(0.1f), checkActive)
            } finally {
                deleteFile(file)
            }
        }
    }

    private fun launchSend(
        onNotStarted: () -> Unit = {},
        action: (() -> Unit) -> Unit,
    ) {
        sending = true
        updateVisibility()
        val token = generation
        val checkActive = {
            if (!running || generation != token) throw InterruptedIOException("Voice message cancelled")
            DiscordAPI.checkCancelled()
        }
        val started = AtomicBoolean(false)
        val task =
            object : FutureTask<Unit>({
                if (started.compareAndSet(false, true)) {
                    try {
                        // Always enter the action's try/finally, even when cancellation
                        // wins the race just as the worker starts, so its file is deleted.
                        action(checkActive)
                    } catch (
                        error: InterruptedIOException,
                    ) {
                        // SocketTimeoutException is also an InterruptedIOException.
                        // Report timeouts; only actual cancellation should be silent.
                        if (running && generation == token && !Thread.currentThread().isInterrupted) {
                            report("Sending timed out. Check the chat before trying again.", error)
                        }
                    } catch (
                        error: Exception,
                    ) {
                        if (running && generation == token) report("Failed to send voice message", error)
                    } finally {
                        post {
                            if (generation == token) {
                                sending = false
                                sendTask = null
                                updateVisibility()
                            }
                        }
                    }
                }
                Unit.a
            }) {
                override fun done() {
                    // A cancelled queued task never enters its callable's finally block.
                    if (started.compareAndSet(false, true)) onNotStarted()
                }
            }
        sendTask = task
        try {
            Utils.threadPool.execute(task)
        } catch (error: RuntimeException) {
            task.cancel(false)
            sendTask = null
            sending = false
            updateVisibility()
            report("Unable to send voice message", error)
        }
    }

    private fun transmit(
        file: File,
        format: AudioFormat,
        destination: Destination,
        waves: String,
        duration: Float,
        checkActive: () -> Unit,
    ) {
        checkActive()
        val uploaded = DiscordAPI.uploadFile(file, destination.channel, format.mime, checkActive)
        val reference = destination.reply?.messageReference
        val reply =
            reference?.c()?.let {
                VoiceMessageBody.MessageReference(it.toString(), reference.a() ?: destination.channel, reference.b())
            }
        DiscordAPI.sendVoiceMessage(
            uploaded,
            duration,
            waves,
            destination.channel,
            format.extension,
            reply,
            destination.reply?.shouldMention ?: false,
            checkActive,
        )
        checkActive()
        try {
            clearSentReply(destination)
        } catch (error: Exception) {
            // The message was already accepted; a UI cleanup failure must not be
            // reported as a failed send and encourage sending the audio twice.
            logger.error(error)
        }
    }

    private fun clearSentReply(destination: Destination) {
        val expected = destination.reply ?: return
        val token = generation
        val store = pendingReplies()
        val dispatcher = replyDispatcherField.get(store) as Dispatcher
        // Compare against live data and remove on the same store dispatch. Checking
        // the UI snapshot before queuing deletion could erase a newly chosen reply.
        dispatcher.schedule {
            if (running && generation == token) {
                val entries = StorePendingReplies.`access$getPendingReplies$p`(store)
                if (entries[destination.channel] === expected) {
                    entries.remove(destination.channel)
                    store.markChanged()
                }
            }
            Unit.a
        }
    }

    private fun detectFormat(file: File): AudioFormat {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }

        fun matches(
            offset: Int,
            value: String,
        ) = count >= offset + value.length &&
            value.indices.all { header[offset + it].toInt() == value[it].code }
        val format =
            when {
                matches(0, "OggS") -> {
                    OGG
                }

                matches(4, "ftyp") -> {
                    M4A
                }

                matches(0, "RIFF") && matches(8, "WAVE") -> {
                    AudioFormat(".wav", "audio/wav", 0, 0)
                }

                matches(0, "fLaC") -> {
                    AudioFormat(".flac", "audio/flac", 0, 0)
                }

                matches(0, "ID3") -> {
                    AudioFormat(".mp3", "audio/mpeg", 0, 0)
                }

                count >= 2 && (header[0].toInt() and 255) == 255 && (header[1].toInt() and 0xF6) == 0xF0 -> {
                    AudioFormat(
                        ".aac",
                        "audio/aac",
                        0,
                        0,
                    )
                }

                count >= 2 && (header[0].toInt() and 255) == 255 && (header[1].toInt() and 0xE0) == 0xE0 -> {
                    AudioFormat(
                        ".mp3",
                        "audio/mpeg",
                        0,
                        0,
                    )
                }

                else -> {
                    throw IOException("Unsupported audio format. Choose Ogg, M4A, MP3, AAC, WAV or FLAC.")
                }
            }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) throw IOException("Choose an audio file without a video track")
                if (mime.startsWith("audio/")) audio = true
            }
            if (!audio) throw IOException("The selected file has no readable audio track")
        } finally {
            extractor.release()
        }
        return format
    }

    private fun duration(file: File): Float? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { it / 1000f }
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (error: Exception) {
                logger.error(error)
            }
        }
    }

    private fun availableAudioBytes(): Long {
        val available = Utils.appContext.cacheDir.usableSpace - MIN_FREE_CACHE_BYTES
        if (available <= 0) throw IOException("Not enough storage for voice messages")
        return available
    }

    private fun temporaryFile(extension: String): File {
        availableAudioBytes()
        return File.createTempFile("voice-message-", extension, Utils.appContext.cacheDir)
    }

    private fun deleteFile(file: File?) {
        if (file != null && file.exists() && !file.delete()) logger.debug("Could not delete temporary audio: ${file.name}")
    }

    private fun release(recorder: MediaRecorder) {
        try {
            recorder.release()
        } catch (error: RuntimeException) {
            logger.error(error)
        }
    }

    private fun report(
        message: String,
        error: Throwable,
    ) {
        logger.error(error)
        Utils.showToast(message)
    }

    private fun removeGlobalListener() {
        val observer = activityRoot?.viewTreeObserver
        if (observer?.isAlive == true) globalLayoutListener?.let { observer.removeOnGlobalLayoutListener(it) }
        globalLayoutListener = null
        activityRoot = null
    }

    private fun watchForComposer(root: View) {
        removeGlobalListener()
        activityRoot = root
        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { attach(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
    }

    private fun removePicker() {
        val manager = pickerActivity?.supportFragmentManager
        pickerActivity = null
        if (manager == null || manager.isDestroyed) return
        val picker = manager.findFragmentByTag(PICKER_TAG) ?: return
        manager.beginTransaction().remove(picker).commitAllowingStateLoss()
    }

    private fun detachComposer() {
        if (recording != null) finishRecording(false)
        composerActivity?.lifecycle?.removeObserver(composerLifecycle)
        composerActivity = null
        attachmentAdapter?.unregisterAdapterDataObserver(adapterObserver)
        attachmentAdapter = null
        attachmentList = null
        attachmentRoot?.removeOnLayoutChangeListener(attachmentListener)
        attachmentRoot = null
        editText?.visibility = originalEditVisibility
        originalContainerParams?.let { container?.layoutParams = it }
        (button?.parent as? ViewGroup)?.removeView(button)
        (cancelButton?.parent as? ViewGroup)?.removeView(cancelButton)
        (waveform?.parent as? ViewGroup)?.removeView(waveform)
        button = null
        cancelButton = null
        waveform = null
        editText = null
        container = null
        inputLayout = null
        originalContainerParams = null
    }

    override fun stop(context: Context) {
        running = false
        generation++
        clearPendingAction()
        dismissOptions()
        removePicker()
        pickerDestination = null
        finishRecording(false)
        sendTask?.cancel(true)
        sendTask = null
        sending = false
        Utils.mainThread.removeCallbacks(sample)
        Utils.mainThread.removeCallbacks(discoverInput)
        cancelHoldStart()
        removeGlobalListener()
        detachComposer()
        patcher.unpatchAll()
        commands.unregisterAll()
        if (instance === this) instance = null
    }

    companion object {
        internal var instance: VoiceMessages? = null
            private set
        internal val DEFAULT_BUTTON_COLOR = Color.rgb(88, 101, 242)
        internal const val DEFAULT_ICON_COLOR = Color.WHITE
        private const val MIN_RECORDING_MILLIS = 500L
        private const val HOLD_START_DELAY_MILLIS = 300L
        private const val HOLD_COOLDOWN_MILLIS = 1000L
        private const val MIN_FREE_CACHE_BYTES = 16L * 1024 * 1024
        private const val BUTTON_TAG = "VoiceMessages.RecordButton"
        private const val SHEET_TAG = "VoiceMessages.VoiceMode"
        private const val PICKER_TAG = "VoiceMessages.AudioFilePicker"
        private val OGG = AudioFormat(".ogg", "audio/ogg", MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS)
        private val M4A = AudioFormat(".m4a", "audio/mp4", MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC)

        // Discord's Kotlin metadata hides this otherwise public companion getter.
        private val pendingReplyStore by lazy {
            val companion = StoreStream::class.java.getField("Companion").get(null)
            companion.javaClass.getMethod("getPendingReplies").invoke(companion) as StorePendingReplies
        }

        private fun pendingReplies() = pendingReplyStore

        private val replyDispatcherField = StorePendingReplies::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }

        private fun dp(value: Int) = DimenUtils.dpToPx(value)
    }
}
