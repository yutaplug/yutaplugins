package com.github.yutaplug.fakedecor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils

/**
 * Headless fragment attached to the activity so the image picker result is delivered.
 * Settings pages live inside Aliucord's fragment proxy, where activity results never arrive.
 */
class DecorationPickerFragment : Fragment() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                REQUEST_CODE,
            )
        } catch (e: ActivityNotFoundException) {
            Utils.showToast("No file picker available")
            removeSelf()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        val uri = data?.data
        if (resultCode == Activity.RESULT_OK && uri != null) {
            FakeDecor.instance?.uploadDecoration(Utils.appContext, uri)
        }
        removeSelf()
    }

    private fun removeSelf() {
        if (!isAdded) return
        val manager = parentFragmentManager
        if (!manager.isDestroyed) manager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val REQUEST_CODE = 4831
        private const val TAG = "FakeDecor.DecorationPicker"

        fun open(activity: FragmentActivity) {
            val manager = activity.supportFragmentManager
            if (manager.findFragmentByTag(TAG) != null) return
            manager.beginTransaction().add(DecorationPickerFragment(), TAG).commitAllowingStateLoss()
        }
    }
}
