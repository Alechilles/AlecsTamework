package com.alechilles.alecstamework.ownership.live;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.util.Map;
import java.util.Set;
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
    private final Map<OwnerWorld, Set<UUID>> byOwnerWorld = new ConcurrentHashMap<>();

    public void observe(@Nullable UUID npcId,
                        @Nullable UUID ownerId,
                        @Nullable String worldName) {
        observe(npcId, ownerId, worldName, null);
    }

    public void observe(@Nullable UUID npcId,
                        @Nullable UUID ownerId,
                        @Nullable String worldName,
                        @Nullable String roleId) {
        if (npcId == null) {
            return;
        }
        String normalizedWorld = normalizeWorld(worldName);
        if (ownerId == null || normalizedWorld == null) {
            remove(npcId);
            return;
        }
        entries.compute(npcId, (id, previous) -> {
            OwnerWorld key = new OwnerWorld(ownerId, normalizedWorld);
            if (previous == null || !key.equals(previous.key())) {
                if (previous != null) removeFromOwner(id, previous.key());
                byOwnerWorld.compute(key, (ignored, ids) -> {
                    if (ids == null) ids = ConcurrentHashMap.newKeySet();
                    ids.add(id);
                    return ids;
                });
            }
            return new OwnedNpc(ownerId, normalizedWorld, normalizeRole(roleId));
        });
    }

    public void remove(@Nullable UUID npcId) {
        if (npcId != null) {
            entries.computeIfPresent(npcId, (id, previous) -> {
                removeFromOwner(id, previous.key());
                return null;
            });
        }
    }

    /** Drops all loaded evidence when the plugin runtime is shut down. */
    public void clear() {
        entries.clear();
        byOwnerWorld.clear();
    }

    /** Stable candidate IDs only. Callers must recheck live ownership on the owning world thread. */
    @Nonnull
    public Set<UUID> ownedNpcIds(@Nonnull UUID ownerId, @Nonnull String worldName) {
        String normalizedWorld = normalizeWorld(worldName);
        if (ownerId == null || normalizedWorld == null) return Set.of();
        var ids = byOwnerWorld.get(new OwnerWorld(ownerId, normalizedWorld));
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    private void removeFromOwner(UUID npcId, OwnerWorld key) {
        byOwnerWorld.computeIfPresent(key, (ignored, ids) -> {
            ids.remove(npcId);
            return ids.isEmpty() ? null : ids;
        });
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

    /** Counts globally loaded owned NPCs whose current role is in the supplied set. */
    public int countOwnedRoles(@Nonnull UUID ownerId,
                               @Nonnull Set<String> roleIds) {
        if (ownerId == null || roleIds == null) {
            throw new IllegalArgumentException("Owner and role IDs are required");
        }
        int count = 0;
        for (OwnedNpc entry : entries.values()) {
            if (ownerId.equals(entry.ownerId())
                    && entry.roleId() != null
                    && roleIds.contains(entry.roleId())) {
                if (count == Integer.MAX_VALUE) {
                    return count;
                }
                count++;
            }
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

    @Nullable
    private static String normalizeRole(@Nullable String roleId) {
        return roleId == null || roleId.isBlank() ? null : roleId.trim();
    }

    private record OwnedNpc(UUID ownerId, String worldName,
                            @Nullable String roleId) {
        OwnerWorld key() { return new OwnerWorld(ownerId, worldName); }
    }

    private record OwnerWorld(UUID ownerId, String worldName) { }
}
