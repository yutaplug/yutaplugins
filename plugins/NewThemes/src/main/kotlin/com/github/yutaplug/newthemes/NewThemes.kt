package com.github.yutaplug.newthemes

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.models.domain.ModelPayload
import com.discord.app.AppActivity
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserSettingsSystem
import com.discord.views.CheckedSetting
import com.discord.widgets.settings.WidgetSettingsAppearance
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

@AliucordPlugin
class NewThemes : Plugin() {
    private class Page(
        val radios: List<CheckedSetting>,
        val picker: NitroThemePicker,
        var model: WidgetSettingsAppearance.Model? = null,
    )
    private val pages = WeakHashMap<WidgetSettingsAppearance, Page>()
    private var sync: ThemeSync? = null
    private var palette: ThemePalette? = null
    private val activityThemes = WeakHashMap<AppActivity, Appearance>()
    private val comparingTheme = ThreadLocal<String>()
    private var running = false
    @Volatile private var selected = Appearance(ThemeChoice.ASH)

    override fun start(context: Context) {
        val store = StoreStream.getUserSettingsSystem()
        selected = Appearance(ThemeChoice.fromNative(store.theme))
        running = true
        val bridge = ThemeSync(context.applicationContext, ::applyTheme) { message, error -> logger.warn(message, error) }
        sync = bridge
        try {
            palette = ThemePalette { selected }.also { it.start(context, patcher) }
            val themeName = Utils.getResId("theme_name", "attr")
            check(themeName != 0) { "Discord theme name attribute unavailable" }
            // Own palette refreshes instead of relying on synthetic native theme names.
            patcher.patch(AppActivity::class.java.getDeclaredMethod("onCreate", android.os.Bundle::class.java), PreHook {
                activityThemes[it.thisObject as AppActivity] = selected
            })
            patcher.patch(AppActivity::class.java.getDeclaredMethod("onResume"), PreHook {
                refreshActivity(it.thisObject as AppActivity)
            })
            activityThemes[Utils.appActivity] = selected
            patcher.patch(Resources.Theme::class.java.getDeclaredMethod(
                "resolveAttribute", Int::class.javaPrimitiveType, TypedValue::class.java, Boolean::class.javaPrimitiveType,
            ), Hook {
                if (it.result == true && it.args[0] == themeName) {
                    comparingTheme.get()?.let { name ->
                        (it.args[1] as TypedValue).string = name
                    }
                }
            })
            val callback = WidgetSettingsAppearance::class.java.classLoader!!.loadClass(
                "com.discord.widgets.settings.WidgetSettingsAppearance\$onViewBoundOrOnResume\$1",
            )
            // Native configureUI replaces listeners after every store emission.
            // Hook the actual target, including callers that ART has inlined.
            callback.declaredMethods.filter { it.name == "invoke" }.forEach(XposedBridge::deoptimizeMethod)
            XposedBridge.deoptimizeMethod(WidgetSettingsAppearance::class.java.getDeclaredMethod(
                "access\$configureUI", WidgetSettingsAppearance::class.java, WidgetSettingsAppearance.Model::class.java,
            ))
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod(
                "configureUI", WidgetSettingsAppearance.Model::class.java,
            ), Hook {
                bind(it.thisObject as WidgetSettingsAppearance, it.args[0] as WidgetSettingsAppearance.Model)
            })
            val activitySettingsCallback = AppActivity::class.java.classLoader!!.loadClass("com.discord.app.AppActivity\$c")
            activitySettingsCallback.declaredMethods.filter { it.name == "invoke" }.forEach(XposedBridge::deoptimizeMethod)
            val compareSettings = activitySettingsCallback.getDeclaredMethod("invoke", Any::class.java)
            patcher.patch(compareSettings, PreHook {
                comparingTheme.set((it.args[0] as StoreUserSettingsSystem.Settings).theme)
            })
            patcher.patch(compareSettings, Hook { comparingTheme.remove() })
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod("onViewBoundOrOnResume"), Hook {
                bind(it.thisObject as WidgetSettingsAppearance, null)
                bridge.refresh()
            })
            patcher.patch(WidgetSettingsAppearance::class.java.getDeclaredMethod("onDestroyView"), PreHook {
                pages.remove(it.thisObject)?.radios?.forEach { radio -> radio.setOnCheckedListener(null) }
            })
            // Also catch Discord's original click path if ART inlines the UI callback.
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod(
                "setTheme", String::class.java, Boolean::class.javaPrimitiveType, Function0::class.java,
            ), PreHook {
                if (it.args[1] == true) {
                    choose(Appearance(ThemeChoice.fromNative(it.args[0] as String)))
                    it.result = null
                }
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("setIsSyncThemeEnabled", Boolean::class.javaPrimitiveType), Hook {
                bridge.syncChanged()
            })
            // The legacy payload collapses Dark and Onyx to Ash. Never let that
            // representation overwrite the four-value protobuf selection.
            patcher.patch(ModelUserSettings::class.java.getDeclaredMethod("getTheme"), Hook {
                if (it.result != null) it.result = selected.theme.native
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("handleConnectionOpen", ModelPayload::class.java), Hook {
                bridge.refresh()
            })
            patcher.patch(StoreUserSettingsSystem::class.java.getDeclaredMethod("handleUserSettingsUpdate", ModelUserSettings::class.java), Hook {
                bridge.refresh()
            })
            bridge.start()
            applyTheme(selected)
        } catch (error: Throwable) {
            bridge.close()
            sync = null
            patcher.unpatchAll()
            palette?.clearCaches()
            palette = null
            running = false
            activityThemes.clear()
            comparingTheme.remove()
            throw error
        }
    }

    private fun refreshActivity(activity: AppActivity) {
        if (!running || activity.isFinishing || activity.isDestroyed) return
        val rendered = activityThemes[activity] ?: selected.also { activityThemes[activity] = it }
        if (rendered == selected || activity.intent?.hasExtra("AC_FRAGMENT_ID") == true) return
        // Mark before posting to coalesce store emissions and onResume callbacks.
        activityThemes[activity] = selected
        activity.window.decorView.post {
            if (running && !activity.isFinishing && !activity.isDestroyed) activity.recreate()
        }
    }

    private fun choose(appearance: Appearance) {
        applyTheme(appearance)
        sync?.choose(appearance)
    }

    private fun applyTheme(appearance: Appearance) {
        if (selected != appearance) palette?.clearCaches()
        selected = appearance
        val store = StoreStream.getUserSettingsSystem()
        if (store.theme != appearance.theme.native) store.setTheme(appearance.theme.native, false, null)
        pages.values.forEach { updatePage(it) }
        refreshActivity(Utils.appActivity)
    }

    private fun updatePage(page: Page) {
        val appearance = selected
        page.radios.forEachIndexed { index, radio ->
            radio.g(appearance.preset == null && ThemeChoice.values()[index] == appearance.theme, false)
        }
        page.picker.update(appearance.preset)
        // Settings pages are not recreated; redraw their gradient surfaces.
        (page.radios[0].rootView as? ViewGroup)?.let { invalidateTree(it) }
    }

    private fun invalidateTree(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (index in 0 until view.childCount) invalidateTree(view.getChildAt(index))
    }

    private fun bind(widget: WidgetSettingsAppearance, model: WidgetSettingsAppearance.Model?) {
        if (widget.view == null) return
        val binding = WidgetSettingsAppearance.`access$getBinding$p`(widget)
        val page = pages[widget] ?: run {
            val parent = binding.l.parent as ViewGroup
            val layout = Utils.getResId("widget_settings_appearance", "layout")
            val radioId = Utils.getResId("settings_appearance_theme_light_radio", "id")
            val headerId = Utils.getResId("settings_appearance_sync_header", "id")
            val subTextId = Utils.getResId("settings_appearance_font_scale_platform", "id")
            check(layout != 0 && radioId != 0 && headerId != 0 && subTextId != 0) { "Discord appearance resources unavailable" }
            // Inflate the exact target layout to retain Discord's styles, spacing
            // and text appearance on every added view.
            fun <T : View> clone(id: Int): T {
                val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
                val view = root.findViewById<T>(id)
                (view.parent as ViewGroup).removeView(view)
                view.id = View.generateViewId()
                return view
            }
            val ash = clone<CheckedSetting>(radioId)
            val onyx = clone<CheckedSetting>(radioId)
            parent.addView(ash, parent.indexOfChild(binding.j))
            parent.addView(onyx, parent.indexOfChild(binding.j) + 1)
            val divider = run {
                // The settings divider that precedes the sync header.
                val root = LayoutInflater.from(parent.context).inflate(layout, parent, false)
                val header = root.findViewById<View>(headerId)
                val container = header.parent as ViewGroup
                container.getChildAt(container.indexOfChild(header) - 1).also { container.removeView(it) }
            }
            val picker = NitroThemePicker(parent.context, clone<TextView>(headerId), clone<TextView>(subTextId), divider) {
                choose(Appearance.of(it))
            }
            var index = parent.indexOfChild(binding.m) + 1
            picker.views.forEach { parent.addView(it, index++) }
            Page(listOf(binding.l, ash, binding.j, onyx), picker).also { pages[widget] = it }
        }
        if (model != null) page.model = model
        binding.m.visibility = View.GONE
        page.radios.forEachIndexed { index, radio ->
            val theme = ThemeChoice.values()[index]
            radio.setText(theme.label)
            // CheckedSetting.e requires a non-null listener. Replace Discord's
            // easter-egg handler with selection of this row's actual theme.
            radio.e { choose(Appearance(theme)) }
            radio.setOnCheckedListener { checked -> if (checked) choose(Appearance(theme)) }
        }
        updatePage(page)
    }

    override fun stop(context: Context) {
        running = false
        sync?.close()
        sync = null
        patcher.unpatchAll()
        palette?.clearCaches()
        palette = null
        activityThemes.clear()
        comparingTheme.remove()
        pages.forEach { (widget, page) ->
            page.radios.forEach { it.setOnCheckedListener(null) }
            (listOf(page.radios[1], page.radios[3]) + page.picker.views).forEach { (it.parent as? ViewGroup)?.removeView(it) }
            if (widget.view != null) {
                page.radios[2].setText("Dark")
                page.model?.let { WidgetSettingsAppearance.`access$configureUI`(widget, it) }
            }
        }
        pages.clear()
        // Leave a native theme the unmodified client understands.
        val theme = selected.theme
        val native = if (theme == ThemeChoice.LIGHT) "light" else if (theme == ThemeChoice.ONYX) "pureEvil" else "dark"
        StoreStream.getUserSettingsSystem().setTheme(native, false, null)
        Utils.appActivity?.let { activity ->
            activity.window.decorView.post {
                if (!activity.isFinishing) activity.recreate()
            }
        }
    }
}
