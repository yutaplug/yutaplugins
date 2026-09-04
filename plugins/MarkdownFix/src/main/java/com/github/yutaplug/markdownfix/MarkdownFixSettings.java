package com.github.yutaplug.markdownfix;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;
import com.discord.views.CheckedSetting;

import java.util.Locale;

/** Settings for MarkdownFix's block formatting and list appearance. */
public final class MarkdownFixSettings extends BottomSheet {
    private final SettingsAPI settings;

    public MarkdownFixSettings(SettingsAPI settings) {
        this.settings = settings;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8),
                dp(context, 20), dp(context, 24));

        addIntro(context, "Tune MarkdownFix's headers, subtext, and bullet-list appearance. "
                + "Scale values use 1.0 as the normal text size.");

        addSectionHeader(context, "Text sizes", true);
        addScaleAction(context, "# header size", "Scale for # headers", MarkdownFix.HEADER_1_SCALE,
                MarkdownFix.DEFAULT_HEADER_1_SCALE);
        addScaleAction(context, "## header size", "Scale for ## headers", MarkdownFix.HEADER_2_SCALE,
                MarkdownFix.DEFAULT_HEADER_2_SCALE);
        addScaleAction(context, "### header size", "Scale for ### headers", MarkdownFix.HEADER_3_SCALE,
                MarkdownFix.DEFAULT_HEADER_3_SCALE);
        addScaleAction(context, "-# subtext size", "Scale for -# subtext", MarkdownFix.SUBTEXT_SCALE,
                MarkdownFix.DEFAULT_SUBTEXT_SCALE);

        addSectionHeader(context, "Bullet lists", false);
        addToggle(context, "Compact web-style bullets",
                "Use smaller, tighter bullets similar to Discord on the web",
                MarkdownFix.COMPACT_BULLETS);
        addToggle(context, "Custom bullet color",
                "Color bullet points using your selected color",
                MarkdownFix.CUSTOM_BULLET_COLOR);
        addAction(context, "Bullet color", "Current: " + currentBulletColor(),
                () -> showColorDialog(context));
    }

    private void addScaleAction(Context context, String title, String description,
                                String key, float fallback) {
        float value = MarkdownFix.readScale(settings, key, fallback);
        addAction(context, title, description + " (currently " + format(value) + ")",
                () -> showScaleDialog(context, title, key, value, fallback));
    }

    private void showScaleDialog(Context context, String title, String key,
                                 float current, float fallback) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("For example: 1.25");
        input.setText(format(current));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage("Enter a scale from 0.1 to 3.0. 1.0 is normal text size.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog, input);

            TextView save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) save.setOnClickListener(button -> {
                try {
                    float value = Float.parseFloat(input.getText().toString().trim());
                    if (value < 0.1f || value > 3.0f) throw new NumberFormatException();
                    settings.setString(key, Float.toString(value));
                    dialog.dismiss();
                    showRestartDialog(context);
                } catch (Throwable error) {
                    Utils.showToast("Enter a number from 0.1 to 3.0");
                }
            });

            TextView reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (reset != null) reset.setOnClickListener(button -> {
                settings.setString(key, Float.toString(fallback));
                dialog.dismiss();
                showRestartDialog(context);
            });
        });
        dialog.show();
    }

    private void showColorDialog(Context context) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("#RRGGBB or #AARRGGBB");
        input.setText(settings.getString(MarkdownFix.BULLET_COLOR, MarkdownFix.DEFAULT_BULLET_COLOR));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Bullet color")
                .setMessage("Enter a hexadecimal color.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog, input);
            TextView save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) save.setOnClickListener(button -> {
                String value = normalizeColor(input.getText().toString());
                try {
                    Color.parseColor(value);
                    settings.setString(MarkdownFix.BULLET_COLOR, value);
                    dialog.dismiss();
                    showRestartDialog(context);
                } catch (Throwable error) {
                    Utils.showToast("Enter #RRGGBB or #AARRGGBB");
                }
            });
        });
        dialog.show();
    }

    private void showRestartDialog(Context context) {
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("Restart required")
                .setMessage("Restart Discord for this MarkdownFix setting to take effect.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Restart", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog, null);
            TextView restart = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (restart != null) restart.setOnClickListener(button -> {
                dialog.dismiss();
                Utils.restartAliucord(context);
            });
        });
        dialog.show();
    }

    private void styleDialog(AlertDialog dialog, EditText input) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.rgb(54, 57, 63)));
        }

        int titleId = dialog.getContext().getResources().getIdentifier(
                "alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title == null) title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) title.setTextColor(Color.WHITE);

        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(Color.WHITE);
        if (input != null) {
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.LTGRAY);
        }

        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(Color.WHITE);
        }
    }

    private String currentBulletColor() {
        String value = settings.getString(MarkdownFix.BULLET_COLOR, MarkdownFix.DEFAULT_BULLET_COLOR);
        String normalized = normalizeColor(value);
        try {
            Color.parseColor(normalized);
            return normalized;
        } catch (Throwable ignored) {
            return MarkdownFix.DEFAULT_BULLET_COLOR;
        }
    }

    private String normalizeColor(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.startsWith("#") ? value.toUpperCase(Locale.ROOT)
                : "#" + value.toUpperCase(Locale.ROOT);
    }

    private void addToggle(Context context, String title, String subtitle, String key) {
        CheckedSetting setting = Utils.createCheckedSetting(
                context, CheckedSetting.ViewType.SWITCH, title, subtitle);
        setting.setChecked(settings.getBool(key, false));
        setting.setOnCheckedListener(value -> {
            settings.setBool(key, value);
            showRestartDialog(context);
        });
        addView(setting);
    }

    private void addAction(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 58));
        row.setPaddingRelative(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        row.setBackground(selectableBackground(context));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(themeColor(context, "colorHeaderPrimary", Color.WHITE));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitleView = new TextView(context);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitleView.setPadding(0, dp(context, 2), 0, 0);
        row.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));

        row.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(context, 4);
        getLinearLayout().addView(row, params);
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
        header.setTextColor(themeColor(context, "colorBrand", Color.rgb(88, 101, 242)));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(0, first ? dp(context, 12) : dp(context, 24), 0, dp(context, 8));
        getLinearLayout().addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private android.graphics.drawable.Drawable selectableBackground(Context context) {
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) return context.getDrawable(value.resourceId);
        return null;
    }

    private int themeColor(Context context, String attribute, int fallback) {
        int id = Utils.getResId(attribute, "attr");
        return id == 0 ? fallback : ColorCompat.getThemedColor(context, id);
    }

    private String format(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
