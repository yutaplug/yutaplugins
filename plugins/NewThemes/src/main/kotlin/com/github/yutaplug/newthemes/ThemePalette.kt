package com.github.yutaplug.newthemes

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.LongSparseArray
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import com.aliucord.api.PatcherAPI
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.Utils
import de.robv.android.xposed.XposedBridge

/**
 * Changes only Discord's neutral palette, retaining accents and status colors.
 * With a Nitro preset, neutral colors are tinted with the preset, and opaque
 * neutral backgrounds draw their slice of one screen-sized gradient.
 */
internal class ThemePalette(private val choice: () -> Appearance) {
    private val originals = HashMap<Int, Int>()
    private val lightOriginals = HashSet<Int>()
    private lateinit var resources: Resources
    private val ash = intArrayOf(0x4e5058, 0x404249, 0x35373c, 0x303238, 0x2b2d31, 0x232428, 0x1e1f22, 0x111214)
    private val dark = intArrayOf(0x3f3f46, 0x29292e, 0x1a1a1e, 0x17171b, 0x131316, 0x111114, 0x0e0e11, 0x08080a)
    private val onyx = intArrayOf(0x333338, 0x111113, 0x000000, 0x08080b, 0x08080b, 0x08080b, 0x08080b, 0x000000)

    // Like the official clients, dark presets darken the gradient towards black
    // instead of grey, so dark parts of a gradient stay black. Raised elements
    // (buttons, inputs) keep a grey base so they still stand out there.
    // Indexes follow primary_500, 560, 600, 630, 660, 700, 800, 900.
    private val darkTones = arrayOf(
        Tone(0xff3f3f46.toInt(), 0.35f),
        Tone(0xff29292e.toInt(), 0.35f),
        Tone(Color.BLACK, 0.40f),
        Tone(Color.BLACK, 0.37f),
        Tone(0xff0a0a0c.toInt(), 0.35f),
        Tone(Color.BLACK, 0.28f),
        Tone(Color.BLACK, 0.22f),
        Tone(Color.BLACK, 0.15f),
    )

    // Tinted solid -> tone, for colors that were already mapped once.
    private val solids = HashMap<Int, Tone>()
    private var solidsPreset: GradientPreset? = null
    private val pendingSurface = ThreadLocal<Tone?>()

    val preset: GradientPreset? get() = choice().preset

    /** The opaque color a surface of [tone] has at the preset's representative point. */
    fun solid(preset: GradientPreset, tone: Tone): Int = GradientPreset.mix(tone.base, preset.mid, tone.share)

    /** Overlay drawn above the gradient, so gradient + overlay averages to [solid]. */
    fun overlay(tone: Tone): Int = ((((1f - tone.share) * 255f) + 0.5f).toInt() shl 24) or (tone.base and 0xffffff)

    private fun lightTone(color: Int) = Tone(color, LIGHT_TINT)

    @Synchronized
    private fun solidsFor(preset: GradientPreset): HashMap<Int, Tone> {
        if (solidsPreset !== preset) {
            solids.clear()
            val tones = if (preset.light) (lightOriginals + Color.WHITE).map { lightTone(it) } else darkTones.toList()
            tones.forEach { solids.getOrPut(solid(preset, it)) { it } }
            solidsPreset = preset
        }
        return solids
    }

    /** Tone of an opaque background color, when a preset should draw it as a gradient. */
    fun surfaceTone(color: Int): Tone? {
        val preset = preset ?: return null
        if (Color.alpha(color) != 255) return null
        if (preset.light) {
            if (color == Color.WHITE || color in lightOriginals) return lightTone(color)
        } else {
            originals[color]?.let { return darkTones[it] }
        }
        return solidsFor(preset)[color]
    }

    fun mapped(color: Int): Int? {
        val appearance = choice()
        val preset = appearance.preset
        val alpha = color and -0x1000000
        if (preset != null) {
            val opaque = color or Color.BLACK
            if (preset.light) {
                // White is also the light theme's text-on-accent color; it is
                // only tinted where it is used as a background (surfaceTone).
                return if (opaque in lightOriginals) alpha or (solid(preset, lightTone(opaque)) and 0xffffff) else null
            }
            val index = originals[opaque] ?: return null
            return alpha or (solid(preset, darkTones[index]) and 0xffffff)
        }
        if (appearance.theme == ThemeChoice.LIGHT) return null
        val index = originals[color or Color.BLACK] ?: return null
        val palette = when (appearance.theme) {
            ThemeChoice.DARK -> dark
            ThemeChoice.ONYX -> onyx
            else -> ash
        }
        return alpha or palette[index]
    }

    fun start(context: Context, patcher: PatcherAPI) {
        resources = context.resources
        @Suppress("DEPRECATION")
        fun color(name: String): Int? {
            val id = Utils.getResId(name, "color")
            if (id == 0) return null
            return if (Build.VERSION.SDK_INT >= 23) context.resources.getColor(id, null) else context.resources.getColor(id)
        }
        val names = arrayOf("primary_500", "primary_560", "primary_600", "primary_630", "primary_660", "primary_700", "primary_800", "primary_900")
        names.forEachIndexed { index, name -> color(name)?.let { originals[it] = index } }
        // Light theme backgrounds (white is handled separately, see mapped).
        arrayOf(
            "primary_100", "primary_200", "primary_light_100", "primary_light_130", "primary_light_160",
            "primary_light_200", "primary_light_230",
        ).forEach { name -> color(name)?.let { lightOriginals.add(it) } }
        val int = Int::class.javaPrimitiveType!!
        val getColor = if (Build.VERSION.SDK_INT >= 23) {
            Resources::class.java.getDeclaredMethod("getColor", int, Resources.Theme::class.java)
        } else Resources::class.java.getDeclaredMethod("getColor", int)
        patcher.patch(getColor, Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        // XML android:background colors become ColorDrawables directly from a
        // TypedValue; that path does not call getColor or ColorDrawable.setColor.
        // Change the value before the loader selects its color-drawable cache key.
        val loadDrawable = when {
            Build.VERSION.SDK_INT >= 26 -> Class.forName("android.content.res.ResourcesImpl").getDeclaredMethod(
                "loadDrawable", Resources::class.java, TypedValue::class.java, int, int, Resources.Theme::class.java,
            )
            Build.VERSION.SDK_INT >= 24 -> Class.forName("android.content.res.ResourcesImpl").getDeclaredMethod(
                "loadDrawable", Resources::class.java, TypedValue::class.java, int, Resources.Theme::class.java,
                Boolean::class.javaPrimitiveType,
            )
            else -> Resources::class.java.getDeclaredMethod(
                "loadDrawable", TypedValue::class.java, int, Resources.Theme::class.java,
            )
        }
        val valueIndex = if (Build.VERSION.SDK_INT >= 24) 1 else 0
        patcher.patch(loadDrawable, PreHook {
            val value = it.args[valueIndex] as TypedValue
            // Set on every call so nested loads cannot leak a stale surface.
            pendingSurface.set(null)
            if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                pendingSurface.set(surfaceTone(value.data))
                mapped(value.data)?.let { color -> value.data = color }
            }
        })
        patcher.patch(loadDrawable, Hook {
            val tone = pendingSurface.get()
            pendingSurface.set(null)
            if (tone != null && preset != null && it.result?.javaClass == ColorDrawable::class.java) {
                it.result = SurfaceDrawable(this, tone)
            }
        })
        // Code-set backgrounds (ColorCompat themed colors, adapters, etc.).
        patcher.patch(View::class.java.getDeclaredMethod("setBackgroundColor", int), PreHook {
            val tone = surfaceTone(it.args[0] as Int) ?: return@PreHook
            val view = it.thisObject as View
            val current = view.background
            if (current is SurfaceDrawable && current.tone == tone) current.reset() else view.background = SurfaceDrawable(this, tone)
            it.result = null
        })
        // These callers may already have inlined the unpatched drawable loader.
        val typedArrayDrawable = if (Build.VERSION.SDK_INT >= 26) {
            TypedArray::class.java.getDeclaredMethod("getDrawableForDensity", int, int)
        } else {
            // The density overload was introduced in Oreo. Resolving it on
            // Nougat throws and rolls back every plugin hook during start.
            TypedArray::class.java.getDeclaredMethod("getDrawable", int)
        }
        XposedBridge.deoptimizeMethod(typedArrayDrawable)
        XposedBridge.deoptimizeMethod(Resources::class.java.getDeclaredMethod(
            "getDrawableForDensity", int, int, Resources.Theme::class.java,
        ))
        patcher.patch(Resources.Theme::class.java.getDeclaredMethod("resolveAttribute", int, TypedValue::class.java, Boolean::class.javaPrimitiveType), Hook {
            if (it.result == true) {
                val value = it.args[1] as TypedValue
                if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                    mapped(value.data)?.let { color -> value.data = color }
                }
            }
        })
        patcher.patch(TypedArray::class.java.getDeclaredMethod("getColor", int, int), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(TypedArray::class.java.getDeclaredMethod("getColorStateList", int), Hook {
            val list = it.result as ColorStateList? ?: return@Hook
            // Stateful lists are handled by getColorForState without losing states.
            if (!list.isStateful) mapped(list.defaultColor)?.let { color -> it.result = ColorStateList.valueOf(color) }
        })
        patcher.patch(ColorStateList::class.java.getDeclaredMethod("getColorForState", IntArray::class.java, int), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(ColorStateList::class.java.getDeclaredMethod("getDefaultColor"), Hook {
            mapped(it.result as Int)?.let { color -> it.result = color }
        })
        patcher.patch(ColorDrawable::class.java.getDeclaredMethod("setColor", int), PreHook {
            mapped(it.args[0] as Int)?.let { color -> it.args[0] = color }
        })
        patcher.patch(GradientDrawable::class.java.getDeclaredMethod("setColor", int), PreHook {
            mapped(it.args[0] as Int)?.let { color -> it.args[0] = color }
        })
        // Framework callers can inline ColorStateList/ColorDrawable accessors.
        // Refresh those compiled callers as in Aliucord's native theming path.
        mapOf(
            TextView::class.java to "updateTextColors",
            RippleDrawable::class.java to "updateRipplePaint",
            Drawable::class.java to "updateBlendModeFilter",
            GradientDrawable::class.java to "updateLocalState",
            android.view.View::class.java to "setBackgroundColor",
        ).forEach { (type, name) ->
            type.declaredMethods.filter { it.name == name }.forEach { XposedBridge.deoptimizeMethod(it) }
        }
        clearCaches()
    }

    /** Cached drawables otherwise retain the previous palette across recreation. */
    fun clearCaches() {
        solidsPreset = null
        if (!::resources.isInitialized) return
        fun field(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val candidate = type
                val result = runCatching { candidate.getDeclaredField(name).apply { isAccessible = true }.get(owner) }
                if (result.isSuccess) return result.getOrNull()
                type = candidate.superclass
            }
            return null
        }
        fun clear(value: Any?) {
            when (value) {
                is MutableMap<*, *> -> value.clear()
                is LongSparseArray<*> -> value.clear()
            }
        }
        val impl = field(resources, "mResourcesImpl") ?: resources
        arrayOf("mDrawableCache", "mColorDrawableCache", "mColorStateListCache", "mComplexColorCache").forEach { name ->
            val cache = field(impl, name) ?: return@forEach
            synchronized(cache) {
                clear(cache)
                arrayOf("mThemedEntries", "mUnthemedEntries", "mNullThemedEntries").forEach { clear(field(cache, it)) }
            }
        }
        // Both libraries cache XML ColorStateLists outside framework resources.
        arrayOf(AppCompatResources::class.java, ResourcesCompat::class.java).forEach { type ->
            runCatching {
                val lock = type.getDeclaredField("sColorStateCacheLock").apply { isAccessible = true }.get(null)!!
                synchronized(lock) {
                    clear(type.getDeclaredField("sColorStateCaches").apply { isAccessible = true }.get(null))
                }
            }
        }
    }


    private companion object {
        // Share of the preset gradient in light surfaces; the rest is the neutral.
        const val LIGHT_TINT = 0.4f
    }
}

/** A neutral background level: [share] of the preset gradient over [base]. */
internal data class Tone(val base: Int, val share: Float)

/**
 * An opaque neutral background that paints its part of a screen-sized preset
 * gradient, then the neutral as an overlay. Every surface uses the same screen
 * gradient, so nested surfaces line up while keeping their relative shades.
 * It stays a ColorDrawable (with the averaged solid color) for code that reads
 * or changes the color; once the color is changed it draws that color plainly.
 */
internal class SurfaceDrawable(private val palette: ThemePalette, val tone: Tone) : ColorDrawable() {
    private val gradient = Paint()
    private val overlay = Paint()
    private val location = IntArray(2)
    private val probe = IntArray(2)
    private val matrix = Matrix()
    private var shader: Shader? = null
    private var shaderPreset: GradientPreset? = null
    private var shaderWidth = 0
    private var shaderHeight = 0

    // The color this drawable set itself; anything else came from outside.
    private var expected = 0

    // Moving a view (sliding panels, scrolling lists, transitions) reuses its
    // last drawing, which would keep the gradient slice of the old position.
    // Redraw whenever the view is about to be drawn somewhere else on screen.
    private var tracked: View? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val view = tracked
        if (view != null) {
            if (owner() !== view) {
                untrack()
            } else {
                view.getLocationOnScreen(probe)
                if (probe[0] != location[0] || probe[1] != location[1]) invalidateSelf()
            }
        }
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            view.viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        override fun onViewDetachedFromWindow(view: View) {
            view.viewTreeObserver.removeOnPreDrawListener(preDraw)
        }
    }

    init {
        reset()
    }

    fun reset() {
        val preset = palette.preset
        expected = if (preset != null) palette.solid(preset, tone) else tone.base or Color.BLACK
        color = expected
    }

    private fun owner(): View? {
        var callback: Any? = callback
        var depth = 0
        while (callback != null && depth++ < 8) {
            if (callback is View) return callback
            callback = (callback as? Drawable)?.callback
        }
        return null
    }

    private fun track(view: View) {
        if (tracked === view) return
        untrack()
        tracked = view
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) view.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    private fun untrack() {
        val view = tracked ?: return
        tracked = null
        view.removeOnAttachStateChangeListener(attach)
        view.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }

    override fun draw(canvas: Canvas) {
        val preset = palette.preset
        val owner = owner()
        if (preset == null || owner == null || color != expected) {
            super.draw(canvas)
            return
        }
        // Pages that are not recreated (settings) follow a newly chosen preset.
        if (expected != palette.solid(preset, tone)) reset()
        track(owner)
        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        var current = shader
        if (current == null || shaderPreset !== preset || shaderWidth != width || shaderHeight != height) {
            current = preset.shader(0f, 0f, width.toFloat(), height.toFloat())
            shader = current
            shaderPreset = preset
            shaderWidth = width
            shaderHeight = height
        }
        owner.getLocationOnScreen(location)
        matrix.setTranslate(-location[0].toFloat(), -location[1].toFloat())
        current.setLocalMatrix(matrix)
        gradient.shader = current
        canvas.drawRect(bounds, gradient)
        overlay.color = palette.overlay(tone)
        canvas.drawRect(bounds, overlay)
    }

    override fun getConstantState(): ConstantState = State(palette, tone)

    private class State(private val palette: ThemePalette, private val tone: Tone) : ConstantState() {
        override fun newDrawable(): Drawable = SurfaceDrawable(palette, tone)
        override fun getChangingConfigurations(): Int = 0
    }
}