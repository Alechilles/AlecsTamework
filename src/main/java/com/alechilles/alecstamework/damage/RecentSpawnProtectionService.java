package com.alechilles.alecstamework.damage;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Tracks newly spawned companions that should be protected from invalid spawn-time fall damage.
 */
public final class RecentSpawnProtectionService {
    private static final long DEFAULT_MAX_AGE_MS = 10_000L;
    private static final RecentSpawnProtectionService INSTANCE =
            new RecentSpawnProtectionService(DEFAULT_MAX_AGE_MS);

    private final ConcurrentHashMap<UUID, Protection> protectionByNpc = new ConcurrentHashMap<>();
    private final long maxAgeMs;

    RecentSpawnProtectionService(long maxAgeMs) {
        this.maxAgeMs = Math.max(1L, maxAgeMs);
    }

    @Nonnull
    public static RecentSpawnProtectionService getInstance() {
        return INSTANCE;
    }

    public void record(@Nullable UUID npcUuid,
                       @Nonnull String branch,
                       @Nullable String roleId,
                       long nowMs) {
        if (npcUuid == null) {
            return;
        }
        maybeCleanup(nowMs);
        protectionByNpc.put(npcUuid, new Protection(npcUuid, clean(branch), clean(roleId), nowMs));
    }

    @Nullable
    public Protection getRecentProtection(@Nullable UUID npcUuid, long nowMs) {
        if (npcUuid == null) {
            return null;
        }
        Protection protection = protectionByNpc.get(npcUuid);
        if (protection == null) {
            return null;
        }
        if (isExpired(protection, nowMs)) {
            protectionByNpc.remove(npcUuid, protection);
            return null;
        }
        return protection;
    }

    public void clear(@Nullable UUID npcUuid) {
        if (npcUuid != null) {
            protectionByNpc.remove(npcUuid);
        }
    }

    private void maybeCleanup(long nowMs) {
        Iterator<Map.Entry<UUID, Protection>> iterator = protectionByNpc.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Protection> entry = iterator.next();
            if (isExpired(entry.getValue(), nowMs)) {
                iterator.remove();
            }
        }
    }

    private boolean isExpired(@Nonnull Protection protection, long nowMs) {
        return nowMs - protection.recordedAtMs() > maxAgeMs;
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }

    public record Protection(@Nonnull UUID npcUuid,
                             @Nonnull String branch,
                             @Nonnull String roleId,
                             long recordedAtMs) {
    }
}
