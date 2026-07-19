package com.alechilles.alecstamework.vfx.projectile;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/** Cross-system generation registry used to invalidate all motes from a completed capture session. */
public final class HomingVisualProjectileSessionRegistry {
    private static final Set<Key> ACTIVE = ConcurrentHashMap.newKeySet();

    private HomingVisualProjectileSessionRegistry() {
    }

    public static void activate(@Nonnull String worldName, @Nonnull String ownerUuid, long generation) {
        if (valid(worldName, ownerUuid, generation)) {
            ACTIVE.add(new Key(worldName, ownerUuid, generation));
        }
    }

    public static void deactivate(@Nonnull String worldName, @Nonnull String ownerUuid, long generation) {
        if (valid(worldName, ownerUuid, generation)) {
            ACTIVE.remove(new Key(worldName, ownerUuid, generation));
        }
    }

    public static boolean isActive(@Nonnull String worldName, @Nonnull String ownerUuid, long generation) {
        return valid(worldName, ownerUuid, generation)
                && ACTIVE.contains(new Key(worldName, ownerUuid, generation));
    }

    static void clearForTests() {
        ACTIVE.clear();
    }

    private static boolean valid(String worldName, String ownerUuid, long generation) {
        return worldName != null && !worldName.isBlank()
                && ownerUuid != null && !ownerUuid.isBlank()
                && generation > 0L;
    }

    private record Key(String worldName, String ownerUuid, long generation) {
    }
}
