package com.github.yutaplug.fixes

import com.aliucord.api.PatcherAPI
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Member

/** Tracks one fix's hooks on the plugin's [PatcherAPI], so each fix can be turned off on its own. */
internal class FixPatcher(private val api: PatcherAPI) {
    private val unpatches = ArrayList<Runnable>()

    /** Like [PatcherAPI.patch]: failures to find the method are logged, not thrown. */
    fun patch(
        clazz: Class<*>,
        fn: String,
        paramTypes: Array<Class<*>> = emptyArray(),
        hook: XC_MethodHook,
    ): Runnable = track(api.patch(clazz, fn, paramTypes, hook))

    fun patch(member: Member, hook: XC_MethodHook): Runnable = track(api.patch(member, hook))

    private fun track(unpatch: Runnable): Runnable {
        synchronized(unpatches) { unpatches.add(unpatch) }
        return unpatch
    }

    fun unpatchAll() {
        val pending = synchronized(unpatches) {
            ArrayList(unpatches).also { unpatches.clear() }
        }
        pending.forEach { it.run() }
    }
}
