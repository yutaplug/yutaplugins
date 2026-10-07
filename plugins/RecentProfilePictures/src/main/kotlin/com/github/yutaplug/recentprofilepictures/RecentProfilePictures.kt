package com.github.yutaplug.recentprofilepictures

import android.content.Context
import b.a.y.b0
import b.a.y.c0
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.GsonUtils
import com.discord.widgets.settings.profile.SettingsUserProfileViewModel
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
@Suppress("UNCHECKED_CAST")
class RecentProfilePictures : Plugin() {
    private val editorClass = WidgetEditUserOrGuildMemberProfile::class.java
    private val originalMenus = Collections.synchronizedMap(WeakHashMap<Any, List<c0>>())
    private val openSheets = Collections.newSetFromMap(WeakHashMap<b0, Boolean>())
    private val openDialogs = Collections.newSetFromMap(WeakHashMap<ConfirmDialog, Boolean>())

    @Volatile private var running = false

    override fun start(context: Context) {
        running = true
        val menuClass = Class.forName(editorClass.name + "\$configureAvatarSelect\$2")
        val selectedClass = Class.forName(editorClass.name + "\$configureAvatarSelect\$2\$1")
        val optionsField = menuClass.getDeclaredField("\$avatarSheetOptions").apply { isAccessible = true }
        val editorField = menuClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val ownerField = selectedClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val selectorMethod = b0.k.javaClass.declaredMethods.single {
            it.name == "a" && it.parameterTypes.size == 5
        }
        patcher.patch(
            selectorMethod,
            Hook { frame ->
                if (selectedClass.isInstance(frame.args[4])) {
                    (frame.result as? b0)?.let { openSheets.add(it) }
                }
            },
        )
        // Hook the native callback itself: the selector helper can be inlined by ART.
        val openMenu = menuClass.declaredMethods.single {
            it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == Any::class.java
        }
        patcher.patch(
            openMenu,
            PreHook { frame ->
                val editor = editorField.get(frame.thisObject) as WidgetEditUserOrGuildMemberProfile
                val options = optionsField.get(frame.thisObject) as List<c0>
                val title = options.firstOrNull()?.e()
                val change = editor.getString(Utils.getResId("user_settings_change_avatar", "string"))
                val upload = editor.getString(Utils.getResId("user_settings_upload_avatar", "string"))
                if (title != change && title != upload) return@PreHook
                if (options.any { it.e() == RECENT_AVATARS }) return@PreHook
                originalMenus[frame.thisObject] = options
                optionsField.set(
                    frame.thisObject,
                    options.toMutableList().apply {
                        add(1, c0(RECENT_AVATARS, null, null, null, null, null, null, 116))
                    },
                )
            },
        )
        patcher.patch(
            selectedClass.getDeclaredMethod("invoke", Any::class.java),
            PreHook { frame ->
                val owner = ownerField.get(frame.thisObject)
                val options = optionsField.get(owner) as List<c0>
                val selected = frame.args[0] as Int
                val recentIndex = options.indexOfFirst { it.e() == RECENT_AVATARS }
                if (recentIndex < 0) return@PreHook
                if (selected != recentIndex) {
                    if (selected > recentIndex) frame.args[0] = selected - 1
                    return@PreHook
                }
                val editor = editorField.get(owner) as WidgetEditUserOrGuildMemberProfile
                try {
                    val avatarCallback = editorClass
                        .getDeclaredField("avatarSelectedResult")
                        .apply { isAccessible = true }
                        .get(editor)
                    val state = avatarCallback.javaClass
                        .getDeclaredField("\$viewState")
                        .apply { isAccessible = true }
                        .get(avatarCallback) as SettingsUserProfileViewModel.ViewState.Loaded
                    loadRecentAvatars(editor, state)
                } catch (error: ReflectiveOperationException) {
                    logger.error("Could not read the avatar editor state", error)
                    Utils.showToast("Could not open recent profile pictures")
                }
                frame.result = null
            },
        )
    }

    private fun loadRecentAvatars(
        editor: WidgetEditUserOrGuildMemberProfile,
        state: SettingsUserProfileViewModel.ViewState.Loaded,
    ) {
        val editorView = editor.view ?: return
        Utils.threadPool.execute {
            try {
                val avatars = Http.Request.newDiscordRNRequest("/users/@me/avatars").use { request ->
                    val response = request.execute()
                    check(response.ok()) { "HTTP ${response.statusCode}" }
                    parseAvatars(GsonUtils.fromJson(response.text(), Map::class.java))
                }
                editorView.postDelayed({
                    if (running && editor.isAdded && editor.view === editorView) {
                        showRecentAvatarMenu(editor, state, avatars)
                    }
                }, 300L)
            } catch (error: Throwable) {
                logger.error("Failed to load synced recent avatars", error)
                editorView.post { if (running) Utils.showToast("Could not load recent profile pictures") }
            }
        }
    }

    private fun parseAvatars(body: Map<*, *>?): List<RecentAvatar> {
        val result = mutableListOf<RecentAvatar>()
        val rawAvatars = body?.get("avatars") as? List<*> ?: return result
        for (raw in rawAvatars) {
            val avatar = raw as? Map<*, *> ?: continue
            val id = avatar["id"] ?: continue
            val hash = avatar["storage_hash"] ?: continue
            if (result.size == MAX_RECENT_AVATARS) break
            result.add(RecentAvatar(id.toString(), hash.toString()))
        }
        return result
    }

    private fun showRecentAvatarMenu(
        editor: WidgetEditUserOrGuildMemberProfile,
        state: SettingsUserProfileViewModel.ViewState.Loaded,
        avatars: List<RecentAvatar>,
    ) {
        if (avatars.isEmpty()) {
            Utils.showToast("No recent profile pictures found")
            return
        }
        val options = avatars.mapIndexed { index, avatar ->
            c0(
                "Recent avatar ${index + 1}",
                "Select this recent avatar",
                null,
                avatar.cdnUrl(state.user.id),
                null,
                null,
                null,
                116,
            )
        }
        // Resolve the callback from Discord's method, avoiding the plugin's bundled Kotlin interface.
        val openMenu = b0.k.javaClass.declaredMethods.single {
            it.name == "a" && it.parameterTypes.size == 5
        }
        val callbackClass = openMenu.parameterTypes[4]
        val onSelected = Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
        ) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val selected = (args?.firstOrNull() as? Number)?.toInt()
                    if (running && editor.isAdded) {
                        selected?.let { avatars.getOrNull(it) }?.let { confirmRecentAvatar(editor, it.id) }
                    }
                    Unit.a
                }

                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "Recent avatar selection callback"
                }

                else -> {
                    null
                }
            }
        }
        val sheet = openMenu.invoke(
            b0.k,
            editor.childFragmentManager,
            "Recent avatars",
            options,
            false,
            onSelected,
        ) as b0
        openSheets.add(sheet)
    }

    private fun confirmRecentAvatar(editor: WidgetEditUserOrGuildMemberProfile, avatarId: String) {
        val dialog = ConfirmDialog()
        dialog
            .setTitle("Change profile picture?")
            .setDescription("Your profile picture will be changed to the selected recent avatar.")
            .setOnOkListener {
                dialog.dismiss()
                selectRecentAvatar(editor, avatarId)
            }.show(Utils.appActivity.supportFragmentManager, "RecentProfilePicturesConfirm")
        openDialogs.add(dialog)
    }

    private fun selectRecentAvatar(editor: WidgetEditUserOrGuildMemberProfile, avatarId: String) {
        val editorView = editor.view ?: return
        Utils.threadPool.execute {
            try {
                Http.Request.newDiscordRNRequest("/users/@me", "PATCH").use { request ->
                    request.setHeader("content-type", "application/json")
                    request.executeWithBody(GsonUtils.toJson(mapOf("avatar_id" to avatarId))).also { response ->
                        check(response.ok()) { "HTTP ${response.statusCode}" }
                    }
                }
                editorView.post {
                    if (running) {
                        if (editor.isAdded && editor.view === editorView) editor.requireActivity().onBackPressed()
                        Utils.showToast("Recent profile picture applied")
                    }
                }
            } catch (error: Throwable) {
                logger.error("Failed to select synced recent avatar", error)
                editorView.post { if (running) Utils.showToast("Could not select recent profile picture") }
            }
        }
    }

    private data class RecentAvatar(val id: String, val hash: String) {
        fun cdnUrl(userId: Long): String {
            val extension = if (hash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$userId/archived/$id/$hash.$extension"
        }
    }

    override fun stop(context: Context) {
        running = false
        openSheets.toList().forEach { it.dismissAllowingStateLoss() }
        openSheets.clear()
        openDialogs.toList().forEach { if (it.isAdded) it.dismissAllowingStateLoss() }
        openDialogs.clear()
        synchronized(originalMenus) {
            for ((menu, options) in originalMenus) {
                menu.javaClass.getDeclaredField("\$avatarSheetOptions").apply { isAccessible = true }.set(menu, options)
            }
            originalMenus.clear()
        }
        patcher.unpatchAll()
    }

    companion object {
        private const val MAX_RECENT_AVATARS = 6
        private const val RECENT_AVATARS = "Recent avatars"
    }
}
