package com.alechilles.alecstamework.avatarflight;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Carries a stale avatar-flight source owner from world recovery to their next join. */
public final class AvatarFlightStaleOwnerRecoveryRegistry {
    private static final java.util.Set<UUID> OWNERS =
            ConcurrentHashMap.newKeySet();

    private AvatarFlightStaleOwnerRecoveryRegistry() {
    }

    public static void record(@Nullable UUID ownerUuid) {
        if (ownerUuid != null) {
            OWNERS.add(ownerUuid);
        }
    }

    public static boolean claim(@Nullable UUID ownerUuid) {
        return ownerUuid != null && OWNERS.remove(ownerUuid);
    }
}
