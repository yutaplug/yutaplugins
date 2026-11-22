package com.github.yutaplug.fixes.qrlogin

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.aliucord.fragments.InputDialog
import com.aliucord.patcher.Hook
import com.discord.app.AppFragment
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.auth.WidgetRemoteAuth
import com.discord.widgets.auth.WidgetRemoteAuthViewModel
import com.github.yutaplug.fixes.Fix
import com.lytefast.flexinput.R

/** Ported from secp192k1's QRCodeLogin: https://github.com/secp192k1/Aliucord-Plugins */
internal class QRCodeLogin : Fix(
    "QRCodeLogin",
    "QR code login",
    "Fixes logging in on another device by scanning its QR code, including 2FA and password prompts.",
    author = "secp192k1",
) {
    override fun start(context: Context) {
        // The native scanner already opens WidgetRemoteAuth for login codes. Route its Login
        // button through remote-auth/finish, which also handles the 2FA challenge (60003).
        patcher.patch(
            WidgetRemoteAuth::class.java.getDeclaredMethod(
                "configureUI",
                WidgetRemoteAuthViewModel.ViewState::class.java,
            ),
            Hook { param -> QrLogin.onRemoteAuthState(param.thisObject as AppFragment, param.args[0]) },
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}

/** 2FA code or password entry for a QR login. */
class QrMfaDialog : InputDialog() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        QrLogin.bindMfaDialog(this)
    }
}

/**
 * Standalone host for the 2FA prompt, so it survives switching apps: WidgetRemoteAuth reloads on
 * resume and fails with "Can't find this computer". A blank view keeps the dialog over the dark
 * activity background.
 */
class QrMfaHost : AppFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        return View(context).apply {
            setBackgroundColor(ColorCompat.getThemedColor(context, R.b.colorBackgroundPrimary))
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val manager = requireActivity().supportFragmentManager
        if (manager.findFragmentByTag(QrLogin.MFA_TAG) == null) QrMfaDialog().show(manager, QrLogin.MFA_TAG)
    }
}
