package com.github.yutaplug.recentprofilepictures

import android.content.Context
import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import b.a.y.b0
import b.a.y.c0
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.RxUtils
import com.aliucord.utils.RxUtils.subscribe
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.images.MGImages
import com.discord.widgets.settings.profile.SettingsUserProfileViewModel
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile
import com.facebook.drawee.view.SimpleDraweeView
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
@Suppress("UNCHECKED_CAST")
class RecentProfilePictures : Plugin() {
    private val editorClass = WidgetEditUserOrGuildMemberProfile::class.java
    private val openSheets = Collections.newSetFromMap(WeakHashMap<b0, Boolean>())
    private val openDialogs = Collections.newSetFromMap(WeakHashMap<ConfirmDialog, Boolean>())
    private val sectionId = View.generateViewId()

    @Volatile private var running = false

    @Volatile private var cachedAvatars: List<RecentAvatar>? = null

    override fun start(context: Context) {
        running = true
        val menuClass = Class.forName(editorClass.name + "\$configureAvatarSelect\$2")
        val selectedClass = Class.forName(editorClass.name + "\$configureAvatarSelect\$2\$1")
        val editorField = menuClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val ownerField = selectedClass.getDeclaredField("this\$0").apply { isAccessible = true }

        patcher.patch(
            b0::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java),
            Hook { frame ->
                val sheet = frame.thisObject as b0
                val callback = sheet.l ?: return@Hook
                if (!selectedClass.isInstance(callback)) return@Hook
                try {
                    val editor = editorField.get(ownerField.get(callback)) as WidgetEditUserOrGuildMemberProfile
                    val options = sheet.argumentsOrDefault.getSerializable("simple_bottom_sheet_options") as? List<c0>
                    val title = options?.firstOrNull()?.e()
                    val change = editor.getString(Utils.getResId("user_settings_change_avatar", "string"))
                    val upload = editor.getString(Utils.getResId("user_settings_upload_avatar", "string"))
                    if (title != change && title != upload) return@Hook
                    val state = readViewState(editor) ?: return@Hook
                    openSheets.add(sheet)
                    injectSection(sheet, frame.args[0] as View, editor, state)
                } catch (error: ReflectiveOperationException) {
                    logger.error("Could not read the avatar editor state", error)
                }
            },
        )
    }

    private fun readViewState(editor: WidgetEditUserOrGuildMemberProfile): SettingsUserProfileViewModel.ViewState.Loaded? {
        val avatarCallback = editorClass
            .getDeclaredField("avatarSelectedResult")
            .apply { isAccessible = true }
            .get(editor)
        return avatarCallback.javaClass
            .getDeclaredField("\$viewState")
            .apply { isAccessible = true }
            .get(avatarCallback) as? SettingsUserProfileViewModel.ViewState.Loaded
    }

    private fun injectSection(
        sheet: b0,
        root: View,
        editor: WidgetEditUserOrGuildMemberProfile,
        state: SettingsUserProfileViewModel.ViewState.Loaded,
    ) {
        // The sheet's header is hidden when the title is empty, and the option list is constrained below it,
        // so the header is reused as the container for the recent avatars section.
        val header = root.findViewById<ConstraintLayout>(
            Utils.getResId("widget_simple_bottom_sheet_selector_header", "id"),
        ) ?: return
        val ctx = root.context
        val section = LinearLayout(ctx).apply {
            id = sectionId
            orientation = LinearLayout.VERTICAL
            setPadding(0, DimenUtils.dpToPx(16), 0, DimenUtils.dpToPx(8))
        }
        // Reuse Discord's own selector row so the title matches the "Change Avatar" option exactly.
        val titleRow = LayoutInflater.from(ctx)
            .inflate(Utils.getResId("bottom_sheet_simple_selector_item", "layout"), section, false)
            .apply {
                background = null
                isClickable = false
                setPadding(0, 0, 0, 0)
                findViewById<View>(Utils.getResId("select_component_sheet_item_icon", "id"))?.visibility = View.GONE
                findViewById<View>(Utils.getResId("select_component_sheet_item_description", "id"))?.visibility =
                    View.GONE
                findViewById<TextView>(Utils.getResId("select_component_sheet_item_title", "id"))?.text = RECENT_AVATARS
            }
        section.addView(titleRow)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(DimenUtils.dpToPx(16), DimenUtils.dpToPx(12), DimenUtils.dpToPx(4), DimenUtils.dpToPx(4))
        }
        section.addView(
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            },
        )

        fun bind(avatars: List<RecentAvatar>) {
            row.removeAllViews()
            if (avatars.isEmpty()) {
                header.visibility = View.GONE
                return
            }
            val size = DimenUtils.dpToPx(AVATAR_SIZE_DP)
            avatars.forEachIndexed { index, avatar ->
                row.addView(
                    SimpleDraweeView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = DimenUtils.dpToPx(12)
                        }
                        contentDescription = "Recent avatar ${index + 1}"
                        outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                        clipToOutline = true
                        MGImages.setImage(this, avatar.cdnUrl(state.user.id))
                        setOnClickListener {
                            if (!running || !editor.isAdded) return@setOnClickListener
                            sheet.dismissAllowingStateLoss()
                            confirmRecentAvatar(editor, avatar.id)
                        }
                    },
                )
            }
            header.visibility = View.VISIBLE
        }

        header.apply {
            removeAllViews()
            background = null
            layoutParams = layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            addView(section, ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        cachedAvatars?.let { bind(it) }

        Utils.threadPool.execute {
            try {
                val avatars = Http.Request.newDiscordRNRequest("/users/@me/avatars").use { request ->
                    val response = request.execute()
                    check(response.ok()) { "HTTP ${response.statusCode}: ${errorBody(request)}" }
                    parseAvatars(GsonUtils.fromJson(response.text(), Map::class.java))
                }
                cachedAvatars = avatars
                root.post { if (running && sheet.isAdded) bind(avatars) }
            } catch (error: Throwable) {
                logger.error("Failed to load synced recent avatars", error)
            }
        }
    }

    // Aliucord's Response.text() throws on non-2xx responses, so read Discord's error body directly.
    private fun errorBody(request: Http.Request): String = try {
        request.conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "<no body>"
    } catch (error: Throwable) {
        "<could not read body: $error>"
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

    private fun selectRecentAvatar(
        editor: WidgetEditUserOrGuildMemberProfile,
        avatarId: String,
        captcha: CaptchaSolution? = null,
    ) {
        val editorView = editor.view ?: return
        Utils.threadPool.execute {
            try {
                var challenge: Map<*, *>? = null
                Http.Request.newDiscordRNRequest("/users/@me", "PATCH").use { request ->
                    request.setHeader("content-type", "application/json")
                    captcha?.let {
                        request.setHeader("X-Captcha-Key", it.key)
                        it.rqtoken?.let { token -> request.setHeader("X-Captcha-Rqtoken", token) }
                        it.sessionId?.let { id -> request.setHeader("X-Captcha-Session-Id", id) }
                    }
                    val response = request.executeWithBody(GsonUtils.toJson(mapOf("avatar_id" to avatarId)))
                    if (!response.ok()) {
                        val body = errorBody(request)
                        val parsed = try {
                            GsonUtils.fromJson(body, Map::class.java)
                        } catch (ignored: Throwable) {
                            null
                        }
                        // Only prompt once: a second captcha means the solved token was rejected.
                        if (captcha == null && parsed?.get("captcha_sitekey") != null) {
                            challenge = parsed
                        } else {
                            error("HTTP ${response.statusCode}: $body")
                        }
                    }
                }
                val pending = challenge
                if (pending != null) {
                    editorView.post { if (running && editor.isAdded) solveCaptcha(editor, avatarId, pending) }
                    return@execute
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

    // Discord wants a captcha for this change: show Discord's own hCaptcha flow, then retry with the token.
    private fun solveCaptcha(editor: WidgetEditUserOrGuildMemberProfile, avatarId: String, challenge: Map<*, *>) {
        val siteKey = challenge["captcha_sitekey"].toString()
        val rqdata = challenge["captcha_rqdata"]?.toString()
        val rqtoken = challenge["captcha_rqtoken"]?.toString()
        val sessionId = challenge["captcha_session_id"]?.toString()
        try {
            CaptchaHelper.INSTANCE.captchaRqtoken = rqtoken
            val request = CaptchaHelper.CaptchaRequest.HCaptcha(siteKey, editor.requireActivity(), rqdata)
            CaptchaHelper.INSTANCE.tryShowCaptcha(request).subscribe(
                RxUtils.createActionSubscriber(
                    { token: String ->
                        if (running && editor.isAdded) {
                            selectRecentAvatar(editor, avatarId, CaptchaSolution(token, rqtoken, sessionId))
                        }
                    },
                    { error: Throwable ->
                        logger.error("Captcha failed", error)
                        if (running) Utils.showToast("Captcha failed, profile picture not changed")
                    },
                ),
            )
        } catch (error: Throwable) {
            logger.error("Could not show captcha", error)
            Utils.showToast("Could not show captcha")
        }
    }

    private class CaptchaSolution(val key: String, val rqtoken: String?, val sessionId: String?)

    private data class RecentAvatar(val id: String, val hash: String) {
        fun cdnUrl(userId: Long): String {
            val extension = if (hash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$userId/archived/$id/$hash.$extension"
        }
    }

    override fun stop(context: Context) {
        running = false
        openSheets.toList().forEach { if (it.isAdded) it.dismissAllowingStateLoss() }
        openSheets.clear()
        openDialogs.toList().forEach { if (it.isAdded) it.dismissAllowingStateLoss() }
        openDialogs.clear()
        cachedAvatars = null
        patcher.unpatchAll()
    }

    companion object {
        private const val MAX_RECENT_AVATARS = 6
        private const val AVATAR_SIZE_DP = 64
        private const val RECENT_AVATARS = "Recent Avatars"
    }
}
