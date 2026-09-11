package com.github.yutaplug.roleiconcontrast;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.patcher.Hook;
import com.discord.api.role.GuildRole;
import com.discord.widgets.roles.RoleIconView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@AliucordPlugin
@SuppressWarnings("unused")
public final class RoleIconContrast extends Plugin {
    private final Map<RoleIconView, Drawable> originalBackgrounds =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void start(Context context) throws Throwable {
        patcher.patch(
                RoleIconView.class,
                "setRole",
                new Class<?>[]{GuildRole.class, Long.class},
                new Hook(param -> updateRoleIcon((RoleIconView) param.thisObject))
        );

        patcher.patch(
                RoleIconView.class,
                "setRoleIconPreview",
                new Class<?>[]{GuildRole.class},
                new Hook(param -> updateRoleIcon((RoleIconView) param.thisObject))
        );

        patcher.patch(
                RoleIconView.class,
                "setRoleIconPreview",
                new Class<?>[]{String.class},
                new Hook(param -> updateRoleIcon((RoleIconView) param.thisObject))
        );
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();

        Map<RoleIconView, Drawable> backgrounds;
        synchronized (originalBackgrounds) {
            backgrounds = new WeakHashMap<>(originalBackgrounds);
            originalBackgrounds.clear();
        }

        for (Map.Entry<RoleIconView, Drawable> entry : backgrounds.entrySet()) {
            RoleIconView view = entry.getKey();
            if (view != null) view.setBackground(entry.getValue());
        }
    }

    private void updateRoleIcon(RoleIconView view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            restoreBackground(view);
            return;
        }

        rememberOriginalBackground(view);
        view.setBackground(createContrastBackground());
    }

    private void rememberOriginalBackground(RoleIconView view) {
        synchronized (originalBackgrounds) {
            if (!originalBackgrounds.containsKey(view)) {
                originalBackgrounds.put(view, view.getBackground());
            }
        }
    }

    private void restoreBackground(RoleIconView view) {
        if (view == null) return;

        Drawable original;
        synchronized (originalBackgrounds) {
            if (!originalBackgrounds.containsKey(view)) return;
            original = originalBackgrounds.remove(view);
        }
        view.setBackground(original);
    }

    private static Drawable createContrastBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.WHITE);
        return background;
    }
}
