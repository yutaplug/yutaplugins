package com.github.yutaplug.fixes.fontscale

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.TypedValue
import com.aliucord.patcher.PreHook
import com.discord.app.AppActivity
import com.discord.stores.StoreStream
import com.discord.utilities.font.FontUtils
import com.github.yutaplug.fixes.Fix
import com.github.yutaplug.fixes.OpenActivities

internal class FontScaleFix :
    Fix(
        "FontScaleFix",
        "Font scaling",
        "Fixes the font scaling option not working.",
    ) {
    override fun onToggled() {
        // Each AppActivity remembers the font scale it was created with (q). When a stale one
        // resumes, Discord relaunches it itself, as after changing the font scale in Appearance.
        // The settings page in front is left alone; it updates when it is reopened.
        for (activity in OpenActivities.inBackground()) {
            if (activity is AppActivity) activity.q = STALE_FONT_SCALE
        }
    }

    private companion object {
        const val STALE_FONT_SCALE = Int.MIN_VALUE
    }

    override fun start(context: Context) {
        patcher.patch(
            AppActivity::class.java,
            "attachBaseContext",
            arrayOf(Context::class.java),
            PreHook { call ->
                val base = call.args[0] as? Context ?: return@PreHook
                // Discord mutates the original Configuration before asking Android
                // for a new context. A copy preserves the font-scale difference.
                val configuration = Configuration(base.resources.configuration)
                configuration.fontScale = FontUtils.INSTANCE.getTargetFontScaleFloat(base)
                call.args[0] = base.createConfigurationContext(configuration)
            },
        )
        patcher.patch(
            TypedValue::class.java,
            "applyDimension",
            arrayOf(Integer.TYPE, java.lang.Float.TYPE, DisplayMetrics::class.java),
            PreHook { call ->
                if (call.args[0] != TypedValue.COMPLEX_UNIT_SP) return@PreHook
                val percent = StoreStream.getUserSettingsSystem().fontScale
                // System mode keeps Android's native accessibility scaling curve.
                if (percent == FontUtils.USE_SYSTEM_FONT_SCALE) return@PreHook
                val metrics = call.args[2] as? DisplayMetrics ?: return@PreHook
                val size = call.args[1] as Float
                // Explicit percentages retain Discord's legacy linear scaling,
                // even if an OEM ignores the context's overridden font scale.
                call.result = size * metrics.density * (percent.coerceIn(80, 150) / 100f)
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
