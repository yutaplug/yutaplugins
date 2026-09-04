package com.github.yutaplug.fakedecor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliucord.Utils;
import com.aliucord.api.SettingsAPI;
import com.aliucord.views.TextInput;
import com.aliucord.widgets.BottomSheet;
import com.discord.utilities.color.ColorCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FakeDecorSettings extends BottomSheet {
    private static final int PICK_DECORATION = 4831;

    private final SettingsAPI settings;
    private final FakeDecor plugin;
    private EditText assetInput;
    private TextView authorizationStatus;

    public FakeDecorSettings(SettingsAPI settings, FakeDecor plugin) {
        this.settings = settings;
        this.plugin = plugin;
    }

    @Override
    public void onViewCreated(View view, Bundle bundle) {
        super.onViewCreated(view, bundle);
        Context context = requireContext();
        getLinearLayout().setPadding(dp(context, 20), dp(context, 8),
                dp(context, 20), dp(context, 24));

        addIntro(context, "Create and use custom avatar decorations on Discord. "
                + "Your selection is saved separately for each Discord account.");

        addSectionHeader(context, "Local decoration", true);
        TextInput asset = new TextInput(context, "Decoration hash or asset", plugin.getSelectedAsset());
        assetInput = asset.getEditText();
        assetInput.setSingleLine(true);
        addContentView(asset, 6);

        addAction(context, "Apply locally", "Use this decoration in messages, lists, and profiles", () -> {
            plugin.setSelectedAsset(assetInput.getText().toString());
            Utils.showToast(assetInput.getText().toString().trim().isEmpty()
                    ? "Decoration removed" : "Decoration applied; reopen the current view");
        });
        addAction(context, "Browse Decor presets", "Choose from Decor's available preset decorations",
                () -> plugin.fetchPresets(this));

        addSectionHeader(context, "Decor account", false);
        authorizationStatus = statusView(context);
        addContentView(authorizationStatus, 8);
        refreshAuthState();

        addAction(context, "Authorize with Discord",
                "Open the system browser to connect the current Discord account",
                () -> plugin.authorizeDecor(context));
        addAction(context, "Finish authorization",
                "Copy the returned token, then tap this button",
                () -> plugin.finishBrowserAuthorization(context, this));
        addAction(context, "Disconnect Decor", "Remove the saved Decor access for this account", () -> {
            plugin.disconnectDecor();
            refreshAuthState();
        });

        addSectionHeader(context, "Cloud actions", false);
        addAction(context, "My Decor decorations", "View, select, or delete decorations on your account",
                () -> plugin.fetchOwnDecorations(this));
        addAction(context, "Sync from Decor", "Load the decoration currently saved to Decor",
                plugin::refreshOwnDecoration);
        addAction(context, "Sync selection to Decor", "Save this selection to your Decor account",
                plugin::applyToDecorService);
        addAction(context, "Upload custom decoration", "Submit a PNG or APNG decoration for review", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_DECORATION);
        });

        addSectionHeader(context, "About", false);
        addIntro(context, "Decor is a separate service. Preset browsing and local selection work without "
                + "an account; cloud actions require authorization. Uploaded decorations may need review "
                + "before they can be selected.");
    }

    void refreshAuthState() {
        if (authorizationStatus == null) return;
        boolean authorized = plugin.isAuthorized();
        authorizationStatus.setText(authorized
                ? "●  Connected to Decor for this Discord account"
                : "○  Not connected to Decor");
        authorizationStatus.setTextColor(authorized
                ? Color.rgb(67, 181, 129)
                : themeColor(requireContext(), "colorTextMuted", Color.LTGRAY));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_DECORATION && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            plugin.uploadDecoration(requireContext(), data.getData());
        }
    }

    public void showPresets(List<?> presets) {
        List<String> labels = new ArrayList<>();
        List<String> assets = new ArrayList<>();
        for (Object rawPreset : presets) {
            if (!(rawPreset instanceof Map)) continue;
            Map<?, ?> preset = (Map<?, ?>) rawPreset;
            Object rawDecorations = preset.get("decorations");
            if (!(rawDecorations instanceof List)) continue;
            String presetName = String.valueOf(preset.get("name"));
            for (Object rawDecoration : (List<?>) rawDecorations) {
                if (!(rawDecoration instanceof Map)) continue;
                Map<?, ?> decoration = (Map<?, ?>) rawDecoration;
                String asset = decorationAsset(decoration);
                if (asset.isEmpty()) continue;
                String alt = decoration.get("alt") == null
                        ? asset : String.valueOf(decoration.get("alt"));
                labels.add(presetName + ": " + alt);
                assets.add(asset);
            }
        }
        if (labels.isEmpty()) {
            Utils.showToast("No Decor presets found");
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                requireContext(), android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(Color.WHITE);
                return row;
            }
        };

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Decor presets")
                .setAdapter(adapter, (dialogInterface, which) -> {
                    assetInput.setText(assets.get(which));
                    assetInput.setSelection(assetInput.length());
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            TextView cancel = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.setTextColor(Color.WHITE);
        });
        dialog.show();
    }

    public void showOwnDecorations(List<?> decorations) {
        List<Map<?, ?>> valid = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String selected = plugin.getSelectedAsset();

        for (Object rawDecoration : decorations) {
            if (!(rawDecoration instanceof Map)) continue;
            Map<?, ?> decoration = (Map<?, ?>) rawDecoration;
            String asset = decorationAsset(decoration);
            if (asset.isEmpty()) continue;
            valid.add(decoration);
            String alt = decoration.get("alt") == null
                    ? asset : String.valueOf(decoration.get("alt"));
            String state = Boolean.FALSE.equals(decoration.get("reviewed"))
                    ? " (pending review)" : "";
            labels.add((asset.equals(selected) ? "✓ " : "") + alt + state);
        }

        if (valid.isEmpty()) {
            Utils.showToast("No Decor decorations found");
            return;
        }

        int selectedIndex = 0;
        for (int i = 0; i < valid.size(); i++) {
            if (decorationAsset(valid.get(i)).equals(selected)) {
                selectedIndex = i;
                break;
            }
        }
        final int[] checked = {selectedIndex};
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("My Decor decorations")
                .setSingleChoiceItems(labels.toArray(new String[0]), selectedIndex,
                        (ignored, which) -> checked[0] = which)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Delete", null)
                .setPositiveButton("Use", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(button -> {
                        if (Boolean.FALSE.equals(valid.get(checked[0]).get("reviewed"))) {
                            Utils.showToast("This decoration is still pending review");
                            return;
                        }
                        plugin.setSelectedAsset(decorationAsset(valid.get(checked[0])));
                        assetInput.setText(plugin.getSelectedAsset());
                        dialog.dismiss();
                        Utils.showToast("Decoration applied; reopen the current view");
                    });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(button -> {
                        Object hash = valid.get(checked[0]).get("hash");
                        if (hash != null) {
                            plugin.deleteDecoration(String.valueOf(hash));
                            dialog.dismiss();
                        }
                    });
        });
        dialog.show();
    }

    private void addIntro(Context context, String text) {
        TextView result = new TextView(context);
        result.setText(text);
        result.setTextColor(themeColor(context, "colorTextMuted", Color.LTGRAY));
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        result.setLineSpacing(0, 1.1f);
        result.setPadding(0, 0, 0, dp(context, 4));
        addContentView(result, 0);
    }

    private void addSectionHeader(Context context, String title, boolean first) {
        TextView result = new TextView(context);
        result.setText(title.toUpperCase(java.util.Locale.ROOT));
        result.setTextColor(themeColor(context, "colorBrand", Color.rgb(88, 101, 242)));
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setLetterSpacing(0.08f);
        result.setPadding(0, first ? dp(context, 12) : dp(context, 24), 0, dp(context, 8));
        addContentView(result, 0);
    }

    private TextView statusView(Context context) {
        TextView result = new TextView(context);
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        result.setGravity(Gravity.CENTER_VERTICAL);
        result.setPaddingRelative(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(themeColor(context, "colorBackgroundSecondary", Color.rgb(47, 49, 54)));
        background.setCornerRadius(dp(context, 10));
        result.setBackground(background);
        return result;
    }

    private void addAction(Context context, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 60));
        row.setPaddingRelative(dp(context, 16), dp(context, 9), dp(context, 16), dp(context, 9));
        row.setBackground(selectableBackground(context));
        row.setClickable(true);
        row.setFocusable(true);

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
        addContentView(row, 4);
    }

    private void addContentView(View view, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(requireContext(), bottomMargin);
        getLinearLayout().addView(view, params);
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

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String decorationAsset(Map<?, ?> decoration) {
        Object hash = decoration.get("hash");
        if (hash == null || "null".equals(String.valueOf(hash))) return "";
        boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
        return (animated ? "a_" : "") + hash;
    }
}
