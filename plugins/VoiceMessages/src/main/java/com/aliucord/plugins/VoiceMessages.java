package com.aliucord.plugins;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageButton;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.aliucord.Constants;
import com.aliucord.Utils;
import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.api.CommandsAPI;
import com.aliucord.api.SettingsAPI;
import com.aliucord.entities.Plugin;
import com.aliucord.utils.DimenUtils;
import com.discord.app.AppActivity;
import com.discord.stores.StoreStream;
import com.discord.utilities.color.ColorCompat;
import com.discord.widgets.chat.input.ChatInputViewModel;
import com.discord.widgets.chat.input.WidgetChatInput;
import com.discord.widgets.chat.input.WidgetChatInputEditText$setOnTextChangedListener$1;
import com.lytefast.flexinput.widget.FlexEditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.Future;

@SuppressWarnings("unused")
@AliucordPlugin
public class VoiceMessages extends Plugin {
    static final int DEFAULT_BUTTON_COLOR = Color.rgb(88, 101, 242);
    static final int DEFAULT_ICON_COLOR = Color.WHITE;
    private static final long MIN_RECORDING_MILLIS = 500;
    private static final String BUTTON_TAG = "VoiceMessages.RecordButton";
    static final int AUDIO_FILE_PICKER_REQUEST_CODE = 4832;
    static final String AUDIO_FILE_PICKER_TAG = "VoiceMessages.AudioFilePicker";
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
    private PopupWindow voiceModePopup;
    private boolean delayedStopPending;
    private volatile boolean audioFileSendInProgress;
    private volatile Future<?> audioFileTask;
    private long audioFileChannelId;
    private VoiceMessageBody.MessageReference audioFileReply;

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
                    if (isRecording) {
                        return true;
                    }
                    showVoiceModePopup();
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

        patcher.patch(WidgetChatInput.class.getDeclaredMethod("onViewBound", View.class), cf -> {
            View inputView = (View) cf.args[0];
            attachToChatInput(inputView);
            // Run after Discord finishes its first layout pass as well.
            Utils.mainThread.post(() -> attachToChatInput(inputView));
        });

        patcher.patch(WidgetChatInputEditText$setOnTextChangedListener$1.class.getDeclaredMethod("afterTextChanged", Editable.class), cf -> {
            updateRecordButtonVisibility();
        });

        patcher.patch(WidgetChatInput.class.getDeclaredMethod(
                "configureUI", ChatInputViewModel.ViewState.class
        ), cf -> Utils.mainThread.post(this::updateRecordButtonVisibility));

        // Observe only until the chat input is found. Keeping a listener on the activity decor
        // while sheets are open can interfere with context menus such as PluginDownloader.
        Utils.mainThread.post(this::observeActivityLayout);
    }

    private void configureRecordButton(Context context) {
        recordButton.setContentDescription("Record voice message");
        if (isIntegratedButton()) {
            recordButton.setBackground(null);
        } else {
            var background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(getButtonBackgroundColor());
            recordButton.setBackground(background);
        }
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
            recordIcon.setAlpha(getButtonAlpha());
            recordButton.setImageDrawable(recordIcon);
        }
        recordButton.setVisibility(View.GONE);
    }

    private int getButtonColor() {
        int color = settings.getInt("buttonColor", DEFAULT_BUTTON_COLOR);
        return Color.alpha(color) == 0 ? DEFAULT_BUTTON_COLOR : color;
    }

    private int getButtonBackgroundColor() {
        int color = getButtonColor();
        return Color.argb(getButtonAlpha(), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int getButtonAlpha() {
        return settings.getBool("translucentButton", false) ? 160 : 255;
    }

    private boolean isIntegratedButton() {
        return settings.getBool("integratedButton", false);
    }

    private int getIconColor() {
        int color = settings.getInt("buttonIconColor", DEFAULT_ICON_COLOR);
        return Color.alpha(color) == 0 ? DEFAULT_ICON_COLOR : color;
    }

    static VoiceMessages getInstance() {
        return instance;
    }

    private void showVoiceModePopup() {
        if (recordButton == null || isRecording || voiceModePopup != null) {
            return;
        }

        Context context = recordButton.getContext();
        int popupWidth = DimenUtils.dpToPx(280);
        LinearLayout menu = new LinearLayout(context);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(
                DimenUtils.dpToPx(6),
                DimenUtils.dpToPx(6),
                DimenUtils.dpToPx(6),
                DimenUtils.dpToPx(6)
        );
        GradientDrawable menuBackground = new GradientDrawable();
        menuBackground.setColor(themeColor(context, "colorBackgroundSecondary", Color.rgb(32, 34, 37)));
        menuBackground.setCornerRadius(DimenUtils.dpToPx(12));
        menuBackground.setStroke(
                DimenUtils.dpToPx(1),
                themeColor(context, "colorBackgroundTertiary", Color.rgb(64, 68, 75))
        );
        menu.setBackground(menuBackground);

        TextView recordOption = createVoiceModeOption(
                context,
                "Send Voice Message",
                this::startNormalVoiceRecording
        );
        TextView fileOption = createVoiceModeOption(
                context,
                "Send .mp3/.wav/etc. as Voice Message",
                ignored -> openAudioFilePicker()
        );
        menu.addView(recordOption);
        menu.addView(fileOption);

        PopupWindow popup = new PopupWindow(
                menu,
                popupWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(menuBackground);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        popup.setElevation(DimenUtils.dpToPx(8));
        popup.setOnDismissListener(() -> {
            if (voiceModePopup == popup) {
                voiceModePopup = null;
            }
        });
        voiceModePopup = popup;

        menu.measure(
                View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int popupHeight = menu.getMeasuredHeight();
        popup.setHeight(popupHeight);
        try {
            popup.showAsDropDown(
                    recordButton,
                    recordButton.getWidth() - popupWidth,
                    -recordButton.getHeight() - popupHeight - DimenUtils.dpToPx(8)
            );
        } catch (RuntimeException e) {
            voiceModePopup = null;
            logger.error(e);
        }
    }

    private TextView createVoiceModeOption(Context context, String label, View.OnClickListener listener) {
        TextView option = new TextView(context);
        option.setText(label);
        option.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(DimenUtils.dpToPx(48));
        option.setPadding(
                DimenUtils.dpToPx(12),
                0,
                DimenUtils.dpToPx(12),
                0
        );
        option.setClickable(true);
        option.setFocusable(true);
        TypedValue selectableValue = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground,
                selectableValue,
                true
        ) && selectableValue.resourceId != 0) {
            option.setBackground(ContextCompat.getDrawable(context, selectableValue.resourceId));
        }
        option.setOnClickListener(ignored -> {
            dismissVoiceModePopup();
            listener.onClick(option);
        });
        return option;
    }

    private void startNormalVoiceRecording(View ignored) {
        try {
            recordingChannelId = StoreStream.getChannelsSelected().getId();
            recordingReply = getPendingReply(recordingChannelId);
            onRecordStart();
        } catch (IOException | RuntimeException e) {
            logger.error(e);
            Utils.showToast("Unable to start voice recording");
        }
    }

    private void dismissVoiceModePopup() {
        if (voiceModePopup != null) {
            voiceModePopup.dismiss();
            voiceModePopup = null;
        }
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        if (id == 0) {
            return fallback;
        }

        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(id, value, true)) {
            return fallback;
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, value.resourceId);
            } catch (RuntimeException ignored) {
                // Fall through to Discord's themed color resolver.
            }
        }
        return ColorCompat.getThemedColor(context, id);
    }

    static void refreshButtonColor() {
        if (instance != null) {
            instance.applyButtonColor();
            instance.updateRecordingUi();
            instance.updateButtonPlacement();
        }
    }

    private void applyButtonColor() {
        if (recordButton == null) {
            return;
        }
        if (isIntegratedButton()) {
            recordButton.setBackground(null);
        } else {
            var background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(getButtonBackgroundColor());
            recordButton.setBackground(background);
        }
        if (recordIcon != null) {
            recordIcon.setAlpha(getButtonAlpha());
            if (!isRecording) {
                recordIcon.setTint(getIconColor());
            }
        }
    }

    private void observeActivityLayout() {
        if (recordButton == null) {
            return;
        }

        try {
            View root = Utils.getAppActivity().getWindow().getDecorView();
            if (root != observedActivityRoot || inputLayoutListener == null) {
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
        attachToChatInput(root, true);
    }

    private boolean attachToChatInput(View root, boolean updateVisibility) {
        if (root == null || recordButton == null) {
            return false;
        }

        FlexEditText candidateEditText = root.findViewById(Utils.getResId("text_input", "id"));
        ViewGroup candidateContainer = root.findViewById(Utils.getResId("main_input_container", "id"));
        ViewParent candidateParent = candidateContainer == null ? null : candidateContainer.getParent();
        if (candidateEditText == null || candidateContainer == null || !(candidateParent instanceof RelativeLayout)) {
            return false;
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
                return false;
            }
        }

        if (recordButton.getParent() != inputLayout) {
            updateButtonPlacement();
        } else if (isIntegratedButton()) {
            updateButtonPlacement();
        }

        updateRecordingUi();
        if (updateVisibility) {
            updateRecordButtonVisibility();
        }
        removeActivityLayoutObserver();
        return true;
    }

    private void updateButtonPlacement() {
        if (recordButton == null || inputLayout == null || inputContainer == null) {
            return;
        }

        ViewGroup target = isIntegratedButton() ? inputContainer : inputLayout;
        if (recordButton.getParent() != target) {
            if (!attachView(recordButton, target, -1, createButtonLayoutParams())) {
                return;
            }
        } else {
            recordButton.setLayoutParams(createButtonLayoutParams());
        }
        updateInputContainerLayout(recordButton.getVisibility() == View.VISIBLE);
    }

    private ViewGroup.LayoutParams createButtonLayoutParams() {
        if (isIntegratedButton()) {
            var params = new LinearLayout.LayoutParams(
                    DimenUtils.dpToPx(36),
                    DimenUtils.dpToPx(36)
            );
            params.gravity = Gravity.CENTER_VERTICAL;
            return params;
        }

        var params = new RelativeLayout.LayoutParams(
                DimenUtils.dpToPx(36),
                DimenUtils.dpToPx(36)
        );
        params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE);
        params.rightMargin = DimenUtils.dpToPx(12);
        return params;
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
        int anchorId = buttonVisible && !isIntegratedButton()
                ? recordButton.getId()
                : Utils.getResId("send_btn_container", "id");
        int rightMargin = DimenUtils.dpToPx(8);
        int[] rules = params.getRules();
        if (rules[RelativeLayout.LEFT_OF] == anchorId && params.rightMargin == rightMargin) {
            return;
        }
        params.addRule(RelativeLayout.LEFT_OF, anchorId);
        params.rightMargin = rightMargin;
        inputContainer.setLayoutParams(params);
    }

    private void configureRecordingFormat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || settings.getBool("legacyOgg", false)) {
            outputFormat = MediaRecorder.OutputFormat.OGG;
            audioEncoder = MediaRecorder.AudioEncoder.OPUS;
            extension = ".ogg";
        } else {
            configureM4aRecordingFormat();
        }
    }

    private void configureM4aRecordingFormat() {
        outputFormat = MediaRecorder.OutputFormat.MPEG_4;
        audioEncoder = MediaRecorder.AudioEncoder.AAC;
        extension = ".m4a";
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

        configureRecordingFormat();
        waveFormView.reset();
        outputFile = File.createTempFile("audio_record", extension, baseDirectory);
        outputFile.deleteOnExit();

        MediaRecorder recorder;
        try {
            recorder = createRecorder(outputFile);
        } catch (IOException | RuntimeException e) {
            if (!isLegacyOggEnabled()) {
                deleteFile(outputFile);
                outputFile = null;
                throw e;
            }

            // Ogg/Opus is not guaranteed to be available before Android 10.
            // Keep the opt-in useful without breaking recording on unsupported devices.
            logger.error(e);
            deleteFile(outputFile);
            configureM4aRecordingFormat();
            outputFile = File.createTempFile("audio_record", extension, baseDirectory);
            outputFile.deleteOnExit();
            try {
                recorder = createRecorder(outputFile);
            } catch (IOException | RuntimeException fallbackError) {
                deleteFile(outputFile);
                outputFile = null;
                throw fallbackError;
            }
        }

        mediaRecorder = recorder;
        isRecording = true;
        delayedStopPending = false;
        recordingStartedAt = System.currentTimeMillis();
        waveformReadFailed = false;
        updateRecordingUi();

        updateWaveformThread = new Thread(updateWaveform, "VoiceMessages-Waveform");
        updateWaveformThread.start();
        return true;
    }

    private void openAudioFilePicker() {
        if (audioFileSendInProgress) {
            return;
        }

        try {
            AppActivity activity = Utils.getAppActivity();
            FragmentManager fragmentManager = activity.getSupportFragmentManager();
            if (fragmentManager.isStateSaved()) {
                Utils.showToast("Please try again in a moment");
                return;
            }
            if (fragmentManager.findFragmentByTag(AUDIO_FILE_PICKER_TAG) != null) {
                return;
            }

            audioFileChannelId = StoreStream.getChannelsSelected().getId();
            audioFileReply = getPendingReply(audioFileChannelId);
            AudioFilePickerFragment picker = new AudioFilePickerFragment();
            fragmentManager.beginTransaction().add(picker, AUDIO_FILE_PICKER_TAG).commitNow();
            picker.open();
        } catch (RuntimeException e) {
            clearAudioFileSelection();
            logger.error(e);
            Utils.showToast("Unable to open the audio file picker");
        }
    }

    void onAudioFilePickerCancelled() {
        clearAudioFileSelection();
    }

    void onAudioFilePicked(Uri uri) {
        if (uri == null) {
            clearAudioFileSelection();
            return;
        }

        long channelId = audioFileChannelId;
        VoiceMessageBody.MessageReference reply = audioFileReply;
        clearAudioFileSelection();
        if (channelId == 0L || audioFileSendInProgress) {
            return;
        }

        audioFileSendInProgress = true;
        updateRecordButtonVisibility();
        Utils.showToast("Sending audio file");
        audioFileTask = Utils.threadPool.submit(() -> sendAudioFile(uri, channelId, reply));
    }

    private void sendAudioFile(Uri uri, long channelId, VoiceMessageBody.MessageReference reply) {
        File audioFile = null;
        try {
            audioFile = copyAudioFile(uri);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (!audioFile.isFile() || audioFile.length() <= 0) {
                throw new IOException("The selected audio file is empty");
            }

            float duration = getRecordingDurationSeconds(audioFile, 1000L);
            String waveform = android.util.Base64.encodeToString(new byte[]{1}, android.util.Base64.NO_WRAP);
            String filename = DiscordAPI.uploadFile(audioFile, channelId, ".ogg");
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            DiscordAPI.sendVoiceMessage(filename, duration, waveform, channelId, ".ogg", reply);
            if (reply != null) {
                StoreStream.Companion.getPendingReplies().onDeletePendingReply(channelId);
            }
        } catch (IOException | RuntimeException e) {
            logger.error(e);
            Utils.showToast("Failed to send audio file");
        } finally {
            deleteFile(audioFile);
            if (instance == this) {
                audioFileTask = null;
                audioFileSendInProgress = false;
                Utils.mainThread.post(this::updateRecordButtonVisibility);
            }
        }
    }

    private File copyAudioFile(Uri uri) throws IOException {
        File baseDirectory = new File(Constants.BASE_PATH);
        if (!baseDirectory.isDirectory() && !baseDirectory.mkdirs()) {
            throw new IOException("Could not create the voice message directory");
        }

        File destination = File.createTempFile("audio_file", ".ogg", baseDirectory);
        destination.deleteOnExit();
        try (InputStream input = Utils.getAppContext().getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Could not read the selected audio file");
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            deleteFile(destination);
            throw e;
        }
        return destination;
    }

    private MediaRecorder createRecorder(File file) throws IOException {
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(outputFormat);
            recorder.setAudioEncoder(audioEncoder);
            recorder.setAudioChannels(1);

            int quality = settings.getInt("audioQuality", 128);
            recorder.setAudioEncodingBitRate(Math.max(32000, Math.min(192000, quality * 1024)));
            setSamplingRate(recorder);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            return recorder;
        } catch (IOException | RuntimeException e) {
            releaseRecorder(recorder);
            throw e;
        }
    }

    private boolean isLegacyOggEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && settings.getBool("legacyOgg", false);
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

        long elapsedMillis = Math.max(0, System.currentTimeMillis() - recordingStartedAt);
        if (send && elapsedMillis < MIN_RECORDING_MILLIS) {
            if (!delayedStopPending) {
                delayedStopPending = true;
                Utils.mainThread.postDelayed(() -> {
                    delayedStopPending = false;
                    if (isRecording) {
                        onRecordStop(true, discordid);
                    }
                }, MIN_RECORDING_MILLIS - elapsedMillis);
            }
            return;
        }
        delayedStopPending = false;

        isRecording = false;
        if (updateWaveformThread != null) {
            updateWaveformThread.interrupt();
        }

        File recordedFile = outputFile;
        outputFile = null;
        String recordingExtension = extension;
        String waveform = waveFormView.getWaveForm();
        VoiceMessageBody.MessageReference reply = recordingReply;
        recordingReply = null;
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        boolean stopped = send ? stopRecorder(recorder) : releaseRecorder(recorder);
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
            recordIcon.setAlpha(getButtonAlpha());
        }
        if (recordButton != null) {
            recordButton.setContentDescription(isRecording
                    ? "Stop recording voice message"
                    : "Choose voice message type");
        }
    }

    private void updateRecordButtonVisibility() {
        if (recordButton == null || editText == null) {
            return;
        }

        Editable text = editText.getText();
        boolean hasAttachments = attachmentPreview != null
                && attachmentPreview.getVisibility() == View.VISIBLE;
        boolean canType = isRecording || canUseComposer();
        boolean buttonVisible = !audioFileSendInProgress
                && canType
                && !hasAttachments
                && (isRecording || text == null || text.length() == 0);
        recordButton.setVisibility(buttonVisible ? View.VISIBLE : View.GONE);
        updateInputContainerLayout(buttonVisible);
    }

    private boolean canUseComposer() {
        if (!editText.isEnabled() || !editText.isFocusable() || !isViewTreeVisible(editText)) {
            return false;
        }
        View cannotSendText = inputLayout == null
                ? null
                : inputLayout.findViewById(Utils.getResId("cannot_send_text", "id"));
        return cannotSendText == null || !isViewTreeVisible(cannotSendText);
    }

    private boolean isViewTreeVisible(View view) {
        View current = view;
        while (current != null) {
            if (current.getVisibility() != View.VISIBLE) {
                return false;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return true;
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
                logger.debug("MediaRecorder could not finalize the recording; discarding it");
            } finally {
                releaseRecorder(recorder);
            }
        }
        return stopped;
    }

    private boolean releaseRecorder(MediaRecorder recorder) {
        if (recorder == null) {
            return true;
        }
        try {
            recorder.release();
        } catch (RuntimeException e) {
            logger.error(e);
            return false;
        }
        return true;
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

    private void clearAudioFileSelection() {
        audioFileChannelId = 0L;
        audioFileReply = null;
    }

    private void removeAudioFilePicker() {
        try {
            AppActivity activity = Utils.getAppActivity();
            FragmentManager fragmentManager = activity.getSupportFragmentManager();
            Fragment picker = fragmentManager.findFragmentByTag(AUDIO_FILE_PICKER_TAG);
            if (picker != null) {
                fragmentManager.beginTransaction().remove(picker).commitAllowingStateLoss();
            }
        } catch (RuntimeException e) {
            logger.debug("Could not remove the audio file picker fragment");
        }
    }

    @Override
    public void stop(Context context) {
        dismissVoiceModePopup();
        removeAudioFilePicker();
        clearAudioFileSelection();
        Future<?> pendingAudioFileTask = audioFileTask;
        if (pendingAudioFileTask != null) {
            pendingAudioFileTask.cancel(true);
            audioFileTask = null;
        }
        audioFileSendInProgress = false;
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
