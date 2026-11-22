package com.github.yutaplug.fixes.qrlogin

import android.os.SystemClock
import android.text.InputType
import android.view.View
import androidx.fragment.app.Fragment
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.fragments.ConfirmDialog
import com.aliucord.fragments.InputDialog
import com.aliucord.utils.IOUtils
import com.discord.app.AppFragment
import com.discord.widgets.auth.WidgetRemoteAuthViewModel
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** Ported from secp192k1's QRCodeLogin. */
internal object QrLogin {
    const val MFA_TAG = "qr_mfa"
    private const val TIMEOUT = 15_000
    private const val LOGIN_BUTTON = "remote_auth_login_button"
    private const val EXPIRED_MESSAGE = "Handshake expired\nDid you switch apps?\n\n" +
        "Restart the process, make sure to authorize before switching from Aliucord."
    private const val HANDSHAKE_TTL = 120_000L

    private const val FINISH_SUCCESS = 0
    private const val FINISH_MFA = 1
    private const val FINISH_FAIL = 2

    private val logger = Logger("Fixes/QRCodeLogin")

    @Volatile private var handshakeToken: String? = null

    @Volatile private var mfaTicket: String? = null

    @Volatile private var mfaType: String? = null

    @Volatile private var mfaNumeric = false

    @Volatile private var pendingMfa: JSONObject? = null

    @Volatile private var pendingError: String? = null

    @Volatile private var pendingHandshake: String? = null

    @Volatile private var handshakeTime = 0L

    @Volatile private var recovering = false

    fun onRemoteAuthState(host: AppFragment, viewState: Any?) {
        if (viewState is WidgetRemoteAuthViewModel.ViewState.Loaded) {
            if (viewState.loginAllowed) bindLogin(host, viewState.handshakeToken)
        } else if (viewState is WidgetRemoteAuthViewModel.ViewState.Failed) {
            // Discord reloads on resume and gets a 404; ignore that mid-2FA. Otherwise the first
            // handshake may still be valid, so offer to finish with it before showing the error.
            if (mfaTicket != null || recovering) return
            val token = pendingHandshake
            if (token != null && SystemClock.elapsedRealtime() - handshakeTime <= HANDSHAKE_TTL) {
                recovering = true
                promptRecover(host, token)
            } else {
                logger.errorToast(EXPIRED_MESSAGE)
            }
        }
    }

    private fun bindLogin(host: AppFragment, token: String) {
        pendingHandshake = token
        handshakeTime = SystemClock.elapsedRealtime()
        val button = findButton(host) ?: return
        button.isEnabled = true
        button.setOnClickListener {
            button.isEnabled = false
            Utils.threadPool.execute { confirmLogin(host, token) }
        }
    }

    private fun confirmLogin(host: AppFragment, token: String) {
        when (remoteFinish(token, null)) {
            FINISH_SUCCESS -> loginDone(host)
            FINISH_MFA -> startMfa(host, token, pendingMfa)
            else -> {
                logger.errorToast(pendingError ?: "Login failed")
                reEnable(host)
            }
        }
    }

    private fun promptRecover(host: AppFragment, token: String) {
        Utils.mainThread.post {
            if (!host.isAdded) {
                recovering = false
                return@post
            }
            val dialog = ConfirmDialog()
            dialog.setTitle("Resume login?")
                .setDescription("The login screen reloaded. Approve the pending login?")
                .setOnOkListener {
                    recovering = false
                    dialog.dismiss()
                    Utils.threadPool.execute { confirmLogin(host, token) }
                }.setOnCancelListener {
                    recovering = false
                    dialog.dismiss()
                    cancelFlow()
                }
            dialog.isCancelable = false
            dialog.show(host.parentFragmentManager, "QrLoginResume")
        }
    }

    private fun startMfa(host: AppFragment, token: String, mfa: JSONObject?) {
        val ticket = mfa?.optString("ticket") ?: ""
        if (ticket.isEmpty()) {
            logger.errorToast("MFA required but no ticket received")
            reEnable(host)
            return
        }
        val types = ArrayList<String>()
        val methods = mfa?.optJSONArray("methods")
        if (methods != null) {
            for (i in 0 until methods.length()) {
                val type = methods.optJSONObject(i)?.optString("type") ?: ""
                if (type.isNotEmpty()) types.add(type)
            }
        }
        val type = when {
            "totp" in types -> "totp"
            "backup" in types -> "backup"
            "password" in types -> "password"
            else -> {
                logger.errorToast("Unsupported 2FA method: ${types.joinToString(", ")}")
                reEnable(host)
                return
            }
        }
        handshakeToken = token
        mfaTicket = ticket
        mfaType = type
        mfaNumeric = type == "totp"

        Utils.mainThread.post {
            Utils.openPageWithProxy(host.requireContext(), QrMfaHost())
            host.requireActivity().finish()
        }
    }

    fun bindMfaDialog(dialog: InputDialog) {
        val password = mfaType == "password"
        dialog.isCancelable = false
        dialog.header.text = if (password) "Password Required" else "Two-Factor Auth"
        dialog.body.text = when {
            password -> "Enter your password"
            mfaNumeric -> "Enter your 6-digit authentication code"
            else -> "Enter a backup code"
        }
        dialog.inputLayout.hint = when {
            password -> "Password"
            mfaNumeric -> "Authentication code"
            else -> "Backup code"
        }
        dialog.inputLayout.editText?.let { input ->
            if (mfaNumeric) {
                input.inputType = InputType.TYPE_CLASS_NUMBER
            } else if (password) {
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        val ok = dialog.okButton
        ok.setOnClickListener {
            // Never trim passwords.
            val code = if (password) dialog.input else dialog.input.trim()
            if (code.isEmpty()) return@setOnClickListener
            ok.isEnabled = false
            Utils.threadPool.execute { submitMfa(dialog, code) }
        }
        dialog.cancelButton.setOnClickListener {
            cancelFlow()
            dialog.requireActivity().finish()
        }
    }

    private fun submitMfa(dialog: InputDialog, code: String) {
        if (mfaTicket == null) {
            mfaError(dialog, "Session expired")
            return
        }
        val mfaToken = mfaVerify(code)
        if (mfaToken == null) {
            mfaError(dialog, pendingError)
            return
        }
        if (remoteFinish(handshakeToken, mfaToken) == FINISH_SUCCESS) loginDone(dialog) else mfaError(dialog, pendingError)
    }

    private fun mfaVerify(code: String): String? {
        return try {
            val body = JSONObject()
                .put("ticket", mfaTicket)
                .put("mfa_type", mfaType)
                .put("data", code)
                .toString()
            val response = post("/mfa/finish", body, null)
            if (response == null) {
                pendingError = "MFA verification failed"
                return null
            }
            val json = JSONObject(response)
            when {
                json.has("token") -> json.optString("token")
                json.optInt("code", -1) == 60008 -> {
                    pendingError = "Invalid code, try again"
                    null
                }
                json.has("retry_after") -> {
                    pendingError = "Rate limited, wait ${Math.ceil(json.optDouble("retry_after")).toInt()}s"
                    null
                }
                else -> {
                    pendingError = json.optString("message", "MFA verification failed")
                    null
                }
            }
        } catch (error: Exception) {
            logger.error("mfaVerify failed", error)
            pendingError = "MFA verification failed"
            null
        }
    }

    private fun remoteFinish(token: String?, mfaToken: String?): Int {
        return try {
            val response = post("/users/@me/remote-auth/finish", handshakeBody(token), mfaToken)
            if (response == null) {
                pendingError = "Login failed"
                return FINISH_FAIL
            }
            if (response.trim().isEmpty()) return FINISH_SUCCESS
            val json = JSONObject(response)
            val code = json.optInt("code", -1)
            when (code) {
                60003 -> {
                    pendingMfa = json.optJSONObject("mfa")
                    FINISH_MFA
                }
                -1 -> FINISH_SUCCESS
                else -> {
                    pendingError = "Login failed: ${json.optString("message", "code $code")}"
                    FINISH_FAIL
                }
            }
        } catch (error: Exception) {
            logger.error("remoteFinish failed", error)
            pendingError = "Login failed"
            FINISH_FAIL
        }
    }

    private fun cancelFlow() {
        val token = handshakeToken
        clearState()
        if (token != null) Utils.threadPool.execute { post("/users/@me/remote-auth/cancel", handshakeBody(token), null) }
    }

    private fun loginDone(fragment: Fragment) {
        logger.infoToast("Logged in!")
        clearState()
        Utils.mainThread.post { fragment.activity?.finish() }
    }

    private fun mfaError(dialog: InputDialog, message: String?) {
        logger.errorToast(message ?: "Login failed")
        Utils.mainThread.post {
            try {
                dialog.okButton.isEnabled = true
            } catch (_: Throwable) {
            }
        }
    }

    private fun reEnable(host: AppFragment) {
        Utils.mainThread.post { findButton(host)?.isEnabled = true }
    }

    private fun findButton(host: AppFragment): MaterialButton? =
        host.view?.findViewById<View>(Utils.getResId(LOGIN_BUTTON, "id")) as? MaterialButton

    private fun clearState() {
        handshakeToken = null
        mfaTicket = null
        mfaType = null
        pendingMfa = null
        pendingHandshake = null
        recovering = false
    }

    private fun handshakeBody(token: String?): String = try {
        JSONObject().put("handshake_token", token).toString()
    } catch (_: Exception) {
        "{}"
    }

    private fun post(route: String, body: String, mfaToken: String?): String? {
        var request: Http.Request? = null
        return try {
            request = Http.Request.newDiscordRequest(route, "POST")
            request.setHeader("Content-Type", "application/json")
            request.setRequestTimeout(TIMEOUT)
            if (!mfaToken.isNullOrEmpty()) {
                request.setHeader("X-Discord-MFA-Authorization", mfaToken)
                request.setHeader("Cookie", "__Secure-recent_mfa=$mfaToken")
            }
            request.executeWithBody(body)
            val stream: InputStream? = try {
                request.conn.inputStream
            } catch (_: IOException) {
                request.conn.errorStream
            }
            if (stream == null) "" else IOUtils.readAsText(stream)
        } catch (error: Exception) {
            logger.error("POST $route failed", error)
            null
        } finally {
            request?.close()
        }
    }
}
