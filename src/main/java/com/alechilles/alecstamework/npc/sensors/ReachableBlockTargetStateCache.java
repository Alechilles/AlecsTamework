package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsResourceStandTargetSelector;
import com.alechilles.alecstamework.npc.progression.ReachableBlockSourceCache;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Stores short-lived target state per live ECS store and canonical sensor authority.
 *
 * <p>The cache is shared by sensor instances, but each store owns an independent bounded map. A
 * cache value keeps only immutable source coordinates and a defensive copy of the projected
 * target. Map access is serialized per store so admission and eldest eviction are O(1).</p>
 */
public final class ReachableBlockTargetStateCache {
    static final int MAX_ENTRIES_PER_STORE = 8_192;
    private static final long VALIDATED_TTL_MS = 1_500L;
    private static final long PENDING_TTL_MS = 1_500L;
    private static final long MISS_TTL_MS = 3_000L;

    private static final ReachableBlockTargetStateCache SHARED =
            new ReachableBlockTargetStateCache();
    private static final ThreadLocal<MutableTargetKey> LOOKUP_KEY =
            ThreadLocal.withInitial(MutableTargetKey::new);

    private final StoreScopedState<StoreState> statesByStore = new StoreScopedState<>(StoreState::new);

    /** Returns the process-wide cache whose entries remain isolated by ECS store. */
    public static ReachableBlockTargetStateCache shared() {
        return SHARED;
    }

    @Nullable
    CachedTarget get(@Nonnull Store<EntityStore> store,
                     @Nonnull UUID npcUuid,
                     @Nonnull SensorAuthority authority,
                     long nowMs) {
        StoreState state = statesByStore.get(Objects.requireNonNull(store, "store"));
        MutableTargetKey key = LOOKUP_KEY.get().set(
                Objects.requireNonNull(npcUuid, "npcUuid"),
                Objects.requireNonNull(authority, "authority")
        );
        try {
            synchronized (state) {
                CachedTarget cached = state.targetsByKey.get(key);
                if (cached == null) {
                    return null;
                }
                if (nowMs >= cached.expiresAtMs()) {
                    state.targetsByKey.remove(key);
                    return null;
                }
                return cached;
            }
        } finally {
            key.clear();
        }
    }

    void put(@Nonnull Store<EntityStore> store,
             @Nonnull UUID npcUuid,
             @Nonnull SensorAuthority authority,
             @Nonnull State stateValue,
             @Nullable Vector3d target,
             @Nullable ReachableBlockSourceCache.SourceCoordinate source,
             long nowMs) {
        long ttlMs = switch (stateValue) {
            case PENDING -> PENDING_TTL_MS;
            case VALIDATED -> VALIDATED_TTL_MS;
            case MISS -> MISS_TTL_MS;
            case DEFERRED, RETRY -> 0L;
        };
        if (ttlMs <= 0L) {
            return;
        }
        StoreState storeState = statesByStore.get(Objects.requireNonNull(store, "store"));
        TargetKey key = new TargetKey(npcUuid, authority);
        synchronized (storeState) {
            storeState.targetsByKey.put(
                    key,
                    new CachedTarget(stateValue, target, source, nowMs + ttlMs)
            );
            if (storeState.targetsByKey.size() > MAX_ENTRIES_PER_STORE) {
                var eldest = storeState.targetsByKey.entrySet().iterator();
                if (eldest.hasNext()) {
                    eldest.next();
                    eldest.remove();
                }
            }
        }
    }

    void remove(@Nonnull Store<EntityStore> store,
                @Nonnull UUID npcUuid,
                @Nonnull SensorAuthority authority,
                @Nonnull CachedTarget expected) {
        StoreState state = statesByStore.get(Objects.requireNonNull(store, "store"));
        MutableTargetKey key = LOOKUP_KEY.get().set(
                Objects.requireNonNull(npcUuid, "npcUuid"),
                Objects.requireNonNull(authority, "authority")
        );
        try {
            synchronized (state) {
                state.targetsByKey.remove(key, expected);
            }
        } finally {
            key.clear();
        }
    }

    /** Removes all target state for one exact store identity. */
    public void clear(@Nonnull Store<EntityStore> store) {
        statesByStore.remove(Objects.requireNonNull(store, "store"));
    }

    int sizeForTests(@Nonnull Store<EntityStore> store) {
        StoreState state = statesByStore.get(Objects.requireNonNull(store, "store"));
        synchronized (state) {
            return state.targetsByKey.size();
        }
    }

    enum State {
        PENDING,
        VALIDATED,
        MISS,
        DEFERRED,
        RETRY
    }

    /** Canonical sensor authority included in every target-state key. */
    record SensorAuthority(@Nonnull String label,
                           @Nullable String blockSet,
                           @Nonnull List<String> blockTypes,
                           double range,
                           int verticalRadius,
                           double approachRadius) {
        SensorAuthority {
            label = normalizeLabel(label);
            blockSet = normalizeId(blockSet);
            Objects.requireNonNull(blockTypes, "blockTypes");
            TreeSet<String> canonicalTypes = new TreeSet<>();
            for (String blockType : blockTypes) {
                if (blockType != null && !blockType.isBlank()) {
                    canonicalTypes.add(blockType.trim().toLowerCase(Locale.ROOT));
                }
            }
            blockTypes = List.copyOf(canonicalTypes);
            if (!Double.isFinite(range) || range <= 0.0
                    || !Double.isFinite(approachRadius) || approachRadius <= 0.0) {
                throw new IllegalArgumentException("sensor authority ranges must be positive");
            }
            verticalRadius = Math.max(0, verticalRadius);
        }

        @Nonnull
        static SensorAuthority from(@Nonnull String label,
                                    @Nonnull ReachableBlockSourceCache.SensorConfiguration source,
                                    double range,
                                    int verticalRadius,
                                    double approachRadius) {
            return new SensorAuthority(
                    label,
                    source.blockSet(),
                    source.blockTypes(),
                    range,
                    verticalRadius,
                    approachRadius
            );
        }

        @Nonnull
        private static String normalizeLabel(@Nullable String value) {
            return value == null || value.isBlank() ? "block" : value.trim().toLowerCase(Locale.ROOT);
        }

        @Nullable
        private static String normalizeId(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static final class TargetKey {
        private final UUID npcUuid;
        private final SensorAuthority authority;
        private final int hashCode;

        private TargetKey(@Nonnull UUID npcUuid, @Nonnull SensorAuthority authority) {
            this.npcUuid = Objects.requireNonNull(npcUuid, "npcUuid");
            this.authority = Objects.requireNonNull(authority, "authority");
            this.hashCode = 31 * npcUuid.hashCode() + authority.hashCode();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof TargetKey key) {
                return npcUuid.equals(key.npcUuid) && authority.equals(key.authority);
            }
            if (other instanceof MutableTargetKey key) {
                return npcUuid.equals(key.npcUuid) && authority.equals(key.authority);
            }
            return false;
        }
    }

    /** Reusable map-probe key. It must not escape the owning thread or map operation. */
    private static final class MutableTargetKey {
        @Nullable
        private UUID npcUuid;
        @Nullable
        private SensorAuthority authority;

        @Nonnull
        private MutableTargetKey set(@Nonnull UUID npcUuid,
                                     @Nonnull SensorAuthority authority) {
            this.npcUuid = npcUuid;
            this.authority = authority;
            return this;
        }

        private void clear() {
            npcUuid = null;
            authority = null;
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(npcUuid) + Objects.hashCode(authority);
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof TargetKey key) {
                return Objects.equals(npcUuid, key.npcUuid)
                        && Objects.equals(authority, key.authority);
            }
            if (other instanceof MutableTargetKey key) {
                return Objects.equals(npcUuid, key.npcUuid)
                        && Objects.equals(authority, key.authority);
            }
            return false;
        }
    }

    private static final class StoreState {
        private final LinkedHashMap<TargetKey, CachedTarget> targetsByKey = new LinkedHashMap<>(
                16,
                0.75f,
                true
        );
    }

    /** State metadata with a defensive target copy owned by this store cache. */
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

        /** Returns the cache-owned target for read-only hot-path use. */
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

/** Performs one bounded world scan without retaining ECS objects between passes. */
final class ReachableBlockColdScanner {
    private ReachableBlockColdScanner() {
    }

    @Nonnull
    static ReachableBlockSourceCache.ScanResult scan(
            @Nonnull ScanContext context,
            @Nonnull ReachableBlockSourceCache.ScanSlice slice,
            int blockSetIndex,
            @Nonnull java.util.Set<String> blockTypes) {
        ReachableBlockSourceCache.SearchBounds bounds = slice.bounds();
        List<ReachableBlockSourceCache.SourceCoordinate> matches = new ArrayList<>(
                ReachableBlockSourceCache.MAX_SELECTOR_OFFERS_PER_PASS
        );
        int x = slice.startX();
        int y = slice.startY();
        int z = slice.startZ();
        int probes = 0;
        while (probes < slice.probeLimit()) {
            WorldChunk worldChunk = resolveWorldChunk(
                    context.chunkStore(),
                    context.chunkStoreStore(),
                    x,
                    z,
                    context.chunkCache()
            );
            if (worldChunk != null
                    && SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                            worldChunk,
                            x,
                            y,
                            z,
                            blockSetIndex,
                            blockTypes,
                            context.blockSetMembership()
                    )) {
                if (context.diagnostics() != null) {
                    context.diagnostics().recordMatchingSource(
                            x,
                            y,
                            z,
                            worldChunk.getBlockType(x, y, z)
                    );
                }
                matches.add(new ReachableBlockSourceCache.SourceCoordinate(x, y, z));
            }
            probes++;
            if (x == bounds.maxX() && y == bounds.maxY() && z == bounds.maxZ()) {
                break;
            }
            if (z < bounds.maxZ()) {
                z++;
            } else if (x < bounds.maxX()) {
                x++;
                z = bounds.minZ();
            } else if (y < bounds.maxY()) {
                y++;
                x = bounds.minX();
                z = bounds.minZ();
            } else {
                break;
            }
        }
        return ReachableBlockSourceCache.ScanResult.of(matches, probes);
    }

    @Nullable
    private static WorldChunk resolveWorldChunk(
            @Nonnull ChunkStore chunkStore,
            @Nonnull Store<ChunkStore> chunkStoreStore,
            int blockX,
            int blockZ,
            @Nonnull Map<Long, WorldChunk> chunkCache) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        if (chunkCache.containsKey(chunkIndex)) {
            return chunkCache.get(chunkIndex);
        }
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            chunkCache.put(chunkIndex, null);
            return null;
        }
        WorldChunk worldChunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
        chunkCache.put(chunkIndex, worldChunk);
        return worldChunk;
    }
}

/** Carries one candidate result from projection and path preflight. */
record CandidateResult(@Nullable Vector3d target, boolean deferred) {
    @Nonnull
    static CandidateResult hit(@Nonnull Vector3d target) {
        return new CandidateResult(target, false);
    }

    @Nonnull
    static CandidateResult empty() {
        return new CandidateResult(null, false);
    }

    @Nonnull
    static CandidateResult pending(@Nonnull Vector3d target) {
        return new CandidateResult(target, true);
    }
}

/** Applies bounded candidates in current-NPC nearest order and skips failed sources. */
final class ReachableBlockTargetCandidateSelector {
    private static final double DISTANCE_EPSILON = 0.000001;
    private static final int MAX_CANDIDATES = ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES;
    private static final ThreadLocal<CandidateOrderScratch> ORDER_SCRATCH =
            ThreadLocal.withInitial(CandidateOrderScratch::new);

    private ReachableBlockTargetCandidateSelector() {
    }

    @Nonnull
    static Selection select(@Nonnull ReachableBlockSourceCache.Snapshot snapshot,
                            @Nonnull CandidateResolver resolver) {
        return select(snapshot, null, resolver);
    }

    @Nonnull
    static Selection select(@Nonnull ReachableBlockSourceCache.Snapshot snapshot,
                            @Nullable Vector3d npcPosition,
                            @Nonnull CandidateResolver resolver) {
        if (npcPosition == null || snapshot.coordinates().size() < 2) {
            return resolveInSnapshotOrder(snapshot, resolver);
        }
        CandidateOrderScratch scratch = ORDER_SCRATCH.get();
        int count = 0;
        for (ReachableBlockSourceCache.SourceCoordinate source : snapshot.coordinates()) {
            int insertion = count;
            double distance = sourceDistanceSquared(source, npcPosition);
            while (insertion > 0 && isBefore(
                    source,
                    distance,
                    scratch.sources[insertion - 1],
                    scratch.distances[insertion - 1]
            )) {
                scratch.sources[insertion] = scratch.sources[insertion - 1];
                scratch.distances[insertion] = scratch.distances[insertion - 1];
                insertion--;
            }
            scratch.sources[insertion] = source;
            scratch.distances[insertion] = distance;
            count++;
        }
        try {
            for (int index = 0; index < count; index++) {
                ReachableBlockSourceCache.SourceCoordinate source = scratch.sources[index];
                CandidateResult result = resolver.resolve(source);
                if (result.deferred()) {
                    return Selection.pending(result.target(), source);
                }
                if (result.target() != null) {
                    return Selection.validated(result.target(), source);
                }
            }
            return Selection.miss();
        } finally {
            scratch.clear(count);
        }
    }

    @Nonnull
    private static Selection resolveInSnapshotOrder(
            @Nonnull ReachableBlockSourceCache.Snapshot snapshot,
            @Nonnull CandidateResolver resolver) {
        for (ReachableBlockSourceCache.SourceCoordinate source : snapshot.coordinates()) {
            CandidateResult result = resolver.resolve(source);
            if (result.deferred()) {
                return Selection.pending(result.target(), source);
            }
            if (result.target() != null) {
                return Selection.validated(result.target(), source);
            }
        }
        return Selection.miss();
    }

    private static double sourceDistanceSquared(
            @Nonnull ReachableBlockSourceCache.SourceCoordinate source,
            @Nonnull Vector3d npcPosition) {
        double dx = source.x() + 0.5 - npcPosition.x;
        double dy = source.y() + 0.5 - npcPosition.y;
        double dz = source.z() + 0.5 - npcPosition.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static boolean isBefore(
            @Nonnull ReachableBlockSourceCache.SourceCoordinate candidate,
            double candidateDistance,
            @Nonnull ReachableBlockSourceCache.SourceCoordinate current,
            double currentDistance) {
        if (candidateDistance < currentDistance - DISTANCE_EPSILON) {
            return true;
        }
        if (Math.abs(candidateDistance - currentDistance) > DISTANCE_EPSILON) {
            return false;
        }
        if (candidate.x() != current.x()) {
            return candidate.x() < current.x();
        }
        if (candidate.y() != current.y()) {
            return candidate.y() < current.y();
        }
        return candidate.z() < current.z();
    }

    /** Applies one keyed rejection lookup to the current candidate only. */
    @Nullable
    static Vector3d acceptIfNotRejected(@Nonnull UUID npcUuid,
                                        @Nonnull String label,
                                        @Nonnull Vector3d target,
                                        long nowMs,
                                        @Nonnull TargetRejectLookup rejectLookup) {
        return rejectLookup.isRejected(npcUuid, label, target, nowMs) ? null : target;
    }

    private static final class CandidateOrderScratch {
        private final ReachableBlockSourceCache.SourceCoordinate[] sources =
                new ReachableBlockSourceCache.SourceCoordinate[MAX_CANDIDATES];
        private final double[] distances = new double[MAX_CANDIDATES];

        private void clear(int count) {
            for (int index = 0; index < count; index++) {
                sources[index] = null;
            }
        }
    }

    @FunctionalInterface
    interface CandidateResolver {
        @Nonnull
        CandidateResult resolve(@Nonnull ReachableBlockSourceCache.SourceCoordinate source);
    }

    record Selection(@Nullable Vector3d target,
                     @Nullable ReachableBlockSourceCache.SourceCoordinate source,
                     boolean deferred) {
        @Nonnull
        static Selection validated(
                @Nonnull Vector3d target,
                @Nonnull ReachableBlockSourceCache.SourceCoordinate source) {
            return new Selection(target, source, false);
        }

        @Nonnull
        static Selection pending(
                @Nullable Vector3d target,
                @Nonnull ReachableBlockSourceCache.SourceCoordinate source) {
            return new Selection(target, source, true);
        }

        @Nonnull
        static Selection miss() {
            return new Selection(null, null, false);
        }

        boolean validated() {
            return target != null && !deferred;
        }
    }
}

@FunctionalInterface
interface TargetRejectLookup {
    boolean isRejected(@Nonnull UUID npcUuid,
                       @Nonnull String label,
                       @Nonnull Vector3d target,
                       long nowMs);
}

/** Collects lazy, per-probe diagnostics without retaining live ECS state. */
final class ReachableBlockScanDiagnostics {
    private int matchingSources;
    private int projectionFailures;
    private int rejectedTargets;
    @Nullable
    private String firstSource;
    @Nullable
    private String lastPreflightReason;

    void recordMatchingSource(int x, int y, int z, @Nullable com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType blockType) {
        matchingSources++;
        if (firstSource == null) {
            firstSource = String.format(
                    Locale.ROOT,
                    "%s@[%d,%d,%d]",
                    blockType == null || blockType.getId() == null ? "<unknown>" : blockType.getId(),
                    x,
                    y,
                    z
            );
        }
    }

    void recordProjectionRejected(@Nonnull String reason) {
        if ("target_rejected".equals(reason)) {
            rejectedTargets++;
            return;
        }
        projectionFailures++;
    }

    void recordPreflight(@Nonnull String reason) {
        lastPreflightReason = reason;
    }

    @Nonnull
    String summary(@Nonnull String result) {
        return String.format(
                Locale.ROOT,
                "%s sources=%d projectionFailures=%d rejectedTargets=%d firstSource=%s lastPreflight=%s",
                result,
                matchingSources,
                projectionFailures,
                rejectedTargets,
                firstSource == null ? "<none>" : firstSource,
                lastPreflightReason == null ? "<none>" : lastPreflightReason
        );
    }
}

record DiagnosticSnapshot(@Nonnull String signature, long loggedAtMs) {
}
