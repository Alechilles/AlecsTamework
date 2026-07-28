package com.alechilles.alecstamework.ownership.live;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Process-local index of currently loaded owned NPCs used by the released owner-cap policy.
 *
 * <p>The index is deliberately not a persistence authority. Entity and owner-component systems
 * maintain one idempotent entry per loaded NPC, allowing global cap reads without crossing or
 * blocking world threads.
 */
public final class OwnerPopulationLiveIndex {
    private final Map<UUID, OwnedNpc> entries = new ConcurrentHashMap<>();

    public void observe(@Nullable UUID npcId,
                        @Nullable UUID ownerId,
                        @Nullable String worldName) {
        if (npcId == null) {
            return;
        }
        String normalizedWorld = normalizeWorld(worldName);
        if (ownerId == null || normalizedWorld == null) {
            entries.remove(npcId);
            return;
        }
        entries.put(npcId, new OwnedNpc(ownerId, normalizedWorld));
    }

    public void remove(@Nullable UUID npcId) {
        if (npcId != null) {
            entries.remove(npcId);
        }
    }

    /** Drops all loaded evidence when the plugin runtime is shut down. */
    public void clear() {
        entries.clear();
    }

    public int count(@Nonnull UUID ownerId,
                     @Nonnull TwGlobalConfig.PerPlayerLimitScope scope,
                     @Nullable String worldName) {
        if (ownerId == null || scope == null) {
            throw new IllegalArgumentException("Owner and population scope are required");
        }
        String normalizedWorld = normalizeWorld(worldName);
        if (scope == TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                && normalizedWorld == null) {
            return -1;
        }
        int count = 0;
        for (OwnedNpc entry : entries.values()) {
            if (!ownerId.equals(entry.ownerId())) {
                continue;
            }
            if (scope != TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                    && !normalizedWorld.equals(entry.worldName())) {
                continue;
            }
            if (count == Integer.MAX_VALUE) {
                return count;
            }
            count++;
        }
        return count;
    }

    int size() {
        return entries.size();
    }

    @Nullable
    private static String normalizeWorld(@Nullable String worldName) {
        return worldName == null || worldName.isBlank() ? null : worldName.trim();
    }

    private record OwnedNpc(UUID ownerId, String worldName) {
    }
}
