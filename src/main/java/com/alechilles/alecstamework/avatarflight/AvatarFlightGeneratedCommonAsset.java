package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/**
 * In-memory common asset for avatar-flight model variants generated at runtime.
 */
final class AvatarFlightGeneratedCommonAsset extends CommonAsset {
    private final byte[] bytes;

    AvatarFlightGeneratedCommonAsset(@Nonnull String name, byte[] bytes) {
        super(name, bytes);
        this.bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Nonnull
    @Override
    protected CompletableFuture<byte[]> getBlob0() {
        return CompletableFuture.completedFuture(Arrays.copyOf(bytes, bytes.length));
    }
}
