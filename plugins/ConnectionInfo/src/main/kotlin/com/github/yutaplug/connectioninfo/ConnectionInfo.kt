package com.github.yutaplug.connectioninfo

import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.stores.StoreStream
import com.discord.utilities.platform.Platform
import com.discord.widgets.user.profile.UserProfileConnectionsView
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.TypeAdapter
import com.google.gson.internal.bind.ReflectiveTypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class ConnectionInfo : Plugin() {
    private val adapters = Collections.synchronizedSet(mutableSetOf<Any>())
    private val details = Collections.synchronizedMap(WeakHashMap<ConnectedAccount, List<String>>())
    private val rows = WeakHashMap<TextView, OriginalRow>()
    private val reading = ThreadLocal<Boolean>()
    private val boundFields = ReflectiveTypeAdapterFactory.Adapter::class.java.declaredFields
        .single { Map::class.java.isAssignableFrom(it.type) }
        .apply { isAccessible = true }

    @Volatile private var running = false

    @Volatile private var generation = 0

    private val dialogs = Collections.newSetFromMap(WeakHashMap<ConnectionActionsDialog, Boolean>())

    override fun start(context: Context) {
        generation++
        running = true
        patcher.patch(
            ReflectiveTypeAdapterFactory::class.java,
            "create",
            arrayOf(Gson::class.java, TypeToken::class.java),
            Hook { frame ->
                if ((frame.args[1] as TypeToken<*>).rawType == ConnectedAccount::class.java) {
                    frame.result?.let { adapters.add(it) }
                }
            },
        )
        adapters.add(GsonUtils.gsonRestApi.i(ConnectedAccount::class.java))
        adapters.add(GsonUtils.gson.i(ConnectedAccount::class.java))
        patcher.patch(
            ReflectiveTypeAdapterFactory.Adapter::class.java,
            "read",
            arrayOf(JsonReader::class.java),
            PreHook { frame ->
                if (!running || reading.get() == true) return@PreHook
                val requestGeneration = generation
                if (!adapters.contains(frame.thisObject)) {
                    // Retrofit may have cached its adapter before this plugin was enabled.
                    val fields = boundFields.get(frame.thisObject) as Map<*, *>
                    if (!fields.keys.containsAll(CONNECTION_FIELDS)) return@PreHook
                    adapters.add(frame.thisObject)
                }
                // Consume the original reader once, then let Discord deserialize the same tree normally.
                val tree = GsonUtils.gson.d<JsonElement>(frame.args[0] as JsonReader, JsonElement::class.java)
                reading.set(true)
                try {
                    val account = (frame.thisObject as TypeAdapter<*>).fromJsonTree(tree) as? ConnectedAccount
                    if (account != null && running && generation == requestGeneration) {
                        try {
                            details[account] = ConnectionMetadata.lines(JSONObject(tree.toString()))
                        } catch (error: Exception) {
                            logger.error("Could not parse connection metadata", error)
                        }
                    }
                    frame.result = account
                } finally {
                    reading.remove()
                }
            },
        )
        patcher.patch(
            UserProfileConnectionsView.ViewHolder::class.java,
            "onConfigure",
            arrayOf(Int::class.javaPrimitiveType!!, UserProfileConnectionsView.ConnectedAccountItem::class.java),
            afterConnectionBinding { frame ->
                if (!running) return@afterConnectionBinding
                val holder = frame.thisObject as UserProfileConnectionsView.ViewHolder
                val view = holder.itemView as TextView
                rows.remove(view)
                val item = frame.args[1] as UserProfileConnectionsView.ConnectedAccountItem
                val account = item.connectedAccount
                val lines = details[account].orEmpty()
                val icon = connectionIcon(account)
                if (icon != null) {
                    view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                    view.setOnClickListener {
                        if (!running) return@setOnClickListener
                        val dialog = ConnectionActionsDialog(account, icon)
                        dialogs.add(dialog)
                        dialog.show(fragmentManagerFor(view.context), "ConnectionInfoActions")
                    }
                }
                if (lines.isEmpty()) {
                    if (icon !=
                        null
                    ) {
                        rows[view] = OriginalRow(WeakReference(holder), frame.args[0] as Int, item, view.text)
                    }
                    return@afterConnectionBinding
                }
                val text = SpannableStringBuilder(view.text)
                val start = text.length
                text.append("\n").append(lines.joinToString("\n"))
                text.setSpan(
                    TextAppearanceSpan(view.context, Utils.getResId("UiKit_ListItem_Description", "style")),
                    start,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                view.text = text
                rows[view] = OriginalRow(WeakReference(holder), frame.args[0] as Int, item, view.text)
                view.contentDescription = "${account.g()}, $text"
            },
        )
    }

    /** The screen showing [context]: Aliucord's appActivity can be a screen that was already closed. */
    private fun fragmentManagerFor(context: Context): FragmentManager {
        var current: Context? = context
        while (current is ContextWrapper && current !is FragmentActivity) current = current.baseContext
        return (current as? FragmentActivity)?.supportFragmentManager ?: Utils.appActivity.supportFragmentManager
    }

    private fun connectionIcon(account: ConnectedAccount): Drawable? {
        if (Platform.from(account) != Platform.NONE) return null
        val res = resources ?: return null
        val name = account.g().replace("-", "")
        val suffix = if (StoreStream.getUserSettingsSystem().theme == "light") "_light" else ""
        val id = res.getIdentifier(name + suffix, "drawable", "com.github.yutaplug.connectioninfo")
        if (id == 0) return null
        return ResourcesCompat.getDrawable(res, id, null)
    }

    // Enter before plugins that skip the original binding (such as UnknownConnectionIcons),
    // so Xposed still calls our after hook. After hooks run in reverse priority order.
    private fun afterConnectionBinding(callback: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
        object : XC_MethodHook(10_000) {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                try {
                    callback(param)
                } catch (error: Exception) {
                    logger.error("Could not display connection metadata", error)
                }
            }
        }

    override fun stop(context: Context) {
        running = false
        generation++
        patcher.unpatchAll()
        dialogs.toList().forEach { it.dismissAllowingStateLoss() }
        dialogs.clear()
        val originals = rows.toMap()
        rows.clear()
        Utils.mainThread.post {
            for ((view, original) in originals) {
                if (view.text !== original.rendered) continue
                original.holder.get()?.onConfigure(original.position, original.item)
            }
        }
        adapters.clear()
        details.clear()
    }

    private data class OriginalRow(
        val holder: WeakReference<UserProfileConnectionsView.ViewHolder>,
        val position: Int,
        val item: UserProfileConnectionsView.ConnectedAccountItem,
        val rendered: CharSequence,
    )

    companion object {
        private val CONNECTION_FIELDS =
            setOf("id", "name", "type", "verified", "friend_sync", "show_activity", "integrations")
    }
}
