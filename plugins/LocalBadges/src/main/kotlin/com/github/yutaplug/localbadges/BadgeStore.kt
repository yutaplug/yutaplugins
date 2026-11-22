package com.github.yutaplug.localbadges

import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject

/**
 * A locally added badge. [icon] is either an http(s) image URL or the name of one of Discord's
 * badge drawables (badges saved by earlier versions).
 */
class LocalBadge(val icon: String, val text: String) {
    val isUrl: Boolean get() = icon.startsWith("http://") || icon.startsWith("https://")
}

/** A badge offered in the picker. [label] is also the default badge name. */
class PresetBadge(hash: String, val label: String) {
    val icon = "https://cdn.discordapp.com/badge-icons/$hash.png"
}

/** Current Discord profile badges, using the same badge-icons images newer clients show. */
val PRESET_BADGES = listOf(
    PresetBadge("5e74e9b61934fc1f67c65515d1f7e60d", "Discord Staff"),
    PresetBadge("3f9748e53446a137a052f3454e2de41e", "Partnered Server Owner"),
    PresetBadge("fee1624003e2fee35cb398e125dc479b", "Moderator Programs Alumni"),
    PresetBadge("bf01d1073931f921909045f3a39fd264", "HypeSquad Events"),
    PresetBadge("8a88d63823d8a71cd5e390baa45efa02", "HypeSquad Bravery"),
    PresetBadge("011940fd013da3f7fb926e4a1cd2e618", "HypeSquad Brilliance"),
    PresetBadge("3aa41de486fa12454c3761e8e223442e", "HypeSquad Balance"),
    PresetBadge("2717692c7dca7289b35297368a940dd0", "Discord Bug Hunter"),
    PresetBadge("848f79194d4be5ff5f81505cbd0ce1e6", "Discord Bug Hunter (Gold)"),
    PresetBadge("6bdc42827a38498929a4920da12695d9", "Active Developer"),
    PresetBadge("6df5892e0f35b051f8b61eace34f4967", "Early Verified Bot Developer"),
    PresetBadge("7060786766c9c840eb3019e725d2b358", "Early Supporter"),
    // Evolving Nitro subscriber badges
    PresetBadge("43aa87a32b12a410943ab55ccf290b34", "Nitro"),
    PresetBadge("4f33c4a9c64ce221936bd256c356f91f", "Nitro Bronze (1 month)"),
    PresetBadge("4514fab914bdbfb4ad2fa23df76121a6", "Nitro Silver (3 months)"),
    PresetBadge("2895086c18d5531d499862e41d1155a6", "Nitro Gold (6 months)"),
    PresetBadge("0334688279c8359120922938dcb1d6f8", "Nitro Platinum (1 year)"),
    PresetBadge("0d61871f72bb9a33a7ae568c1fb4f20a", "Nitro Diamond (2 years)"),
    PresetBadge("11e2d339068b55d3a506cff34d3780f3", "Nitro Emerald (3 years)"),
    PresetBadge("cd5e2cfd9d7f27a8cdcd3e8a8d5dc9f4", "Nitro Ruby (5 years)"),
    PresetBadge("5b154df19c53dce2af92c9b61e6be5e2", "Nitro Opal (6+ years)"),
    // Evolving server boost badges
    PresetBadge("51040c70d4f20a921ad6674ff86fc95c", "Server Booster (1 month)"),
    PresetBadge("0e4080d1d333bc7ad29ef6528b6f2fb7", "Server Booster (2 months)"),
    PresetBadge("72bed924410c304dbe3d00a6e593ff59", "Server Booster (3 months)"),
    PresetBadge("df199d2050d3ed4ebf84d64ae83989f8", "Server Booster (6 months)"),
    PresetBadge("996b3e870e8a22ce519b3a50e6bdd52f", "Server Booster (9 months)"),
    PresetBadge("991c9f39ee33d7537d9f408c3e53141e", "Server Booster (1 year)"),
    PresetBadge("cb3ae83c15e970e8f3d410bc62cb8b99", "Server Booster (1 year 3 months)"),
    PresetBadge("7142225d31238f6387d9f09efaa02759", "Server Booster (1 year 6 months)"),
    PresetBadge("ec92202290b48d0879b7413d2dde3bab", "Server Booster (2 years)"),
    // Evolving Nitro gifting badges
    PresetBadge("ac305d1b9481f312ce4419e7f8296558", "Nitro Gifting: Patron"),
    PresetBadge("8b7792c4f65953d3ff564f23429cb79e", "Nitro Gifting: Champion"),
    PresetBadge("3119f5504b2cd09576a323908c7c3517", "Nitro Gifting: Luminary"),
    PresetBadge("64f2413c9b9803661322aaad25826b62", "Nitro Gifting: Icon"),
    PresetBadge("77d65b1f210014a11eb1582ee06ab684", "Nitro Gifting: Hero"),
    PresetBadge("7fe346cfc5da1340087d8759a9e7a395", "Nitro Gifting: Legend"),
    PresetBadge("7d9ae358c8c5e118768335dbe68b4fb8", "Completed a Quest"),
    PresetBadge("83d8a1eb09a8d64e59233eec5d4d5c2d", "Collected the Orb Profile Badge"),
    PresetBadge("6f9e37f9029ff57aef81db857890005e", "Supports Commands"),
    PresetBadge("f2459b691ac7453ed6039bbcfaccbfcd", "Uses AutoMod"),
    PresetBadge("6de6d34650760ba5551a79732e98ed60", "Originally known as"),
    PresetBadge("ca105ad9cfc8580c765101d17bbb2323", "Level 100 Reached"),
)

/** Badges per user id, saved as one JSON object in the plugin settings. */
class BadgeStore(private val settings: SettingsAPI) {
    private var cache: MutableMap<Long, MutableList<LocalBadge>>? = null

    private fun all(): MutableMap<Long, MutableList<LocalBadge>> {
        cache?.let { return it }
        val map = HashMap<Long, MutableList<LocalBadge>>()
        try {
            val json = JSONObject(settings.getString(KEY, "{}"))
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toLongOrNull() ?: continue
                val array = json.optJSONArray(key) ?: continue
                val list = ArrayList<LocalBadge>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val icon = obj.optString("icon", "")
                    if (icon.isEmpty()) continue
                    list.add(LocalBadge(icon, obj.optString("text", "")))
                }
                if (list.isNotEmpty()) map[id] = list
            }
        } catch (_: Throwable) {
        }
        cache = map
        return map
    }

    fun get(userId: Long): List<LocalBadge> = all()[userId].orEmpty()

    fun add(userId: Long, badge: LocalBadge) {
        all().getOrPut(userId) { ArrayList() }.add(badge)
        save()
    }

    fun remove(userId: Long, index: Int) {
        val list = all()[userId] ?: return
        if (index < 0 || index >= list.size) return
        list.removeAt(index)
        if (list.isEmpty()) all().remove(userId)
        save()
    }

    private fun save() {
        val json = JSONObject()
        for ((id, list) in all()) {
            val array = JSONArray()
            for (badge in list) array.put(JSONObject().put("icon", badge.icon).put("text", badge.text))
            json.put(id.toString(), array)
        }
        settings.setString(KEY, json.toString())
    }

    private companion object {
        const val KEY = "badges"
    }
}
