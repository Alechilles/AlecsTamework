package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService.TargetRejector;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourceStandTargetSelector;
import com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkReachableBlockTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.blockset.BlockSetModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Generic sensor that exposes a path-preflighted approach target for nearby matching blocks.
 */
public final class SensorTameworkReachableBlockTarget extends TameworkSensorBase {
    public static final double DEFAULT_APPROACH_RADIUS = NeedsResourceStandTargetSelector.MIN_ADJACENT_DISTANCE;
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE = new NeedsResourcePathPreflightService();
    private static final ThreadLocal<NeedsResourceStandTargetSelector> STAND_TARGET_SELECTOR =
            ThreadLocal.withInitial(NeedsResourceStandTargetSelector::new);
    private static final long TARGET_CACHE_HIT_TTL_MS = 1_500L;
    private static final long TARGET_CACHE_MISS_TTL_MS = 3_000L;
    private static final int MAX_SOURCE_CANDIDATES_PER_SCAN = 16;
    private static final double SCORE_EPSILON = 0.000001;

    @Nullable
    private final String blockSet;
    @Nonnull
    private final Set<String> blockTypes;
    @Nonnull
    private final String label;
    private final double range;
    private final int verticalRadius;
    private final double approachRadius;
    private final TameworkTargetPositionInfo positionInfo = new TameworkTargetPositionInfo();
    private final TameworkTargetPositionInfoProvider infoProvider =
            new TameworkTargetPositionInfoProvider(null, positionInfo);
    private final ConcurrentHashMap<UUID, CachedTargetResult> cachedTargetsByNpcId = new ConcurrentHashMap<>();

    public SensorTameworkReachableBlockTarget(@Nonnull BuilderSensorTameworkReachableBlockTarget builder,
                                              @Nonnull BuilderSupport support) {
        super(builder);
        this.blockSet = sanitizeNullableId(builder.getBlockSet(support));
        this.blockTypes = sanitizeIdSet(builder.getBlockTypes(support));
        this.label = sanitizeLabel(builder.getLabel(support));
        this.range = sanitizePositive(builder.getRange(support), 12.0);
        this.verticalRadius = sanitizeVerticalRadius(builder.getVerticalRadius(support));
        this.approachRadius = sanitizePositive(builder.getApproachRadius(support), DEFAULT_APPROACH_RADIUS);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        positionInfo.clear();
        if (!super.matches(ref, role, dt, store) || !hasMatchConfig()) {
            return false;
        }
        UUID npcUuid = resolveNpcUuid(ref, store);
        long nowMs = System.currentTimeMillis();
        CachedTargetResult cached = npcUuid != null ? getCachedTarget(npcUuid, nowMs) : null;
        if (cached != null && cached.target() != null
                && PositionTargetRejectCache.isRejected(npcUuid, label, cached.target(), nowMs)) {
            cachedTargetsByNpcId.remove(npcUuid, cached);
            cached = null;
        }
        if (cached != null) {
            if (cached.target() == null) {
                return false;
            }
            positionInfo.setTarget(cached.target().x, cached.target().y, cached.target().z);
            return true;
        }

        TargetResolution resolution = resolveTarget(ref, role, store, npcUuid, nowMs);
        if (resolution.target() == null) {
            if (npcUuid != null && resolution.cacheMiss()) {
                cacheTarget(npcUuid, null, nowMs);
            }
            return false;
        }
        if (npcUuid != null) {
            cacheTarget(npcUuid, resolution.target(), nowMs);
        }
        positionInfo.setTarget(resolution.target().x, resolution.target().y, resolution.target().z);
        return true;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return infoProvider;
    }

    @Nonnull
    private TargetResolution resolveTarget(@Nonnull Ref<EntityStore> ref,
                                           @Nonnull Role role,
                                           @Nonnull Store<EntityStore> store,
                                           @Nullable UUID npcUuid,
                                           long nowMs) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || transform.getPosition() == null || world == null || world.getChunkStore() == null) {
            return TargetResolution.miss(true);
        }
        if (npcUuid == null) {
            return TargetResolution.miss(true);
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return TargetResolution.miss(true);
        }

        Vector3d npcPosition = transform.getPosition();
        NeedsResourceStandTargetSelector.CandidateProjector projector =
                STAND_TARGET_SELECTOR.get().createProjector(role, store);
        if (projector == null) {
            return TargetResolution.miss(true);
        }
        ScanContext context = new ScanContext(
                ref,
                role,
                store,
                npcUuid,
                nowMs,
                chunkStore,
                chunkStoreStore,
                npcPosition,
                (int) Math.floor(npcPosition.x),
                (int) Math.floor(npcPosition.y),
                (int) Math.floor(npcPosition.z),
                range * range,
                new HashMap<>(),
                projector,
                PositionTargetRejectCache.hasRejectedTargetFor(npcUuid, label, nowMs)
                        ? target -> PositionTargetRejectCache.isRejected(npcUuid, label, target, nowMs)
                        : null
        );
        int searchRadius = Math.max(1, (int) Math.ceil(range));

        int checkedSources = 0;
        for (int horizontalRadius = 0; horizontalRadius <= searchRadius; horizontalRadius++) {
            RingResult ringResult = scanRing(context, horizontalRadius, checkedSources);
            checkedSources += ringResult.checkedSources();
            if (ringResult.target() != null) {
                return TargetResolution.hit(ringResult.target());
            }
            if (ringResult.deferred() || checkedSources >= MAX_SOURCE_CANDIDATES_PER_SCAN) {
                return TargetResolution.miss(false);
            }
        }
        return TargetResolution.miss(true);
    }

    @Nonnull
    private RingResult scanRing(@Nonnull ScanContext context, int horizontalRadius, int alreadyCheckedSources) {
        int checkedSources = 0;
        for (int yOffset = -verticalRadius; yOffset <= verticalRadius; yOffset++) {
            int y = context.blockY() + yOffset;
            for (int x = context.blockX() - horizontalRadius; x <= context.blockX() + horizontalRadius; x++) {
                for (int z = context.blockZ() - horizontalRadius; z <= context.blockZ() + horizontalRadius; z++) {
                    if (!isOnRing(x, z, context.blockX(), context.blockZ(), horizontalRadius)
                            || outsideRadius(x, z, context.blockX(), context.blockZ(), context.radiusSq())) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(
                            context.chunkStore(),
                            context.chunkStoreStore(),
                            x,
                            z,
                            context.chunkCache()
                    );
                    if (worldChunk == null || !matchesConfiguredBlock(worldChunk, x, y, z, blockSet, blockTypes)) {
                        continue;
                    }
                    checkedSources++;
                    if (alreadyCheckedSources + checkedSources > MAX_SOURCE_CANDIDATES_PER_SCAN) {
                        return RingResult.deferred(checkedSources);
                    }
                    CandidateResult candidate = resolveCandidate(context, x, y, z);
                    if (candidate.isMiss()) {
                        continue;
                    }
                    if (candidate.target() != null) {
                        return RingResult.hit(candidate.target(), checkedSources);
                    }
                    return RingResult.deferred(checkedSources);
                }
            }
        }
        return RingResult.miss(checkedSources);
    }

    @Nonnull
    private CandidateResult resolveCandidate(@Nonnull ScanContext context, int x, int y, int z) {
        Vector3d target = STAND_TARGET_SELECTOR.get().findNearestProjectedTarget(
                x,
                y,
                z,
                context.npcPosition(),
                approachRadius,
                false,
                context.projector()
        );
        if (target == null || (context.rejector() != null && context.rejector().rejects(target))) {
            return CandidateResult.empty();
        }
        PathPreflightResult preflight = PATH_PREFLIGHT_SERVICE.preflight(
                context.ref(),
                context.role(),
                context.store(),
                context.npcUuid(),
                label,
                target,
                context.nowMs()
        );
        if (preflight.ready()) {
            return CandidateResult.hit(target);
        }
        if (preflight.noPath()) {
            PositionTargetRejectCache.reject(
                    context.npcUuid(),
                    label,
                    target,
                    PositionTargetRejectCache.DEFAULT_TTL_SECONDS,
                    context.nowMs()
            );
            return CandidateResult.empty();
        }
        return CandidateResult.pending();
    }

    static boolean matchesConfiguredBlock(@Nonnull WorldChunk worldChunk,
                                          int x,
                                          int y,
                                          int z,
                                          @Nullable String blockSet,
                                          @Nonnull Set<String> blockTypes) {
        BlockType blockType = worldChunk.getBlockType(x, y, z);
        int blockId = worldChunk.getBlock(x, y, z);
        return matchesConfiguredBlock(blockType, blockId, blockSet, blockTypes);
    }

    static boolean matchesConfiguredBlock(@Nullable BlockType blockType,
                                          int blockId,
                                          @Nullable String blockSet,
                                          @Nonnull Set<String> blockTypes) {
        if (blockType == null) {
            return false;
        }
        String id = sanitizeNullableId(blockType.getId());
        if (id != null && blockTypes.contains(id)) {
            return true;
        }
        String normalizedBlockSet = sanitizeNullableId(blockSet);
        if (normalizedBlockSet == null) {
            return false;
        }
        BlockSetModule module = BlockSetModule.getInstance();
        if (module == null) {
            return false;
        }
        try {
            return module.blockInSet(blockId, normalizedBlockSet);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Nonnull
    static Set<String> sanitizeIdSet(@Nullable String[] ids) {
        if (ids == null || ids.length == 0) {
            return Set.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String id : ids) {
            String normalized = sanitizeNullableId(id);
            if (normalized != null) {
                sanitized.add(normalized);
            }
        }
        return sanitized.isEmpty() ? Set.of() : Set.copyOf(sanitized);
    }

    @Nonnull
    static String sanitizeLabel(@Nullable String value) {
        return value == null || value.isBlank() ? "Block" : value.trim();
    }

    @Nullable
    static String sanitizeNullableId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasMatchConfig() {
        return blockSet != null || !blockTypes.isEmpty();
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        return store.getExternalData().getWorld();
    }

    @Nullable
    private static WorldChunk resolveWorldChunk(@Nonnull ChunkStore chunkStore,
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

    private static boolean isOnRing(int x, int z, int centerX, int centerZ, int horizontalRadius) {
        return Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) == horizontalRadius;
    }

    private static boolean outsideRadius(int x, int z, int centerX, int centerZ, double radiusSq) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return (dx * dx) + (dz * dz) > radiusSq + SCORE_EPSILON;
    }

    private static double sanitizePositive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static int sanitizeVerticalRadius(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 4;
        }
        return Math.max(0, (int) Math.ceil(value));
    }

    @Nullable
    private CachedTargetResult getCachedTarget(@Nonnull UUID npcUuid, long nowMs) {
        CachedTargetResult cached = cachedTargetsByNpcId.get(npcUuid);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            cachedTargetsByNpcId.remove(npcUuid, cached);
            return null;
        }
        return cached;
    }

    private void cacheTarget(@Nonnull UUID npcUuid, @Nullable Vector3d target, long nowMs) {
        cachedTargetsByNpcId.put(
                npcUuid,
                new CachedTargetResult(
                        target != null ? new Vector3d(target) : null,
                        nowMs + (target != null ? TARGET_CACHE_HIT_TTL_MS : TARGET_CACHE_MISS_TTL_MS)
                )
        );
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    static int maxSourceCandidatesPerScanForTests() {
        return MAX_SOURCE_CANDIDATES_PER_SCAN;
    }

    private record CachedTargetResult(@Nullable Vector3d target, long expiresAtMs) {
    }

    private record ScanContext(@Nonnull Ref<EntityStore> ref,
                               @Nonnull Role role,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull UUID npcUuid,
                               long nowMs,
                               @Nonnull ChunkStore chunkStore,
                               @Nonnull Store<ChunkStore> chunkStoreStore,
                               @Nonnull Vector3d npcPosition,
                               int blockX,
                               int blockY,
                               int blockZ,
                               double radiusSq,
                               @Nonnull Map<Long, WorldChunk> chunkCache,
                               @Nonnull NeedsResourceStandTargetSelector.CandidateProjector projector,
                               @Nullable TargetRejector rejector) {
    }

    private record TargetResolution(@Nullable Vector3d target, boolean cacheMiss) {
        @Nonnull
        private static TargetResolution hit(@Nonnull Vector3d target) {
            return new TargetResolution(target, true);
        }

        @Nonnull
        private static TargetResolution miss(boolean cacheMiss) {
            return new TargetResolution(null, cacheMiss);
        }
    }

    private record RingResult(@Nullable Vector3d target, int checkedSources, boolean deferred) {
        @Nonnull
        private static RingResult hit(@Nonnull Vector3d target, int checkedSources) {
            return new RingResult(target, checkedSources, false);
        }

        @Nonnull
        private static RingResult miss(int checkedSources) {
            return new RingResult(null, checkedSources, false);
        }

        @Nonnull
        private static RingResult deferred(int checkedSources) {
            return new RingResult(null, checkedSources, true);
        }
    }

    private record CandidateResult(@Nullable Vector3d target, boolean deferred) {
        private boolean isMiss() {
            return target == null && !deferred;
        }

        @Nonnull
        private static CandidateResult hit(@Nonnull Vector3d target) {
            return new CandidateResult(target, false);
        }

        @Nonnull
        private static CandidateResult empty() {
            return new CandidateResult(null, false);
        }

        @Nonnull
        private static CandidateResult pending() {
            return new CandidateResult(null, true);
        }
    }
}
