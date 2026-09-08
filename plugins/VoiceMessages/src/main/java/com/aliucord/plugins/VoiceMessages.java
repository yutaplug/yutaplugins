package com.aliucord.plugins;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.aliucord.Constants;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.discord.stores.StoreStream;
import com.discord.widgets.chat.input.WidgetChatInputEditText$setOnTextChangedListener$1;
import com.lytefast.flexinput.fragment.FlexInputFragment;
import com.lytefast.flexinput.widget.FlexEditText;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@SuppressWarnings("unused")
@AliucordPlugin
public class VoiceMessages extends Plugin {
    static final int DEFAULT_BUTTON_COLOR = Color.rgb(88, 101, 242);
    static final int DEFAULT_ICON_COLOR = Color.WHITE;
    private static final String BUTTON_TAG = "VoiceMessages.RecordButton";
    private static VoiceMessages instance;

    private MediaRecorder mediaRecorder;
    private volatile boolean isRecording;
    private volatile boolean waveformReadFailed;
    private long recordingStartedAt;
    private Thread updateWaveformThread;
    private WaveFormView waveFormView;
    private File outputFile;
    private long recordingChannelId;
    private VoiceMessageBody.MessageReference recordingReply;

    private FlexEditText editText;
    private ViewGroup inputContainer;
    private RelativeLayout inputLayout;
    private View attachmentPreview;
    private AppCompatImageButton recordButton;
    private Drawable recordIcon;
    private View observedActivityRoot;
    private ViewTreeObserver.OnGlobalLayoutListener inputLayoutListener;
    private boolean activityObservationRetryScheduled;

    public static SettingsAPI staticSettings;
    int outputFormat = MediaRecorder.OutputFormat.MPEG_4;
    int audioEncoder = MediaRecorder.AudioEncoder.AAC;
    String extension = ".m4a";

    private final Runnable updateWaveform = () -> {
        while (isRecording && !Thread.currentThread().isInterrupted()) {
            MediaRecorder recorder = mediaRecorder;
            if (recorder == null) {
                break;
            }

            int amplitude = 0;
            try {
                synchronized (recorder) {
                    if (!isRecording) {
                        break;
                    }
                    amplitude = recorder.getMaxAmplitude();
                }
                waveformReadFailed = false;
            } catch (RuntimeException e) {
                // Some Android 7 recorder implementations briefly reject an amplitude read.
                if (!waveformReadFailed) {
                    logger.error(e);
                    waveformReadFailed = true;
                }
            }

            amplitude = Math.max(0, Math.min(32767, amplitude));
            int wave = amplitude == 0
                    ? 1
                    : Math.max(1, Math.min(255, (int) (Math.sqrt(amplitude / 32767.0) * 254)));
            waveFormView.addWave(wave);
            waveFormView.postInvalidate();

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void start(Context context) throws NoSuchMethodException {
        instance = this;
        configureRecordingFormat();
        staticSettings = settings;

        if (settings.getString("vendorId", null) == null) {
            settings.setString("vendorId", UUID.randomUUID().toString());
        }

        settingsTab = new SettingsTab(BottomShit.class, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings);
        waveFormView = new WaveFormView(context);
        recordButton = new AppCompatImageButton(context);
        recordButton.setId(View.generateViewId());
        recordButton.setTag(BUTTON_TAG);
        configureRecordButton(context);

        recordButton.setOnTouchListener((view, motionEvent) -> {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    try {
                        recordingChannelId = StoreStream.getChannelsSelected().getId();
                        recordingReply = getPendingReply(recordingChannelId);
                        onRecordStart();
                    } catch (IOException | RuntimeException e) {
                        logger.error(e);
                        Utils.showToast("Unable to start voice recording");
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (isRecording) {
                        onRecordStop(true, recordingChannelId);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (isRecording) {
                        onRecordStop(false, 0L);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isRecording && (motionEvent.getY() < 0 || motionEvent.getY() > view.getHeight())) {
                        onRecordStop(false, 0L);
                        Utils.showToast("Voice recording cancelled");
                    }
                    return true;
                default:
                    return false;
            }
        });

        commands.registerCommand("voicemessage", "Start or stop recording a voice message", commandContext -> {
            if (isRecording) {
                onRecordStop(true, recordingChannelId);
                Utils.showToast("Sending voice message");
                return null;
            }

            var channel = commandContext.getCurrentChannel();
            if (channel == null) {
                return new CommandsAPI.CommandResult("There is no active channel to record in.");
            }

            recordingChannelId = channel.getId();
            recordingReply = getPendingReply(recordingChannelId);
            try {
                if (onRecordStart()) {
                    Utils.showToast("Recording started. Run /voicemessage again to send.");
                }
            } catch (IOException | RuntimeException e) {
                logger.error(e);
                return new CommandsAPI.CommandResult("Unable to start voice recording.");
            }
            return null;
        });

        commands.registerCommand("voicemessage cancel", "Cancel the active voice message recording", commandContext -> {
            if (!isRecording) {
                return new CommandsAPI.CommandResult("There is no active voice recording.");
            }
            onRecordStop(false, 0L);
            Utils.showToast("Voice recording cancelled");
            return null;
        });

        patcher.patch(FlexInputFragment.class.getDeclaredMethod("onViewCreated", View.class, Bundle.class), cf -> {
            View inputView = (View) cf.args[0];
            attachToChatInput(inputView);
            // Run after Discord finishes its first layout pass as well.
            Utils.mainThread.post(() -> attachToChatInput(inputView));
        });

        patcher.patch(FlexInputFragment.class.getDeclaredMethod("onResume"), cf -> {
            FlexInputFragment fragment = (FlexInputFragment) cf.thisObject;
            Utils.mainThread.post(() -> {
                View inputView = fragment.getView();
                if (inputView != null) {
                    attachToChatInput(inputView);
                }
            });
        });

        patcher.patch(WidgetChatInputEditText$setOnTextChangedListener$1.class.getDeclaredMethod("afterTextChanged", Editable.class), cf -> {
            updateRecordButtonVisibility();
        });

        // Observe the current activity because Android 7 can create the input after the plugin starts.
        Utils.mainThread.post(this::observeActivityLayout);
    }

    private void configureRecordButton(Context context) {
        recordButton.setContentDescription("Record voice message");
        var background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(getButtonColor());
        recordButton.setBackground(background);
        recordButton.setMinimumWidth(0);
        recordButton.setMinimumHeight(0);
        recordButton.setPadding(
                DimenUtils.dpToPx(4),
                DimenUtils.dpToPx(4),
                DimenUtils.dpToPx(4),
                DimenUtils.dpToPx(4)
        );

        var drawable = ContextCompat.getDrawable(context, com.lytefast.flexinput.R.e.ic_mic_grey_24dp);
        if (drawable != null) {
            recordIcon = drawable.mutate();
            recordIcon.setTint(getIconColor());
            recordButton.setImageDrawable(recordIcon);
        }
        recordButton.setVisibility(View.GONE);
    }

    private int getButtonColor() {
        int color = settings.getInt("buttonColor", DEFAULT_BUTTON_COLOR);
        return Color.alpha(color) == 0 ? DEFAULT_BUTTON_COLOR : color;
    }

    private int getIconColor() {
        int color = settings.getInt("buttonIconColor", DEFAULT_ICON_COLOR);
        return Color.alpha(color) == 0 ? DEFAULT_ICON_COLOR : color;
    }

    static void refreshButtonColor() {
        if (instance != null) {
            instance.applyButtonColor();
        }
    }

    private void applyButtonColor() {
        if (recordButton == null) {
            return;
        }
        var background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(getButtonColor());
        recordButton.setBackground(background);
        if (recordIcon != null && !isRecording) {
            recordIcon.setTint(getIconColor());
        }
    }

    private void observeActivityLayout() {
        if (recordButton == null) {
            return;
        }

        try {
            View root = Utils.getAppActivity().getWindow().getDecorView();
            if (root != observedActivityRoot) {
                removeActivityLayoutObserver();
                observedActivityRoot = root;
                inputLayoutListener = () -> attachToChatInput(root);
                root.getViewTreeObserver().addOnGlobalLayoutListener(inputLayoutListener);
            }
            attachToChatInput(root);
        } catch (RuntimeException e) {
            logger.error(e);
            scheduleActivityObservationRetry();
        }
    }

    private void scheduleActivityObservationRetry() {
        if (recordButton == null || activityObservationRetryScheduled) {
            return;
        }
        activityObservationRetryScheduled = true;
        Utils.mainThread.postDelayed(() -> {
            activityObservationRetryScheduled = false;
            observeActivityLayout();
        }, 500);
    }

    private void removeActivityLayoutObserver() {
        if (observedActivityRoot == null || inputLayoutListener == null) {
            return;
        }
        ViewTreeObserver observer = observedActivityRoot.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(inputLayoutListener);
        }
        inputLayoutListener = null;
    }

    private void attachToChatInput(View root) {
        if (root == null || recordButton == null) {
            return;
        }

        FlexEditText candidateEditText = root.findViewById(Utils.getResId("text_input", "id"));
        ViewGroup candidateContainer = root.findViewById(Utils.getResId("main_input_container", "id"));
        ViewParent candidateParent = candidateContainer == null ? null : candidateContainer.getParent();
        if (candidateEditText == null || candidateContainer == null || !(candidateParent instanceof RelativeLayout)) {
            return;
        }

        editText = candidateEditText;
        inputContainer = candidateContainer;
        inputLayout = (RelativeLayout) candidateParent;
        attachmentPreview = root.findViewById(Utils.getResId("attachment_preview_container", "id"));

        View existingButton = inputLayout.findViewWithTag(BUTTON_TAG);
        if (existingButton != null && existingButton != recordButton) {
            inputLayout.removeView(existingButton);
        }

        if (waveFormView.getParent() != candidateContainer) {
            var waveformParams = new LinearLayout.LayoutParams(0, DimenUtils.dpToPx(30), 1f);
            waveformParams.gravity = Gravity.CENTER_VERTICAL;
            if (!attachView(waveFormView, candidateContainer, 0, waveformParams)) {
                return;
            }
        }

        if (recordButton.getParent() != inputLayout) {
            var buttonParams = new RelativeLayout.LayoutParams(
                    DimenUtils.dpToPx(36),
                    DimenUtils.dpToPx(36)
            );
            buttonParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
            buttonParams.addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE);
            buttonParams.rightMargin = DimenUtils.dpToPx(12);
            if (!attachView(recordButton, inputLayout, -1, buttonParams)) {
                return;
            }
        }

        updateRecordingUi();
        updateRecordButtonVisibility();
    }

    private boolean attachView(View view, ViewGroup target, int index, ViewGroup.LayoutParams params) {
        ViewParent currentParent = view.getParent();
        if (currentParent == target) {
            return true;
        }
        if (currentParent instanceof ViewGroup) {
            ((ViewGroup) currentParent).removeView(view);
        }
        if (view.getParent() != null) {
            scheduleActivityObservationRetry();
            return false;
        }

        try {
            if (index < 0) {
                target.addView(view, params);
            } else {
                target.addView(view, index, params);
            }
            return true;
        } catch (IllegalStateException e) {
            logger.error(e);
            scheduleActivityObservationRetry();
            return false;
        }
    }

    private void detachFromParent(View view) {
        ViewParent parent = view == null ? null : view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private void updateInputContainerLayout(boolean buttonVisible) {
        if (inputContainer == null || recordButton == null) {
            return;
        }

        ViewGroup.LayoutParams rawParams = inputContainer.getLayoutParams();
        if (!(rawParams instanceof RelativeLayout.LayoutParams)) {
            return;
        }

        var params = (RelativeLayout.LayoutParams) rawParams;
        int anchorId = buttonVisible
                ? recordButton.getId()
                : Utils.getResId("send_btn_container", "id");
        int rightMargin = DimenUtils.dpToPx(8);
        if (params.getRule(RelativeLayout.LEFT_OF) == anchorId && params.rightMargin == rightMargin) {
            return;
        }
        params.addRule(RelativeLayout.LEFT_OF, anchorId);
        params.rightMargin = rightMargin;
        inputContainer.setLayoutParams(params);
    }

    private void configureRecordingFormat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputFormat = MediaRecorder.OutputFormat.OGG;
            audioEncoder = MediaRecorder.AudioEncoder.OPUS;
            extension = ".ogg";
        } else {
            // MPEG_2_TS was added in API 26. MPEG-4/AAC is available on Android 7.
            outputFormat = MediaRecorder.OutputFormat.MPEG_4;
            audioEncoder = MediaRecorder.AudioEncoder.AAC;
            extension = ".m4a";
        }
    }

    public boolean onRecordStart() throws IOException {
        if (isRecording) {
            return true;
        }

        if (ContextCompat.checkSelfPermission(Utils.getAppContext(), android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(Utils.getAppActivity(), new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            Utils.showToast("Microphone permission is required");
            return false;
        }

        File baseDirectory = new File(Constants.BASE_PATH);
        if (!baseDirectory.isDirectory() && !baseDirectory.mkdirs()) {
            throw new IOException("Could not create the voice recording directory");
        }

        waveFormView.reset();
        outputFile = File.createTempFile("audio_record", extension, baseDirectory);
        outputFile.deleteOnExit();

        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(outputFormat);
            recorder.setAudioEncoder(audioEncoder);
            recorder.setAudioChannels(1);

            int quality = settings.getInt("audioQuality", 128);
            recorder.setAudioEncodingBitRate(Math.max(32000, Math.min(192000, quality * 1024)));
            setSamplingRate(recorder);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            mediaRecorder = recorder;
            isRecording = true;
            recordingStartedAt = System.currentTimeMillis();
            waveformReadFailed = false;
            updateRecordingUi();
        } catch (IOException | RuntimeException e) {
            releaseRecorder(recorder);
            deleteFile(outputFile);
            outputFile = null;
            throw e;
        }

        updateWaveformThread = new Thread(updateWaveform, "VoiceMessages-Waveform");
        updateWaveformThread.start();
        return true;
    }

    private void setSamplingRate(MediaRecorder recorder) {
        int requestedRate = settings.getBool("highSamplingRate", false) ? 48000 : 44100;
        try {
            recorder.setAudioSamplingRate(requestedRate);
        } catch (RuntimeException e) {
            if (requestedRate != 44100) {
                try {
                    recorder.setAudioSamplingRate(44100);
                    return;
                } catch (RuntimeException fallbackError) {
                    logger.error(fallbackError);
                }
            } else {
                logger.error(e);
            }
        }
    }

    public void onRecordStop(boolean send, long discordid) {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        if (updateWaveformThread != null) {
            updateWaveformThread.interrupt();
        }

        File recordedFile = outputFile;
        outputFile = null;
        String recordingExtension = extension;
        String waveform = waveFormView.getWaveForm();
        long elapsedMillis = Math.max(0, System.currentTimeMillis() - recordingStartedAt);
        VoiceMessageBody.MessageReference reply = recordingReply;
        recordingReply = null;
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        boolean stopped = stopRecorder(recorder);
        updateRecordingUi();

        if (!send || !stopped || recordedFile == null) {
            deleteFile(recordedFile);
            return;
        }

        if (!recordedFile.isFile() || recordedFile.length() <= 0) {
            logger.error(new IllegalStateException("MediaRecorder produced an empty voice recording"));
            deleteFile(recordedFile);
            Utils.showToast("No audio was recorded");
            return;
        }

        Utils.threadPool.execute(() -> {
            try {
                var filename = DiscordAPI.uploadFile(recordedFile, discordid, recordingExtension);
                float seconds = getRecordingDurationSeconds(recordedFile, elapsedMillis);
                DiscordAPI.sendVoiceMessage(
                        filename,
                        seconds,
                        waveform,
                        discordid,
                        recordingExtension,
                        reply
                );
                if (reply != null) {
                    StoreStream.Companion.getPendingReplies().onDeletePendingReply(discordid);
                }
            } catch (RuntimeException e) {
                logger.error(e);
                Utils.showToast("Failed to send voice message");
            } finally {
                deleteFile(recordedFile);
            }
        });
    }

    private void updateRecordingUi() {
        if (editText != null) {
            editText.setVisibility(isRecording ? View.GONE : View.VISIBLE);
        }
        if (waveFormView != null && waveFormView.getParent() != null) {
            waveFormView.setVisibility(isRecording ? View.VISIBLE : View.GONE);
        }
        if (recordIcon != null) {
            recordIcon.setTint(isRecording ? Color.rgb(237, 66, 69) : getIconColor());
        }
        if (recordButton != null) {
            recordButton.setContentDescription(isRecording
                    ? "Stop recording voice message"
                    : "Record voice message");
        }
    }

    private void updateRecordButtonVisibility() {
        if (recordButton == null || editText == null) {
            return;
        }

        Editable text = editText.getText();
        boolean hasAttachments = attachmentPreview != null
                && attachmentPreview.getVisibility() == View.VISIBLE;
        boolean buttonVisible = !hasAttachments && (isRecording || text == null || text.length() == 0);
        recordButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
        updateInputContainerLayout(buttonVisible);
    }

    private boolean stopRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            return false;
        }

        boolean stopped = false;
        synchronized (recorder) {
            try {
                recorder.stop();
                stopped = true;
            } catch (RuntimeException e) {
                logger.error(e);
            } finally {
                releaseRecorder(recorder);
            }
        }
        return stopped;
    }

    private void releaseRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            return;
        }
        try {
            recorder.reset();
        } catch (RuntimeException e) {
            logger.error(e);
        }
        try {
            recorder.release();
        } catch (RuntimeException e) {
            logger.error(e);
        }
    }

    private float getRecordingDurationSeconds(File file, long fallbackMillis) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(file.getAbsolutePath());
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                float duration = Integer.parseInt(durationStr) / 1000.0f;
                if (duration > 0) {
                    return duration;
                }
            }
        } catch (RuntimeException e) {
            logger.error(e);
        } finally {
            try {
                mmr.release();
            } catch (IOException | RuntimeException e) {
                logger.error(e);
            }
        }
        return Math.max(0.1f, fallbackMillis / 1000.0f);
    }

    private VoiceMessageBody.MessageReference getPendingReply(long channelId) {
        try {
            var pending = StoreStream.Companion.getPendingReplies().getPendingReply(channelId);
            if (pending == null || pending.getMessageReference() == null) {
                return null;
            }

            var reference = pending.getMessageReference();
            if (reference.c() == null) {
                return null;
            }
            return new VoiceMessageBody.MessageReference(
                    String.valueOf(reference.c()),
                    reference.a() == null ? channelId : reference.a(),
                    reference.b()
            );
        } catch (RuntimeException e) {
            logger.error(e);
            return null;
        }
    }

    private void deleteFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logger.error(new IllegalStateException("Could not delete temporary voice recording"));
        }
    }

    @Override
    public void stop(Context context) {
        if (isRecording) {
            onRecordStop(false, 0L);
        } else {
            releaseRecorder(mediaRecorder);
            mediaRecorder = null;
            deleteFile(outputFile);
            outputFile = null;
        }
        detachFromParent(recordButton);
        detachFromParent(waveFormView);
        patcher.unpatchAll();
        commands.unregisterAll();
        removeActivityLayoutObserver();
        activityObservationRetryScheduled = false;
        recordButton = null;
        editText = null;
        inputContainer = null;
        inputLayout = null;
        attachmentPreview = null;
        waveFormView = null;
        if (instance == this) {
            instance = null;
        }
        observedActivityRoot = null;
    }
}
