package com.aliucord.plugins;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;

import java.util.Locale;

public final class BottomShit extends BottomSheet {
    private static final int DEFAULT_AUDIO_QUALITY = 128;

    private final SettingsAPI settings;

    public BottomShit(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 24));

        addIntro(context, "Customize voice recording quality and the appearance of the voice message button.");

        addSectionHeader(context, "Recording", true);
        CheckedSetting highSamplingRate = Utils.createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Increases sampling rate",
                "May fix recordings that play too quickly on some devices"
        );
        highSamplingRate.setChecked(settings.getBool("highSamplingRate", true));
        highSamplingRate.setOnCheckedListener(value -> settings.setBool("highSamplingRate", value));
        addSetting(highSamplingRate, context, 4);

        addSectionHeader(context, "Appearance", false);
        addColorSetting(context, "Voice button color", "Button background color", "buttonColor",
                VoiceMessages.DEFAULT_BUTTON_COLOR);
        addColorSetting(context, "Voice icon color", "Microphone color when idle", "buttonIconColor",
                VoiceMessages.DEFAULT_ICON_COLOR);
        CheckedSetting translucentButton = Utils.createCheckedSetting(
                context,
                CheckedSetting.ViewType.SWITCH,
                "Translucent button",
                "Use a semi-transparent background for the voice button"
        );
        translucentButton.setChecked(settings.getBool("translucentButton", false));
        translucentButton.setOnCheckedListener(value -> {
            settings.setBool("translucentButton", value);
            VoiceMessages.refreshButtonColor();
        });
        addSetting(translucentButton, context, 4);

        addSectionHeader(context, "Audio quality", false);
        addQualitySettings(context);
    }

    private void addIntro(Context context, String text) {
        TextView intro = new TextView(context);
        intro.setText(text);
        intro.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setLineSpacing(0, 1.1f);
        intro.setPadding(0, 0, 0, dp(context, 4));
        getLinearLayout().addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView header = new TextView(context);
        header.setText(title.toUpperCase(Locale.ROOT));
        header.setTextColor(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, dp(context, first ? 12 : 24), 0, dp(context, 8));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addSetting(View setting, Context context, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, bottomMargin);
        getLinearLayout().addView(setting, params);
    }

    private void addColorSetting(Context context, String title, String subtitle, String key, int defaultColor) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 64));
        row.setPaddingRelative(dp(context, 16), dp(context, 9), dp(context, 12), dp(context, 9));
        row.setClickable(true);
        row.setFocusable(true);
        android.graphics.drawable.Drawable selectable = selectableBackground(context);
        if (selectable != null) {
            row.setBackground(selectable);
        }

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        textColumn.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView summaryView = new TextView(context);
        summaryView.setText(subtitle + " • " + colorHex(getColor(key, defaultColor)));
        summaryView.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryView.setPadding(0, dp(context, 2), 0, 0);
        textColumn.addView(summaryView, new LinearLayout.LayoutParams(-1, -2));

        row.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1f));

        View swatch = new View(context);
        updateSwatch(swatch, getColor(key, defaultColor), context);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));

        row.setOnClickListener(ignored -> showColorPicker(
                context, title, subtitle, key, defaultColor, swatch, summaryView
        ));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 4);
        getLinearLayout().addView(row, params);
    }

    private void addQualitySettings(Context context) {
        String[] labels = {"High", "Normal", "Low"};
        int[] values = {192, 128, 64};
        int selectedValue = settings.getInt("audioQuality", DEFAULT_AUDIO_QUALITY);
        if (selectedValue != 192 && selectedValue != 128 && selectedValue != 64) {
            selectedValue = DEFAULT_AUDIO_QUALITY;
        }

        RadioButton[] radios = new RadioButton[values.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(context, 52));
            row.setPaddingRelative(dp(context, 16), dp(context, 4), dp(context, 8), dp(context, 4));
            row.setClickable(true);
            row.setFocusable(true);
            android.graphics.drawable.Drawable selectable = selectableBackground(context);
            if (selectable != null) {
                row.setBackground(selectable);
            }

            TextView label = new TextView(context);
            label.setText(labels[i]);
            label.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            RadioButton radio = new RadioButton(context);
            radio.setChecked(values[i] == selectedValue);
            radios[i] = radio;
            row.addView(radio, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));

            View.OnClickListener listener = ignored -> {
                for (RadioButton item : radios) {
                    item.setChecked(item == radio);
                }
                settings.setInt("audioQuality", values[index]);
            };
            row.setOnClickListener(listener);
            radio.setOnClickListener(listener);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(context, 2);
            getLinearLayout().addView(row, params);
        }
    }

    private void updateSwatch(View swatch, int color, Context context) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        background.setStroke(dp(context, 1), themeColor(context, "colorBackgroundTertiary", Color.GRAY));
        swatch.setBackground(background);
    }

    private void showColorPicker(
            Context context,
            String title,
            String subtitle,
            String key,
            int defaultColor,
            View swatch,
            TextView summaryView
    ) {
        int initialColor = getColor(key, defaultColor);
        ColorPickerView picker = new ColorPickerView(context, initialColor);
        TextView dialogTitle = new TextView(context);
        dialogTitle.setText(title);
        dialogTitle.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        dialogTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        dialogTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dialogTitle.setPadding(0, 0, 0, dp(context, 4));
        TextView hex = new TextView(context);
        hex.setGravity(Gravity.CENTER);
        hex.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        hex.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        content.addView(dialogTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView description = new TextView(context);
        description.setText(subtitle);
        description.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.bottomMargin = dp(context, 16);
        content.addView(description, descriptionParams);
        content.addView(picker, new LinearLayout.LayoutParams(-1, dp(context, 260)));
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(-1, dp(context, 48));
        hexParams.topMargin = dp(context, 8);
        content.addView(hex, hexParams);
        updatePreview(hex, initialColor);

        picker.setOnColorChangedListener(color -> updatePreview(hex, color));
        androidx.appcompat.app.AlertDialog alertDialog = new androidx.appcompat.app.AlertDialog.Builder(context)
                .setView(content)
                .setNeutralButton("Reset", (ignoredDialog, which) -> {
                    settings.setInt(key, defaultColor);
                    updateSwatch(swatch, defaultColor, context);
                    summaryView.setText(subtitle + " • " + colorHex(defaultColor));
                    VoiceMessages.refreshButtonColor();
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (ignoredDialog, which) -> {
                    int color = picker.getColor();
                    settings.setInt(key, color);
                    updateSwatch(swatch, color, context);
                    summaryView.setText(subtitle + " • " + colorHex(color));
                    VoiceMessages.refreshButtonColor();
                })
                .create();
        alertDialog.show();
        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR));
        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setTextColor(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR));
        alertDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(themeColor(context, "colorBrand", VoiceMessages.DEFAULT_BUTTON_COLOR));
    }

    private void updatePreview(TextView preview, int color) {
        preview.setText(colorHex(color));
    }

    private int getColor(String key, int defaultColor) {
        int color = settings.getInt(key, defaultColor);
        return Color.alpha(color) == 0 ? defaultColor : color;
    }

    private String colorHex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private android.graphics.drawable.Drawable selectableBackground(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) {
            return ContextCompat.getDrawable(context, value.resourceId);
        }
        return null;
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        if (id == 0) {
            return fallback;
        }

        android.util.TypedValue value = new android.util.TypedValue();
        if (!context.getTheme().resolveAttribute(id, value, true)) {
            return fallback;
        }
        if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        if (value.resourceId != 0) {
            try {
                return ContextCompat.getColor(context, value.resourceId);
            } catch (RuntimeException ignored) {
                // Fall back if a theme value is not a color resource on this Discord theme.
            }
        }
        return ColorCompat.getThemedColor(context, id);
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
