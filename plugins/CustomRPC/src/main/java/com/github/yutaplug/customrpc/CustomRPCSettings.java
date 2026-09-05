package com.github.yutaplug.customrpc;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.utils.DimenUtils;
import com.aliucord.views.TextInput;
import com.aliucord.widgets.BottomSheet;
import com.discord.api.activity.ActivityType;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;

import java.util.*;

import androidx.appcompat.R;
import androidx.appcompat.app.AlertDialog;

public final class CustomRPCSettings extends BottomSheet {
    private final SettingsAPI settings;
    private final CustomRPC plugin;

    private final Map<String, EditText> inputs = new LinkedHashMap<>();

    private CheckedSetting enabledSetting;
    private TextView activityTypeSummary;
    private TextView activityFlagsSummary;
    private EditText applicationId;
    private EditText name;
    private EditText details;
    private EditText state;
    private EditText largeImage;
    private EditText largeImageText;
    private EditText smallImage;
    private EditText smallImageText;

    public CustomRPCSettings(SettingsAPI settings, CustomRPC plugin) {
        this.settings = settings;
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8),
                dp(context, 20), dp(context, 24));

        addIntro(context, "Set the activity shown on your Discord profile. Changes are sent through Discord's gateway.");

        enabledSetting = Utils.createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Enable CustomRPC",
                "Show the configured activity to other Discord users");
        enabledSetting.setChecked(settings.getBool(CustomRPC.ENABLED, false));
        enabledSetting.setOnCheckedListener(value -> {
            if (value) plugin.enableActivitySharing(requireActivity());
            plugin.setEnabled(value);
        });
        addView(enabledSetting);

        addSectionHeader(context, "Activity", true);
        addActivityTypeAction(context);
        addActivityFlagsAction(context);
        name = addInput(context, "Activity name", CustomRPC.NAME, "Custom RPC");
        details = addInput(context, "Details", CustomRPC.DETAILS, "");
        state = addInput(context, "State", CustomRPC.STATE, "");

        addSectionHeader(context, "Application and assets", false);
        addIntro(context, "Application ID is optional for text-only activities. It is needed for images and full Rich Presence features.");
        applicationId = addInput(context, "Application ID (optional)", CustomRPC.APPLICATION_ID,
                "");
        applicationId.setInputType(InputType.TYPE_CLASS_NUMBER);
        addButton(context, "Get an Application ID", () -> openDeveloperApplications(context));
        largeImage = addInput(context, "Large image key (optional)", CustomRPC.LARGE_IMAGE,
                "");
        largeImageText = addInput(context, "Large image text (optional)", CustomRPC.LARGE_IMAGE_TEXT,
                "");
        smallImage = addInput(context, "Small image key (optional)", CustomRPC.SMALL_IMAGE,
                "");
        smallImageText = addInput(context, "Small image text (optional)", CustomRPC.SMALL_IMAGE_TEXT,
                "");

        addButton(context, "Save and apply", () -> {
            plugin.enableActivitySharing(requireActivity());
            boolean applied = plugin.saveAndApply(
                    text(applicationId),
                    text(name),
                    text(details),
                    text(state),
                    text(largeImage),
                    text(largeImageText),
                    text(smallImage),
                    text(smallImageText)
            );
            if (applied) {
                enabledSetting.setChecked(true);
                Utils.showToast("CustomRPC applied");
            } else {
                enabledSetting.setChecked(false);
                Utils.showToast("Could not apply CustomRPC");
            }
        });
        addButton(context, "Remove activity", () -> {
            plugin.setEnabled(false);
            enabledSetting.setChecked(false);
            Utils.showToast("CustomRPC removed");
        });
    }

    private EditText addInput(Context context, String label, String key, String defaultValue) {
        String value = settings.getString(key, "");
        if (value == null || value.isEmpty()) value = defaultValue;
        TextInput input = new TextInput(context, label, value);
        EditText editor = input.getEditText();
        editor.setSingleLine(true);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(Color.LTGRAY);
        editor.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editor.setOnFocusChangeListener((ignored, hasFocus) -> {
            if (!hasFocus) saveChanges(key, editor);
        });
        editor.setOnEditorActionListener((ignored, actionId, event) -> {
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_DONE && !enterKey) return false;
            saveChanges(key, editor);
            editor.clearFocus();
            return true;
        });
        inputs.put(key, editor);
        addContentView(input, 6);
        return editor;
    }

    private void saveChanges(String key, EditText editor) {
        String value = text(editor).trim();
        String saved = settings.getString(key, "");
        if (value.equals(saved == null ? "" : saved.trim())) return;
        plugin.saveField(key, value);
    }

    @Override
    public void onPause() {
        super.onPause();
        for (Map.Entry<String, EditText> input : inputs.entrySet()) {
            saveChanges(input.getKey(), input.getValue());
        }
    }

    private void openDeveloperApplications(Context context) {
        try {
            context.startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://discord.com/developers/applications")
            ));
        } catch (Throwable error) {
            Utils.showToast("Could not open Discord Developer Portal");
        }
    }

    private void addActivityTypeAction(Context context) {
        activityTypeSummary = addAction(context, "Activity type", plugin.getActivityTypeLabel(), this::showActivityTypeDialog);
    }

    private void addActivityFlagsAction(Context context) {
        activityFlagsSummary = addAction(context, "Activity flags", plugin.getActivityFlagsLabel(), this::showActivityFlagsDialog);
    }

    private TextView addAction(Context context, String title, String summaryText, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 58));
        row.setPaddingRelative(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        row.setBackground(selectableBackground(context));
        row.setOnClickListener(ignored -> action.run());

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView activitySummary = new TextView(context);
        activitySummary.setText(summaryText);
        activitySummary.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        activitySummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        activitySummary.setPadding(0, dp(context, 2), 0, 0);
        row.addView(activitySummary, new LinearLayout.LayoutParams(-1, -2));
        addContentView(row, 4);
        return activitySummary;
    }

    private void showActivityTypeDialog() {
        ActivityType[] types = {
                ActivityType.PLAYING,
                ActivityType.STREAMING,
                ActivityType.LISTENING,
                ActivityType.WATCHING,
                ActivityType.COMPETING
        };
        String[] labels = {"Playing", "Streaming", "Listening to", "Watching", "Competing in"};
        ActivityType selectedType = plugin.getActivityType();
        int selected = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == selectedType) {
                selected = i;
                break;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Activity type")
                .setSingleChoiceItems(labels, selected, (dialogInterface, which) -> {
                    plugin.setActivityType(types[which]);
                    if (activityTypeSummary != null) {
                        activityTypeSummary.setText(labels[which]);
                    }
                    dialogInterface.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> styleActivityTypeDialog(dialog));
        dialog.show();
    }

    private void showActivityFlagsDialog() {
        boolean[] checked = new boolean[ActivityFlags.VALUES.length];
        int current = plugin.getActivityFlags();
        for (int i = 0; i < checked.length; i++) {
            checked[i] = (current & ActivityFlags.VALUES[i]) != 0;
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Activity flags")
                .setMultiChoiceItems(ActivityFlags.LABELS, checked,
                        (dialogInterface, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (dialogInterface, which) -> {
                    int flags = 0;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) flags |= ActivityFlags.VALUES[i];
                    }
                    plugin.setActivityFlags(flags);
                    if (activityFlagsSummary != null) {
                        activityFlagsSummary.setText(plugin.getActivityFlagsLabel());
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> styleActivityTypeDialog(dialog));
        dialog.show();
    }

    private void styleActivityTypeDialog(AlertDialog dialog) {
        TextView title = dialog.findViewById(R.id.alertTitle);
        if (title != null) title.setTextColor(Color.WHITE);

        for (int i = 0; i < dialog.getListView().getChildCount(); i++) {
            setTextColor(dialog.getListView().getChildAt(i), Color.WHITE);
        }

        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(Color.WHITE);
        }
    }

    private void setTextColor(View view, int color) {
        if (view instanceof TextView) ((TextView) view).setTextColor(color);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                setTextColor(group.getChildAt(i), color);
            }
        }
    }

    private void addButton(Context context, String label, Runnable action) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(ignored -> action.run());
        addContentView(button, 6);
    }

    private void addIntro(Context context, String text) {
        TextView intro = new TextView(context);
        intro.setText(text);
        intro.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setLineSpacing(0, 1.1f);
        intro.setPadding(0, 0, 0, dp(context, 4));
        addContentView(intro, 0);
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(Locale.ROOT));
        header.setTextColor(themeColor(context, "colorBrand", Color.rgb(88, 101, 242)));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, first ? dp(context, 12) : dp(context, 24), 0, dp(context, 8));
        addContentView(header, 0);
    }

    private void addContentView(View view, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(requireContext(), bottomMargin);
        getLinearLayout().addView(view, params);
    }

    private Drawable selectableBackground(Context context) {
        GradientDrawable card = new GradientDrawable();
        card.setColor(inputCardColor(context));
        card.setCornerRadius(DimenUtils.getDefaultCardRadius());
        int highlight = themeColor(context, "colorBackgroundModifierSelected", Color.argb(153, 79, 84, 92));
        return new RippleDrawable(ColorStateList.valueOf(highlight), card, null);
    }

    private int inputCardColor(Context context) {
        try {
            int color = new TextInput(context).getRoot().getBoxBackgroundColor();
            if (color != 0) return color;
        } catch (Throwable ignored) {}
        return themeColor(context, "colorBackgroundTertiary", Color.rgb(32, 34, 37));
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private String text(EditText editor) {
        return editor == null ? "" : editor.getText().toString();
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
