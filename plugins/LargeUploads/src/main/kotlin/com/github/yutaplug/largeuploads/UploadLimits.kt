package com.github.yutaplug.largeuploads

import com.discord.api.premium.PremiumTier
import com.discord.models.user.User
import com.discord.stores.StoreStream

/** Discord's current per-file limits, matching Aliucord's UploadSize core plugin. */
internal object UploadLimits {
    const val MIB = 1024L * 1024L

    /** Discord rejects messages whose attachments add up to more than this. */
    const val TOTAL_BYTES = 500 * MIB

    fun userMiB(user: User?): Int =
        when (user?.premiumTier) {
            PremiumTier.TIER_0 -> 50 // Nitro Basic
            PremiumTier.TIER_1 -> 50 // Nitro Classic
            PremiumTier.TIER_2 -> 500 // Nitro
            else -> 20
        }

    private fun guildMiB(tier: Int): Int =
        when (tier) {
            2 -> 50
            3 -> 100
            else -> 20
        }

    /** The largest file the current account can attach in [channelId], in bytes. */
    fun forChannel(channelId: Long): Long {
        val channel = StoreStream.getChannels().getChannel(channelId)
        val guild = channel?.let { StoreStream.getGuilds().getGuild(it.i()) }
        return maxOf(userMiB(StoreStream.getUsers().me), guildMiB(guild?.premiumTier ?: 0)) * MIB
    }

    /** Message length limit, which the appended links have to fit into. */
    fun maxMessageLength(): Int = if (StoreStream.getUsers().me?.premiumTier == PremiumTier.TIER_2) 4000 else 2000
}
