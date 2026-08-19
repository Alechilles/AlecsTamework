package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.ReachableBlockSourceCache;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService.TargetRejector;
import com.alechilles.alecstamework.npc.progression.NeedsResourceStandTargetSelector;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Stores one sensor instance's bounded per-NPC target state with defensive target copies. */
final class ReachableBlockTargetStateCache {
    private static final long VALIDATED_TTL_MS = 1_500L;
    private static final long PENDING_TTL_MS = 1_500L;
    private static final long MISS_TTL_MS = 3_000L;

    private final ConcurrentHashMap<UUID, CachedTarget> targetsByNpcId = new ConcurrentHashMap<>();

    @Nullable
    CachedTarget get(@Nonnull UUID npcUuid, long nowMs) {
        CachedTarget cached = targetsByNpcId.get(npcUuid);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            targetsByNpcId.remove(npcUuid, cached);
            return null;
        }
        return cached;
    }

    void put(@Nonnull UUID npcUuid,
             @Nonnull State state,
             @Nullable Vector3d target,
             @Nullable ReachableBlockSourceCache.SourceCoordinate source,
             long nowMs) {
        long ttlMs = switch (state) {
            case PENDING -> PENDING_TTL_MS;
            case VALIDATED -> VALIDATED_TTL_MS;
            case MISS -> MISS_TTL_MS;
            case DEFERRED, RETRY -> 0L;
        };
        if (ttlMs <= 0L) {
            return;
        }
        targetsByNpcId.put(npcUuid, new CachedTarget(state, target, source, nowMs + ttlMs));
    }

    void remove(@Nonnull UUID npcUuid, @Nonnull CachedTarget expected) {
        targetsByNpcId.remove(npcUuid, expected);
    }

    enum State {
        PENDING,
        VALIDATED,
        MISS,
        DEFERRED,
        RETRY
    }

    /** State metadata with a defensive target copy owned by this sensor instance. */
    static final class CachedTarget {
        @Nonnull
        private final State state;
        @Nullable
        private final Vector3d target;
        @Nullable
        private final ReachableBlockSourceCache.SourceCoordinate source;
        private final long expiresAtMs;

        private CachedTarget(@Nonnull State state,
                             @Nullable Vector3d target,
                             @Nullable ReachableBlockSourceCache.SourceCoordinate source,
                             long expiresAtMs) {
            this.state = state;
            this.target = target == null ? null : new Vector3d(target);
            this.source = source;
            this.expiresAtMs = expiresAtMs;
        }

        @Nonnull
        State state() {
            return state;
        }

        /** Returns the sensor-owned target for read-only hot-path use. */
        @Nullable
        Vector3d target() {
            return target;
        }

        @Nullable
        ReachableBlockSourceCache.SourceCoordinate source() {
            return source;
        }

        long expiresAtMs() {
            return expiresAtMs;
        }
    }

    @Nonnull
    static String formatTarget(@Nullable Vector3d target) {
        if (target == null
                || !Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "[%.2f,%.2f,%.2f]", target.x, target.y, target.z);
    }
}

record ScanContext(@Nonnull Ref<EntityStore> ref,
                   @Nonnull Role role,
                   @Nonnull Store<EntityStore> store,
                   @Nonnull UUID npcUuid,
                   long nowMs,
                   @Nonnull ChunkStore chunkStore,
                   @Nonnull Store<ChunkStore> chunkStoreStore,
                   @Nonnull Map<Long, WorldChunk> chunkCache,
                   @Nullable BlockSetMembership blockSetMembership,
                   @Nullable ReachableBlockScanDiagnostics diagnostics) {
}

record CandidateContext(@Nonnull Ref<EntityStore> ref,
                        @Nonnull Role role,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull UUID npcUuid,
                        long nowMs,
                        @Nonnull Vector3d npcPosition,
                        @Nonnull NeedsResourceStandTargetSelector.CandidateProjector projector,
                        @Nullable TargetRejector rejector,
                        @Nullable ReachableBlockScanDiagnostics diagnostics) {
}

record TargetResolution(@Nonnull ReachableBlockTargetStateCache.State state,
                        @Nullable Vector3d target,
                        @Nullable ReachableBlockSourceCache.SourceCoordinate source,
                        boolean cacheMiss,
                        @Nonnull String detail) {
    @Nonnull
    static TargetResolution hit(
            @Nonnull Vector3d target,
            @Nullable ReachableBlockSourceCache.SourceCoordinate source,
            @Nonnull String detail) {
        return new TargetResolution(
                ReachableBlockTargetStateCache.State.VALIDATED,
                target,
                source,
                true,
                detail
        );
    }

    @Nonnull
    static TargetResolution pending(
            @Nullable Vector3d target,
            @Nullable ReachableBlockSourceCache.SourceCoordinate source,
            @Nonnull String detail) {
        return new TargetResolution(
                ReachableBlockTargetStateCache.State.PENDING,
                target,
                source,
                false,
                detail
        );
    }

    @Nonnull
    static TargetResolution miss(boolean cacheMiss, @Nonnull String detail) {
        return new TargetResolution(
                ReachableBlockTargetStateCache.State.MISS,
                null,
                null,
                cacheMiss,
                detail
        );
    }

    @Nonnull
    static TargetResolution retry(
            @Nullable ReachableBlockSourceCache.SourceCoordinate source,
            @Nonnull String detail) {
        return new TargetResolution(
                ReachableBlockTargetStateCache.State.RETRY,
                null,
                source,
                false,
                detail
        );
    }

    @Nonnull
    static TargetResolution deferred(@Nonnull String detail) {
        return new TargetResolution(
                ReachableBlockTargetStateCache.State.DEFERRED,
                null,
                null,
                false,
                detail
        );
    }
}

@FunctionalInterface
interface BlockSetMembership {
    boolean blockInSet(int blockSetIndex, int blockTypeIndex);
}
