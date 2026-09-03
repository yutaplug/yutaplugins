package com.github.yutaplug.fakedecor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
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
                + "Your local selection is stored per Discord account.");

        addSectionHeader(context, "Local decoration", true);

        TextInput asset = new TextInput(context, "Decoration hash or asset",
                plugin.getSelectedAsset());
        assetInput = asset.getEditText();
        assetInput.setSingleLine(true);
        addContentView(asset, 6);

        addAction(context, "Browse Decor presets", "Choose from the available preset decorations",
                () -> plugin.fetchPresets(this));

        addAction(context, "Apply locally", "Use this decoration in messages, lists, and profiles", () -> {
            plugin.setSelectedAsset(assetInput.getText().toString());
            Utils.showToast(assetInput.getText().toString().trim().isEmpty()
                    ? "Decoration removed" : "Decoration applied; reopen the current view");
        });

        addSectionHeader(context, "Decor account", false);
        authorizationStatus = statusView(context);
        addContentView(authorizationStatus, 8);
        refreshAuthState();

        addAction(context, "Authorize with Discord",
                "Open the system browser to connect the current Discord account",
                () -> plugin.authorizeDecor(context, this));
        addAction(context, "Finish authorization",
                "Complete authorization after copying the returned Decor token",
                () -> plugin.finishBrowserAuthorization(context, this));
        addAction(context, "Disconnect Decor", "Remove the saved Decor access for this account", () -> {
            plugin.disconnectDecor();
            refreshAuthState();
        });

        addSectionHeader(context, "Cloud actions", false);
        addAction(context, "Sync from Decor", "Load the decoration currently saved to Decor",
                plugin::refreshOwnDecoration);
        addAction(context, "Sync selection to Decor", "Save your local selection to your Decor account",
                plugin::applyToDecorService);
        addAction(context, "Upload custom decoration", "Upload an image and apply it to your account", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_DECORATION);
        });

        addSectionHeader(context, "How authorization works", false);
        addIntro(context, "Cloud actions require a Decor account. Authorize in your system browser, "
                + "copy the returned token, and tap Finish authorization. Local decorations and "
                + "preset browsing work without an account.");
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
        if (requestCode == PICK_DECORATION && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            plugin.uploadDecoration(requireContext(), data.getData());
        }
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
                Object hash = decoration.get("hash");
                if (hash == null) continue;
                boolean animated = Boolean.TRUE.equals(decoration.get("animated"));
                String asset = (animated ? "a_" : "") + hash;
                String alt = decoration.get("alt") == null ? asset : String.valueOf(decoration.get("alt"));
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
}
