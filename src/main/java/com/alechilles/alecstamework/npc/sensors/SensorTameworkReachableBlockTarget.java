package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.Tamework;
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
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE = new NeedsResourcePathPreflightService();
    private static final ThreadLocal<NeedsResourceStandTargetSelector> STAND_TARGET_SELECTOR =
            ThreadLocal.withInitial(NeedsResourceStandTargetSelector::new);
    private static final long TARGET_CACHE_HIT_TTL_MS = 1_500L;
    private static final long TARGET_CACHE_MISS_TTL_MS = 3_000L;
    private static final long DIAGNOSTIC_REPEAT_INTERVAL_MS = 2_000L;
    private static final int MAX_SOURCE_CANDIDATES_PER_SCAN = 16;
    private static final double SCORE_EPSILON = 0.000001;
    private static final ConcurrentHashMap<String, DiagnosticSnapshot> LAST_DIAGNOSTIC_BY_NPC_AND_LABEL =
            new ConcurrentHashMap<>();

    @Nullable
    private final String blockSet;
    private final int blockSetIndex;
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
        this.blockSetIndex = resolveBlockSetIndex(this.blockSet);
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
                maybeLogDiagnostic(npcUuid, "cache_miss", "negative_cache", null);
                return false;
            }
            positionInfo.setTarget(cached.target().x, cached.target().y, cached.target().z);
            maybeLogDiagnostic(npcUuid, "target_found", "cache_hit", cached.target());
            return true;
        }

        TargetResolution resolution = resolveTarget(ref, role, store, npcUuid, nowMs);
        if (resolution.target() == null) {
            if (npcUuid != null && resolution.cacheMiss()) {
                cacheTarget(npcUuid, null, nowMs);
            }
            maybeLogDiagnostic(npcUuid, "target_missing", resolution.detail(), null);
            return false;
        }
        if (npcUuid != null) {
            cacheTarget(npcUuid, resolution.target(), nowMs);
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
        if (transform == null || transform.getPosition() == null || world == null || world.getChunkStore() == null) {
            return TargetResolution.miss(true, "motion_or_world_context_missing");
        }
        if (npcUuid == null) {
            return TargetResolution.miss(true, "npc_uuid_missing");
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return TargetResolution.miss(true, "chunk_store_missing");
        }

        Vector3d npcPosition = transform.getPosition();
        NeedsResourceStandTargetSelector.CandidateProjector projector =
                STAND_TARGET_SELECTOR.get().createProjector(role, store);
        if (projector == null) {
            return TargetResolution.miss(true, "projector_missing");
        }
        BlockSetMembership blockSetMembership = blockSetIndex == Integer.MIN_VALUE
                ? null : resolveBlockSetMembership();
        ScanDiagnostics diagnostics = isDiagnosticsEnabled() ? new ScanDiagnostics() : null;
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
                blockSetMembership,
                projector,
                PositionTargetRejectCache.hasRejectedTargetFor(npcUuid, label, nowMs)
                        ? target -> PositionTargetRejectCache.isRejected(npcUuid, label, target, nowMs)
                        : null,
                diagnostics
        );
        int searchRadius = Math.max(1, (int) Math.ceil(range));

        int checkedSources = 0;
        for (int horizontalRadius = 0; horizontalRadius <= searchRadius; horizontalRadius++) {
            RingResult ringResult = scanRing(context, horizontalRadius, checkedSources);
            checkedSources += ringResult.checkedSources();
            if (ringResult.target() != null) {
                return TargetResolution.hit(ringResult.target(), scanDetail(diagnostics, "source_ready"));
            }
            if (ringResult.deferred() || checkedSources >= MAX_SOURCE_CANDIDATES_PER_SCAN) {
                return TargetResolution.miss(false, scanDetail(diagnostics, "source_limit_deferred"));
            }
        }
        return TargetResolution.miss(true, scanDetail(diagnostics, "scan_exhausted"));
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
                    if (worldChunk == null
                            || !matchesConfiguredBlock(
                                    worldChunk,
                                    x,
                                    y,
                                    z,
                                    blockSetIndex,
                                    blockTypes,
                                    context.blockSetMembership()
                            )) {
                        continue;
                    }
                    if (context.diagnostics() != null) {
                        context.diagnostics().recordMatchingSource(x, y, z, worldChunk.getBlockType(x, y, z));
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
        return CandidateResult.pending();
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
    private static String scanDetail(@Nullable ScanDiagnostics diagnostics, @Nonnull String result) {
        return diagnostics == null ? result : diagnostics.summary(result);
    }

    private static void recordPreflight(@Nullable ScanDiagnostics diagnostics, @Nonnull String reason) {
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

    private void maybeLogDiagnostic(@Nullable UUID npcUuid,
                                    @Nonnull String result,
                                    @Nonnull String detail,
                                    @Nullable Vector3d target) {
        if (npcUuid == null || !isDiagnosticsEnabled() || !LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        String key = npcUuid + "|" + label;
        String signature = result + "|" + detail + "|" + formatTarget(target);
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
                formatTarget(target)
        ));
    }

    private static boolean isDiagnosticsEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsSeekDiagnosticsEnabled();
    }

    @Nonnull
    private static String formatTarget(@Nullable Vector3d target) {
        if (target == null
                || !Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            return "<none>";
        }
        return String.format(Locale.ROOT, "[%.2f,%.2f,%.2f]", target.x, target.y, target.z);
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
                               @Nullable BlockSetMembership blockSetMembership,
                               @Nonnull NeedsResourceStandTargetSelector.CandidateProjector projector,
                               @Nullable TargetRejector rejector,
                               @Nullable ScanDiagnostics diagnostics) {
    }

    private record TargetResolution(@Nullable Vector3d target, boolean cacheMiss, @Nonnull String detail) {
        @Nonnull
        private static TargetResolution hit(@Nonnull Vector3d target, @Nonnull String detail) {
            return new TargetResolution(target, true, detail);
        }

        @Nonnull
        private static TargetResolution miss(boolean cacheMiss, @Nonnull String detail) {
            return new TargetResolution(null, cacheMiss, detail);
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

    private static final class ScanDiagnostics {
        private int matchingSources;
        private int projectionFailures;
        private int rejectedTargets;
        @Nullable
        private String firstSource;
        @Nullable
        private String lastPreflightReason;

        private void recordMatchingSource(int x, int y, int z, @Nullable BlockType blockType) {
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

        private void recordProjectionRejected(@Nonnull String reason) {
            if ("target_rejected".equals(reason)) {
                rejectedTargets++;
                return;
            }
            projectionFailures++;
        }

        private void recordPreflight(@Nonnull String reason) {
            lastPreflightReason = reason;
        }

        @Nonnull
        private String summary(@Nonnull String result) {
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

    private record DiagnosticSnapshot(@Nonnull String signature, long loggedAtMs) {
    }

    @FunctionalInterface
    interface BlockSetMembership {
        boolean blockInSet(int blockSetIndex, int blockTypeIndex);
    }
}
