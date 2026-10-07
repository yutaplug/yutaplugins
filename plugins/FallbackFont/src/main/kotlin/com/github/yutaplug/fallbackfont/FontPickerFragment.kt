package com.github.yutaplug.fallbackfont

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.aliucord.Utils

/**
 * Headless fragment attached to the activity so the file picker result is delivered.
 * Settings pages live inside Aliucord's fragment proxy, where activity results never arrive.
 */
class FontPickerFragment : Fragment() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // File managers report font MIME types inconsistently, so the file is validated after picking.
                    type = "*/*"
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
        val slot = arguments?.getString(ARG_SLOT)?.let { name -> FontSlot.values().firstOrNull { it.name == name } }
        if (resultCode == Activity.RESULT_OK && uri != null && slot != null) {
            FallbackFont.instance?.onFontPicked(slot, uri)
        }
        removeSelf()
    }

    private fun removeSelf() {
        if (!isAdded) return
        val manager = parentFragmentManager
        if (!manager.isDestroyed) manager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val REQUEST_CODE = 4833
        private const val TAG = "FallbackFont.FontPicker"
        private const val ARG_SLOT = "slot"

        fun open(activity: FragmentActivity, slot: FontSlot) {
            val manager = activity.supportFragmentManager
            if (manager.findFragmentByTag(TAG) != null) return
            val fragment = FontPickerFragment().apply { arguments = Bundle().apply { putString(ARG_SLOT, slot.name) } }
            manager.beginTransaction().add(fragment, TAG).commitAllowingStateLoss()
        }
    }
}
