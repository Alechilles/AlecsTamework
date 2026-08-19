package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService.TargetRejector;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourceStandTargetSelector;
import com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache;
import com.alechilles.alecstamework.npc.progression.ReachableBlockSourceCache;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkReachableBlockTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blockset.config.BlockSet;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Generic sensor that exposes a path-preflighted approach target for nearby matching blocks.
 */
public final class SensorTameworkReachableBlockTarget extends TameworkSensorBase {
    public static final double DEFAULT_APPROACH_RADIUS = NeedsResourceStandTargetSelector.MIN_ADJACENT_DISTANCE;
    private static final Logger LOGGER = Logger.getLogger(SensorTameworkReachableBlockTarget.class.getName());
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE =
            NeedsResourcePathPreflightService.shared();
    private static final ReachableBlockSourceCache SOURCE_CACHE =
            ReachableBlockSourceCache.shared();
    private static final ThreadLocal<NeedsResourceStandTargetSelector> STAND_TARGET_SELECTOR =
            ThreadLocal.withInitial(NeedsResourceStandTargetSelector::new);
    private static final long DIAGNOSTIC_REPEAT_INTERVAL_MS = 2_000L;
    private static final int MAX_SOURCE_CANDIDATES_PER_SCAN = ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES;
    private static final double SCORE_EPSILON = 0.000001;
    private static final ConcurrentHashMap<String, DiagnosticSnapshot> LAST_DIAGNOSTIC_BY_NPC_AND_LABEL =
            new ConcurrentHashMap<>();

    @Nullable
    private final String blockSet;
    private final int blockSetIndex;
    @Nonnull
    private final Set<String> blockTypes;
    @Nonnull
    private final ReachableBlockSourceCache.SensorConfiguration sourceConfiguration;
    @Nonnull
    private final String label;
    private final double range;
    private final int verticalRadius;
    private final double approachRadius;
    private final TameworkTargetPositionInfo positionInfo = new TameworkTargetPositionInfo();
    private final TameworkTargetPositionInfoProvider infoProvider =
            new TameworkTargetPositionInfoProvider(null, positionInfo);
    private final ReachableBlockTargetStateCache targetStateCache = new ReachableBlockTargetStateCache();

    public SensorTameworkReachableBlockTarget(@Nonnull BuilderSensorTameworkReachableBlockTarget builder,
                                              @Nonnull BuilderSupport support) {
        super(builder);
        this.blockSet = sanitizeNullableId(builder.getBlockSet(support));
        this.blockSetIndex = resolveBlockSetIndex(this.blockSet);
        this.blockTypes = sanitizeIdSet(builder.getBlockTypes(support));
        this.sourceConfiguration = new ReachableBlockSourceCache.SensorConfiguration(
                this.blockSet,
                List.copyOf(this.blockTypes)
        );
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
        ReachableBlockTargetStateCache.CachedTarget cached = npcUuid != null
                ? targetStateCache.get(npcUuid, nowMs)
                : null;
        Vector3d cachedTarget = cached == null ? null : cached.target();
        if (cachedTarget != null
                && PositionTargetRejectCache.isRejected(npcUuid, label, cachedTarget, nowMs)) {
            targetStateCache.remove(npcUuid, cached);
            cached = null;
            cachedTarget = null;
        }
        if (cached != null) {
            if (cachedTarget == null) {
                maybeLogDiagnostic(npcUuid, "cache_miss", "negative_cache", null);
                return false;
            }
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null
                    || !isCachedTargetUsable(
                            cached.source(),
                            cachedTarget,
                            transform.getPosition(),
                            range,
                            verticalRadius
                    )) {
                targetStateCache.remove(npcUuid, cached);
                cached = null;
            } else if (cached.state() == ReachableBlockTargetStateCache.State.PENDING) {
                TargetResolution resumed = resumePendingTarget(
                        ref,
                        role,
                        store,
                        npcUuid,
                        cachedTarget,
                        cached.source(),
                        nowMs
                );
                if (resumed.state() == ReachableBlockTargetStateCache.State.VALIDATED) {
                    targetStateCache.put(
                            npcUuid,
                            ReachableBlockTargetStateCache.State.VALIDATED,
                            resumed.target(),
                            resumed.source(),
                            nowMs
                    );
                    positionInfo.setTarget(resumed.target().x, resumed.target().y, resumed.target().z);
                    maybeLogDiagnostic(npcUuid, "target_found", resumed.detail(), resumed.target());
                    return true;
                }
                if (resumed.state() == ReachableBlockTargetStateCache.State.PENDING) {
                    targetStateCache.put(
                            npcUuid,
                            ReachableBlockTargetStateCache.State.PENDING,
                            resumed.target(),
                            resumed.source(),
                            nowMs
                    );
                    maybeLogDiagnostic(npcUuid, "target_pending", resumed.detail(), resumed.target());
                    return false;
                }
                if (resumed.state() == ReachableBlockTargetStateCache.State.RETRY) {
                    targetStateCache.remove(npcUuid, cached);
                    cached = null;
                } else {
                    if (resumed.cacheMiss()) {
                        targetStateCache.put(
                                npcUuid,
                                ReachableBlockTargetStateCache.State.MISS,
                                null,
                                null,
                                nowMs
                        );
                    }
                    maybeLogDiagnostic(npcUuid, "target_missing", resumed.detail(), resumed.target());
                    return false;
                }
            } else {
                positionInfo.setTarget(cachedTarget.x, cachedTarget.y, cachedTarget.z);
                maybeLogDiagnostic(npcUuid, "target_found", "cache_hit", cachedTarget);
                return true;
            }
        }

        TargetResolution resolution = resolveTarget(ref, role, store, npcUuid, nowMs);
        if (resolution.state() == ReachableBlockTargetStateCache.State.MISS) {
            if (npcUuid != null && resolution.cacheMiss()) {
                targetStateCache.put(
                        npcUuid,
                        ReachableBlockTargetStateCache.State.MISS,
                        null,
                        null,
                        nowMs
                );
            }
            maybeLogDiagnostic(npcUuid, "target_missing", resolution.detail(), null);
            return false;
        }
        if (resolution.state() == ReachableBlockTargetStateCache.State.PENDING) {
            if (npcUuid != null) {
                targetStateCache.put(
                        npcUuid,
                        ReachableBlockTargetStateCache.State.PENDING,
                        resolution.target(),
                        resolution.source(),
                        nowMs
                );
            }
            maybeLogDiagnostic(npcUuid, "target_pending", resolution.detail(), resolution.target());
            return false;
        }
        if (resolution.state() != ReachableBlockTargetStateCache.State.VALIDATED
                || resolution.target() == null) {
            maybeLogDiagnostic(npcUuid, "target_missing", resolution.detail(), null);
            return false;
        }
        if (npcUuid != null) {
            targetStateCache.put(
                    npcUuid,
                    ReachableBlockTargetStateCache.State.VALIDATED,
                    resolution.target(),
                    resolution.source(),
                    nowMs
            );
        }
        positionInfo.setTarget(resolution.target().x, resolution.target().y, resolution.target().z);
        maybeLogDiagnostic(npcUuid, "target_found", resolution.detail(), resolution.target());
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
        if (transform == null || transform.getPosition() == null || world == null) {
            return TargetResolution.miss(true, "motion_or_world_context_missing");
        }
        if (npcUuid == null) {
            return TargetResolution.miss(true, "npc_uuid_missing");
        }
        Vector3d npcPosition = transform.getPosition();
        ReachableBlockSourceCache.SourceKey sourceKey = ReachableBlockSourceCache.keyFor(
                world.getName(),
                (int) Math.floor(npcPosition.x),
                (int) Math.floor(npcPosition.y),
                (int) Math.floor(npcPosition.z),
                sourceConfiguration,
                range,
                verticalRadius
        );
        ReachableBlockSourceCache.Lookup lookup = SOURCE_CACHE.lookup(store, sourceKey, nowMs);
        ReachableBlockScanDiagnostics diagnostics = null;
        ReachableBlockSourceCache.Snapshot snapshot;
        if (lookup.status() == ReachableBlockSourceCache.Lookup.Status.DEFERRED) {
            return TargetResolution.deferred("source_scan_deferred");
        }
        if (lookup.status() == ReachableBlockSourceCache.Lookup.Status.ABSENT) {
            ReachableBlockSourceCache.ColdScanStart start = SOURCE_CACHE.startColdScan(
                    store,
                    sourceKey,
                    nowMs
            );
            if (start.status() == ReachableBlockSourceCache.ColdScanStart.Status.DEFERRED) {
                return TargetResolution.deferred("source_scan_deferred");
            }
            if (start.status() == ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED) {
                diagnostics = isDiagnosticsEnabled() ? new ReachableBlockScanDiagnostics() : null;
                ReachableBlockSourceCache.Snapshot scanned = ReachableBlockSourceCache.Snapshot.empty();
                try {
                    ChunkStore chunkStore = world.getChunkStore();
                    Store<ChunkStore> chunkStoreStore = chunkStore == null ? null : chunkStore.getStore();
                    if (chunkStore != null && chunkStoreStore != null) {
                        ScanContext context = new ScanContext(
                                ref,
                                role,
                                store,
                                npcUuid,
                                nowMs,
                                chunkStore,
                                chunkStoreStore,
                                new HashMap<>(),
                                blockSetIndex == Integer.MIN_VALUE ? null : resolveBlockSetMembership(),
                                diagnostics
                        );
                        scanned = scanSources(context, start.permit().searchBounds(), sourceKey);
                    }
                } catch (RuntimeException ignored) {
                    scanned = ReachableBlockSourceCache.Snapshot.empty();
                }
                ReachableBlockSourceCache.Lookup completed = SOURCE_CACHE.completeColdScan(
                        start.permit(),
                        scanned,
                        nowMs
                );
                if (completed.status() == ReachableBlockSourceCache.Lookup.Status.DEFERRED) {
                    return TargetResolution.deferred(scanDetail(diagnostics, "source_scan_cleared"));
                }
                snapshot = completed.snapshot();
            } else {
                snapshot = start.snapshot();
            }
        } else {
            snapshot = lookup.snapshot();
        }
        if (snapshot == null || snapshot.coordinates().isEmpty()) {
            return TargetResolution.miss(true, scanDetail(diagnostics, "source_scan_miss"));
        }
        NeedsResourceStandTargetSelector.CandidateProjector projector =
                STAND_TARGET_SELECTOR.get().createProjector(role, store);
        if (projector == null) {
            return TargetResolution.miss(true, "projector_missing");
        }
        CandidateContext candidateContext = new CandidateContext(
                ref,
                role,
                store,
                npcUuid,
                nowMs,
                npcPosition,
                projector,
                PositionTargetRejectCache.hasRejectedTargetFor(npcUuid, label, nowMs)
                        ? target -> PositionTargetRejectCache.isRejected(npcUuid, label, target, nowMs)
                        : null,
                diagnostics
        );
        ReachableBlockTargetCandidateSelector.Selection selection =
                ReachableBlockTargetCandidateSelector.select(
                        snapshot,
                        source -> {
                            if (!isSourceInRange(source, npcPosition, range, verticalRadius)) {
                                return CandidateResult.empty();
                            }
                            return resolveCandidate(candidateContext, source.x(), source.y(), source.z());
                        }
                );
        if (selection.deferred()) {
            return TargetResolution.pending(
                    selection.target(),
                    selection.source(),
                    scanDetail(diagnostics, "path_preflight_pending")
            );
        }
        if (selection.validated()) {
            return TargetResolution.hit(
                    selection.target(),
                    selection.source(),
                    scanDetail(diagnostics, "source_ready")
            );
        }
        boolean candidateLimitReached = snapshot.coordinates().size() >= MAX_SOURCE_CANDIDATES_PER_SCAN;
        return TargetResolution.miss(
                !candidateLimitReached,
                scanDetail(diagnostics, candidateLimitReached
                        ? "source_limit_deferred"
                        : "source_candidates_rejected")
        );
    }

    @Nonnull
    private ReachableBlockSourceCache.Snapshot scanSources(
            @Nonnull ScanContext context,
            @Nonnull ReachableBlockSourceCache.SearchBounds bounds,
            @Nonnull ReachableBlockSourceCache.SourceKey sourceKey) {
        ReachableBlockSourceCache.AuthoritySourceSelector sources =
                new ReachableBlockSourceCache.AuthoritySourceSelector(
                        bounds,
                        sourceKey.authorityOriginX(),
                        sourceKey.authorityOriginY(),
                        sourceKey.authorityOriginZ(),
                        range,
                        verticalRadius
                );
        for (int y = bounds.minY(); ; y++) {
            for (int x = bounds.minX(); ; x++) {
                for (int z = bounds.minZ(); ; z++) {
                    WorldChunk worldChunk = resolveWorldChunk(
                            context.chunkStore(),
                            context.chunkStoreStore(),
                            x,
                            z,
                            context.chunkCache()
                    );
                    if (worldChunk != null
                            && matchesConfiguredBlock(
                                    worldChunk,
                                    x,
                                    y,
                                    z,
                                    blockSetIndex,
                                    blockTypes,
                                    context.blockSetMembership()
                            )) {
                        if (context.diagnostics() != null) {
                            context.diagnostics().recordMatchingSource(x, y, z, worldChunk.getBlockType(x, y, z));
                        }
                        sources.offer(new ReachableBlockSourceCache.SourceCoordinate(x, y, z));
                    }
                    if (z == bounds.maxZ()) {
                        break;
                    }
                }
                if (x == bounds.maxX()) {
                    break;
                }
            }
            if (y == bounds.maxY()) {
                break;
            }
        }
        return new ReachableBlockSourceCache.Snapshot(sources.finish());
    }

    @Nonnull
    private TargetResolution resumePendingTarget(@Nonnull Ref<EntityStore> ref,
                                                 @Nonnull Role role,
                                                 @Nonnull Store<EntityStore> store,
                                                 @Nonnull UUID npcUuid,
                                                 @Nonnull Vector3d target,
                                                 @Nullable ReachableBlockSourceCache.SourceCoordinate source,
                                                 long nowMs) {
        PathPreflightResult preflight = PATH_PREFLIGHT_SERVICE.preflight(
                ref,
                role,
                store,
                npcUuid,
                label,
                target,
                nowMs
        );
        if (preflight.ready()) {
            return TargetResolution.hit(target, source, "pending_path_ready");
        }
        if (preflight.noPath()) {
            World world = resolveWorld(store);
            PATH_PREFLIGHT_SERVICE.invalidateTarget(
                    npcUuid,
                    world == null ? null : world.getName(),
                    label,
                    target
            );
            PositionTargetRejectCache.reject(
                    npcUuid,
                    label,
                    target,
                    PositionTargetRejectCache.DEFAULT_TTL_SECONDS,
                    nowMs
            );
            return TargetResolution.retry(source, "pending_path_no_path");
        }
        return TargetResolution.pending(target, source, "pending_path_computing");
    }

    @Nonnull
    private CandidateResult resolveCandidate(@Nonnull CandidateContext context, int x, int y, int z) {
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
            if (context.diagnostics() != null) {
                context.diagnostics().recordProjectionRejected(
                        target == null ? "projection_failed" : "target_rejected"
                );
            }
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
            recordPreflight(context.diagnostics(), preflight.reason());
            return CandidateResult.hit(target);
        }
        if (preflight.noPath()) {
            World world = resolveWorld(context.store());
            PATH_PREFLIGHT_SERVICE.invalidateTarget(
                    context.npcUuid(),
                    world == null ? null : world.getName(),
                    label,
                    target
            );
            PositionTargetRejectCache.reject(
                    context.npcUuid(),
                    label,
                    target,
                    PositionTargetRejectCache.DEFAULT_TTL_SECONDS,
                    context.nowMs()
            );
            recordPreflight(context.diagnostics(), preflight.reason());
            return CandidateResult.empty();
        }
        recordPreflight(context.diagnostics(), preflight.reason());
        return CandidateResult.pending(target);
    }

    static boolean matchesConfiguredBlock(@Nonnull WorldChunk worldChunk,
                                          int x,
                                          int y,
                                          int z,
                                          int blockSetIndex,
                                          @Nonnull Set<String> blockTypes,
                                          @Nullable BlockSetMembership blockSetMembership) {
        if (!blockTypes.isEmpty()
                && matchesExactBlockType(worldChunk.getBlockType(x, y, z), blockTypes)) {
            return true;
        }
        if (blockSetIndex == Integer.MIN_VALUE || blockSetMembership == null) {
            return false;
        }
        return blockSetMembership.blockInSet(blockSetIndex, worldChunk.getBlock(x, y, z));
    }

    static boolean matchesConfiguredBlock(@Nullable BlockType blockType,
                                          int blockId,
                                          @Nullable String blockSet,
                                          @Nonnull Set<String> blockTypes) {
        return matchesConfiguredBlock(
                blockType,
                blockId,
                resolveBlockSetIndex(sanitizeNullableId(blockSet)),
                blockTypes,
                resolveBlockSetMembership()
        );
    }

    static boolean matchesConfiguredBlock(@Nullable BlockType blockType,
                                          int blockId,
                                          int blockSetIndex,
                                          @Nonnull Set<String> blockTypes,
                                          @Nullable BlockSetMembership blockSetMembership) {
        if (blockType == null) {
            return false;
        }
        if (matchesExactBlockType(blockType, blockTypes)) {
            return true;
        }
        if (blockSetIndex == Integer.MIN_VALUE || blockSetMembership == null) {
            return false;
        }
        return blockSetMembership.blockInSet(blockSetIndex, blockId);
    }

    private static boolean matchesExactBlockType(@Nullable BlockType blockType,
                                                 @Nonnull Set<String> blockTypes) {
        if (blockType == null || blockTypes.isEmpty()) {
            return false;
        }
        String id = blockType.getId();
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (blockTypes.contains(id)) {
            return true;
        }
        if (!requiresIdNormalization(id)) {
            return false;
        }
        String normalized = sanitizeNullableId(id);
        return normalized != null && blockTypes.contains(normalized);
    }

    private static boolean requiresIdNormalization(@Nonnull String id) {
        int lastIndex = id.length() - 1;
        if (Character.isWhitespace(id.charAt(0)) || Character.isWhitespace(id.charAt(lastIndex))) {
            return true;
        }
        for (int i = 0; i <= lastIndex; i++) {
            if (Character.isUpperCase(id.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String scanDetail(@Nullable ReachableBlockScanDiagnostics diagnostics,
                                     @Nonnull String result) {
        return diagnostics == null ? result : diagnostics.summary(result);
    }

    private static void recordPreflight(@Nullable ReachableBlockScanDiagnostics diagnostics,
                                        @Nonnull String reason) {
        if (diagnostics != null) {
            diagnostics.recordPreflight(reason);
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
        return blockSetIndex != Integer.MIN_VALUE || !blockTypes.isEmpty();
    }

    private static int resolveBlockSetIndex(@Nullable String blockSet) {
        if (blockSet == null) {
            return Integer.MIN_VALUE;
        }
        return BlockSet.getAssetMap().getIndex(blockSet);
    }

    @Nullable
    private static BlockSetMembership resolveBlockSetMembership() {
        BlockSetModule module = BlockSetModule.getInstance();
        return module == null ? null : module::blockInSet;
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

    static boolean isSourceInRange(@Nonnull ReachableBlockSourceCache.SourceCoordinate source,
                                   @Nullable Vector3d npcPosition,
                                   double horizontalRange,
                                   int verticalRadius) {
        if (source == null
                || npcPosition == null
                || !Double.isFinite(npcPosition.x)
                || !Double.isFinite(npcPosition.y)
                || !Double.isFinite(npcPosition.z)
                || !Double.isFinite(horizontalRange)
                || horizontalRange <= 0.0) {
            return false;
        }
        return ReachableBlockSourceCache.isSourceInRangeForAuthority(
                source,
                (int) Math.floor(npcPosition.x),
                (int) Math.floor(npcPosition.y),
                (int) Math.floor(npcPosition.z),
                horizontalRange,
                verticalRadius
        );
    }

    static boolean isCachedTargetUsable(
            @Nullable ReachableBlockSourceCache.SourceCoordinate source,
            @Nullable Vector3d target,
            @Nullable Vector3d npcPosition,
            double horizontalRange,
            int verticalRadius) {
        return isSourceInRange(source, npcPosition, horizontalRange, verticalRadius)
                && isTargetInRange(npcPosition, target, horizontalRange, verticalRadius);
    }

    private static boolean isTargetInRange(@Nullable Vector3d npcPosition,
                                           @Nullable Vector3d target,
                                           double horizontalRange,
                                           int verticalRadius) {
        if (npcPosition == null
                || target == null
                || !Double.isFinite(npcPosition.x)
                || !Double.isFinite(npcPosition.y)
                || !Double.isFinite(npcPosition.z)
                || !Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)
                || !Double.isFinite(horizontalRange)
                || horizontalRange <= 0.0) {
            return false;
        }
        double dx = target.x - npcPosition.x;
        double dz = target.z - npcPosition.z;
        if ((dx * dx) + (dz * dz) > (horizontalRange * horizontalRange) + SCORE_EPSILON) {
            return false;
        }
        return Math.abs(Math.floor(target.y) - Math.floor(npcPosition.y))
                <= Math.max(0, verticalRadius);
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
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    static int maxSourceCandidatesPerScanForTests() {
        return MAX_SOURCE_CANDIDATES_PER_SCAN;
    }

    private void maybeLogDiagnostic(@Nullable UUID npcUuid,
                                    @Nonnull String result,
                                    @Nonnull String detail,
                                    @Nullable Vector3d target) {
        if (npcUuid == null || !isDiagnosticsEnabled() || !LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        String key = npcUuid + "|" + label;
        String signature = result + "|" + detail + "|"
                + ReachableBlockTargetStateCache.formatTarget(target);
        DiagnosticSnapshot previous = LAST_DIAGNOSTIC_BY_NPC_AND_LABEL.get(key);
        if (previous != null
                && previous.signature().equals(signature)
                && nowMs < previous.loggedAtMs() + DIAGNOSTIC_REPEAT_INTERVAL_MS) {
            return;
        }
        LAST_DIAGNOSTIC_BY_NPC_AND_LABEL.put(key, new DiagnosticSnapshot(signature, nowMs));
        LOGGER.log(Level.INFO, String.format(
                Locale.ROOT,
                "Reachable block target probe: npc=%s label=%s result=%s detail=%s blockSet=%s blockTypes=%d range=%.2f verticalRadius=%d target=%s",
                npcUuid,
                label,
                result,
                detail,
                blockSet == null ? "<none>" : blockSet,
                blockTypes.size(),
                range,
                verticalRadius,
                ReachableBlockTargetStateCache.formatTarget(target)
        ));
    }

    private static boolean isDiagnosticsEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsSeekDiagnosticsEnabled();
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

/** Applies bounded shared candidates and skips one invalidated source during retry. */
final class ReachableBlockTargetCandidateSelector {
    private ReachableBlockTargetCandidateSelector() {
    }

    @Nonnull
    static Selection select(
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

/** Collects lazy, per-probe diagnostics without retaining live ECS state. */
final class ReachableBlockScanDiagnostics {
    private int matchingSources;
    private int projectionFailures;
    private int rejectedTargets;
    @Nullable
    private String firstSource;
    @Nullable
    private String lastPreflightReason;

    void recordMatchingSource(int x, int y, int z, @Nullable BlockType blockType) {
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
