package com.discord.api.user;

import java.io.Serializable;
import java.util.Objects;

/**
 * Compatibility model used by Aliucord's Decorations core plugin.
 * Discord added this model after the 126.021 mobile client was built.
 */
public final class AvatarDecoration implements Serializable {
    private final String asset;
    private final long skuId;

    public AvatarDecoration(String asset, long skuId) {
        this.asset = asset;
        this.skuId = skuId;
    }

    public String getAsset() {
        return asset;
    }

    public long getSkuId() {
        return skuId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AvatarDecoration)) return false;
        AvatarDecoration that = (AvatarDecoration) other;
        return skuId == that.skuId && Objects.equals(asset, that.asset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(asset, skuId);
    }

    @Override
    public String toString() {
        return "AvatarDecoration(asset=" + asset + ", skuId=" + skuId + ")";
    }
}
