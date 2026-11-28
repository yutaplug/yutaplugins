package com.github.yutaplug.fixes.deleteserver

import android.content.Context
import android.text.InputType
import android.view.View
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.fragments.InputDialog
import com.aliucord.patcher.InsteadHook
import com.aliucord.utils.IOUtils
import com.discord.utilities.view.extensions.ViewExtensions
import com.discord.widgets.servers.WidgetServerDeleteDialog
import com.discord.widgets.servers.`WidgetServerDeleteDialog$configureUI$2`
import com.github.yutaplug.fixes.Fix
import org.json.JSONObject

/**
 * Discord 126.21 deletes a server by sending the 2FA code in the request body (`code`), which Discord
 * no longer accepts: it answers 401 with an MFA ticket (error 60003). This sends the request, verifies
 * the entered code against that ticket through `/mfa/finish`, and retries with the resulting token in
 * `X-Discord-MFA-Authorization`, like the web app does. Accounts without 2FA are asked for their password.
 */
internal class DeleteServerFix :
    Fix(
        "DeleteServerFix",
        "Delete server",
        "Fixes deleting a server failing with \"You are unauthorized to perform this request\".",
    ) {
    override fun start(context: Context) {
        patcher.patch(
            `WidgetServerDeleteDialog$configureUI$2`::class.java,
            "onClick",
            arrayOf(View::class.java),
            InsteadHook { param ->
                val listener = param.thisObject as `WidgetServerDeleteDialog$configureUI$2`
                val dialog = listener.`this$0`
                val guildId = listener.`$this_configureUI`.guild.id
                val binding = WidgetServerDeleteDialog.`access$getBinding$p`(dialog)
                val code = ViewExtensions.getTextOrEmpty(binding.e).trim().replace(" ", "")
                val button = binding.c
                button.isEnabled = false
                Utils.threadPool.execute { delete(dialog, guildId, code, null) }
                null
            },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    /** [password] is set when retrying after the password prompt (accounts without 2FA). */
    private fun delete(dialog: WidgetServerDeleteDialog, guildId: Long, code: String, password: String?) {
        try {
            val body = JSONObject()
            if (code.isNotEmpty()) body.put("code", code)
            var response = post("/guilds/$guildId/delete", body, null)
            if (response.status == 401 && response.json.optInt("code") == MFA_REQUIRED) {
                val mfa = response.json.optJSONObject("mfa")
                val ticket = mfa?.optString("ticket") ?: ""
                if (ticket.isEmpty()) return fail(dialog, response.message())
                val methods = HashSet<String>()
                mfa?.optJSONArray("methods")?.let { array ->
                    for (i in 0 until array.length()) array.optJSONObject(i)?.optString("type")?.let(methods::add)
                }
                val type = when {
                    password != null && "password" in methods -> "password"
                    code.isNotEmpty() && "backup" in methods && (code.contains('-') || code.length == 8) -> "backup"
                    code.isNotEmpty() && "totp" in methods -> "totp"
                    code.isNotEmpty() && "backup" in methods -> "backup"
                    "password" in methods -> return askPassword(dialog, guildId)
                    else -> return fail(dialog, "Enter your authentication code")
                }
                val data = if (type == "password") password!! else code
                val finish = post("/mfa/finish", JSONObject().put("ticket", ticket).put("mfa_type", type).put("data", data), null)
                val token = finish.json.optString("token")
                if (!finish.ok || token.isEmpty()) return fail(dialog, finish.message())
                response = post("/guilds/$guildId/delete", JSONObject(), token)
            }
            if (!response.ok) return fail(dialog, response.message())
            Utils.mainThread.post {
                try {
                    WidgetServerDeleteDialog.`access$afterDeleteGuild`(dialog, guildId)
                } catch (error: Throwable) {
                    logger.error("Could not close the delete dialog", error)
                }
            }
        } catch (error: Throwable) {
            logger.error("Could not delete server $guildId", error)
            fail(dialog, error.message ?: "Could not delete the server")
        }
    }

    private fun askPassword(dialog: WidgetServerDeleteDialog, guildId: Long) {
        Utils.mainThread.post {
            enableButton(dialog)
            if (!dialog.isAdded) return@post
            val input = InputDialog()
                .setTitle("Password Required")
                .setDescription("Enter your password to delete this server.")
                .setPlaceholderText("Password")
                .setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            input.setOnOkListener {
                // Never trim passwords.
                val password = input.input
                if (password.isEmpty()) return@setOnOkListener
                input.dismiss()
                WidgetServerDeleteDialog.`access$getBinding$p`(dialog).c.isEnabled = false
                Utils.threadPool.execute { delete(dialog, guildId, "", password) }
            }
            input.show(dialog.parentFragmentManager, "DeleteServerPassword")
        }
    }

    private fun fail(dialog: WidgetServerDeleteDialog, message: String) {
        Utils.mainThread.post {
            enableButton(dialog)
            Utils.showToast(message, true)
        }
    }

    private fun enableButton(dialog: WidgetServerDeleteDialog) {
        try {
            if (dialog.view != null) WidgetServerDeleteDialog.`access$getBinding$p`(dialog).c.isEnabled = true
        } catch (_: Throwable) {
        }
    }

    private class Result(val status: Int, val json: JSONObject) {
        val ok get() = status in 200..299

        fun message(): String {
            val message = json.optString("message")
            return if (message.isEmpty()) "Could not delete the server ($status)" else message
        }
    }

    private fun post(route: String, body: JSONObject, mfaToken: String?): Result {
        val request = Http.Request.newDiscordRequest(route, "POST")
        try {
            request.setRequestTimeout(15_000)
            request.setHeader("Content-Type", "application/json")
            if (mfaToken != null) {
                request.setHeader("X-Discord-MFA-Authorization", mfaToken)
                request.setHeader("Cookie", "__Secure-recent_mfa=$mfaToken")
            }
            request.executeWithBody(body.toString())
            val status = request.conn.responseCode
            val stream = if (status in 200..299) request.conn.inputStream else request.conn.errorStream
            val text = stream?.let { IOUtils.readAsText(it) } ?: ""
            val json = try {
                if (text.trim().isEmpty()) JSONObject() else JSONObject(text)
            } catch (_: Exception) {
                JSONObject()
            }
            return Result(status, json)
        } finally {
            request.close()
        }
    }

    private companion object {
        const val MFA_REQUIRED = 60003
    }
}
