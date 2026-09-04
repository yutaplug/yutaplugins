package com.discord.api.user;

import java.io.Serializable;
import java.util.Objects;

/**
 * Compatibility model used by Aliucord's Decorations core plugin.
 *
 * The legacy Discord client bundled by this repository predates Discord's
 * avatar-decoration model. The Decorations core plugin still needs the model
 * at compile time, so FakeDecor supplies the matching value object.
 */
public final class AvatarDecoration implements Serializable {
    private final String asset;
    private final long skuId;
    private final Integer expiresAt;

    public AvatarDecoration(String asset, long skuId, Integer expiresAt) {
        this.asset = asset;
        this.skuId = skuId;
        this.expiresAt = expiresAt;
    }

    public String getAsset() {
        return asset;
    }

    public long getSkuId() {
        return skuId;
    }

    public Integer getExpiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AvatarDecoration)) return false;
        AvatarDecoration that = (AvatarDecoration) other;
        return skuId == that.skuId
                && Objects.equals(asset, that.asset)
                && Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(asset, skuId, expiresAt);
    }

    @Override
    public String toString() {
        return "AvatarDecoration(asset=" + asset + ", skuId=" + skuId + ", expiresAt=" + expiresAt + ")";
    }
}
