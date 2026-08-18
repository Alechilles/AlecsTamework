package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.items.FeedTroughWaterStateService;
import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Performs one bounded, world-thread water-source search.
 *
 * <p>The service returns source block coordinates, not mutable target vectors.
 * A request-local section cache resolves each fluid section once and checks
 * {@link FluidSection#isEmpty()} before reading any cell. Trough checks still
 * inspect block and state data when a fluid section is empty.
 */
public final class NeedsWaterTargetSearchService {
    private static final int MAX_CANDIDATES = NeedsResourceCandidates.MAX_CANDIDATES;
    private static final double SCORE_EPSILON = 0.000001;
    private static final double WATER_APPROACH_RADIUS = 1.0;
    private static final Map<Integer, Boolean> WATER_TROUGH_BLOCK_ID_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Searches loaded world chunks on the current world thread.
     *
     * <p>The store, reference, world, chunk, and fluid objects are used only
     * during this call. They are never copied into the immutable result.
     */
    @Nonnull
    public NeedsResourceCandidates.Snapshot search(@Nullable Store<EntityStore> store,
                                                   @Nullable Ref<EntityStore> npcRef,
                                                   @Nonnull WaterRequest request) {
        if (store == null || npcRef == null || !npcRef.isValid() || request == null) {
            return emptySnapshot(false);
        }
        World world = resolveWorld(store);
        if (world == null || world.getChunkStore() == null) {
            return emptySnapshot(false);
        }
        Store<ChunkStore> chunkStoreStore = world.getChunkStore().getStore();
        if (chunkStoreStore == null) {
            return emptySnapshot(false);
        }
        return search(request, new WorldSearchAccess(world.getChunkStore(), chunkStoreStore));
    }

    /**
     * Test seam for the traversal algorithm. The seam exposes only section and
     * cell values, so tests do not need to construct an ECS world.
     */
    @Nonnull
    NeedsResourceCandidates.Snapshot search(@Nonnull WaterRequest request,
                                             @Nonnull WaterSearchAccess access) {
        if (request == null || access == null || !request.hasValidRange()) {
            return emptySnapshot(false);
        }
        return scan(request, access);
    }

    private static NeedsResourceCandidates.Snapshot scan(@Nonnull WaterRequest request,
                                                         @Nonnull WaterSearchAccess access) {
        int blockX = floorBlock(request.originX());
        int blockY = floorBlock(request.originY());
        int blockZ = floorBlock(request.originZ());
        int searchRadius = request.searchRadius();
        double radiusSquared = request.radius() * request.radius();
        SectionCache sectionCache = new SectionCache(access);
        for (int horizontalRadius = 0; horizontalRadius <= searchRadius; horizontalRadius++) {
            CandidateBuffer candidates = new CandidateBuffer(request.maxCandidates());
            boolean foundSource = false;
            boolean foundSourceInConsumeRange = false;
            for (int yOffset = -request.verticalScanRadius();
                 yOffset <= request.verticalScanRadius();
                 yOffset++) {
                int y = blockY + yOffset;
                for (int x = blockX - horizontalRadius; x <= blockX + horizontalRadius; x++) {
                    for (int z = blockZ - horizontalRadius; z <= blockZ + horizontalRadius; z++) {
                        if (!isHorizontalRingCell(blockX, blockZ, x, z, horizontalRadius)) {
                            continue;
                        }
                        double dx = x - blockX;
                        double dz = z - blockZ;
                        double horizontalDistanceSquared = (dx * dx) + (dz * dz);
                        if (horizontalDistanceSquared > radiusSquared + SCORE_EPSILON) {
                            continue;
                        }
                        if (!isConsumableSource(access, sectionCache, x, y, z)) {
                            continue;
                        }
                        foundSource = true;
                        if (horizontalDistanceSquared <= request.consumeRadius() * request.consumeRadius()
                                + SCORE_EPSILON) {
                            foundSourceInConsumeRange = true;
                        }
                        candidates.add(x, y, z, request.originX(), request.originY(), request.originZ());
                    }
                }
            }
            if (foundSource) {
                return candidates.toSnapshot(
                        true,
                        foundSourceInConsumeRange,
                        cacheTtlMs(candidates.count() > 0, true)
                );
            }
        }
        return emptySnapshot(false);
    }

    private static boolean isConsumableSource(@Nonnull WaterSearchAccess access,
                                              @Nonnull SectionCache sectionCache,
                                              int x,
                                              int y,
                                              int z) {
        SectionState section = sectionCache.get(x, y, z);
        if (section.section() != null && !section.empty()
                && access.fluidId(section.section(), x, y, z) != 0) {
            return true;
        }
        return access.hasConsumableTrough(x, y, z);
    }

    private static boolean isHorizontalRingCell(int originX,
                                                int originZ,
                                                int x,
                                                int z,
                                                int horizontalRadius) {
        return horizontalRadius == 0
                || x == originX - horizontalRadius
                || x == originX + horizontalRadius
                || z == originZ - horizontalRadius
                || z == originZ + horizontalRadius;
    }

    private static long cacheTtlMs(boolean hasTarget, boolean foundSource) {
        long baseTtlMs = NeedsResourceSearchCachePolicy.baseTtlMs(hasTarget, foundSource);
        return TameworkRuntimePressureService.getInstance().scaleTtlMs(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                baseTtlMs,
                System.currentTimeMillis()
        );
    }

    @Nonnull
    private static NeedsResourceCandidates.Snapshot emptySnapshot(boolean foundSource) {
        return new NeedsResourceCandidates.Snapshot(
                List.of(),
                foundSource,
                false,
                cacheTtlMs(false, foundSource)
        );
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        return store.getExternalData().getWorld();
    }

    private static int floorBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static boolean isWaterTroughBlock(@Nonnull WorldChunk chunk,
                                              @Nullable Store<ChunkStore> chunkStore,
                                              int x,
                                              int y,
                                              int z) {
        int blockId = chunk.getBlock(x, y, z);
        if (blockId == 0 || !WATER_TROUGH_BLOCK_ID_CACHE.computeIfAbsent(
                blockId,
                NeedsWaterTargetSearchService::resolveWaterTroughBlock
        )) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        return FeedTroughWaterStateService.hasConsumableWater(
                chunk,
                chunkStore,
                x,
                y,
                z,
                blockType
        );
    }

    private static boolean resolveWaterTroughBlock(int blockId) {
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        return FeedTroughWaterStateService.isWaterTroughBlockType(blockType);
    }

    /**
     * Value-only water search input. The vector constructor copies its three
     * coordinates; no vector is retained by a request.
     */
    public record WaterRequest(double originX,
                               double originY,
                               double originZ,
                               double radius,
                               int verticalScanRadius,
                               double consumeRadius,
                               int maxCandidates) {
        public WaterRequest {
            verticalScanRadius = Math.max(0, verticalScanRadius);
            maxCandidates = Math.max(0, Math.min(MAX_CANDIDATES, maxCandidates));
        }

        public WaterRequest(double originX,
                            double originY,
                            double originZ,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius) {
            this(originX, originY, originZ, radius, verticalScanRadius, consumeRadius, MAX_CANDIDATES);
        }

        public WaterRequest(@Nonnull Vector3d origin,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius,
                            int maxCandidates) {
            this(origin.x, origin.y, origin.z, radius, verticalScanRadius, consumeRadius, maxCandidates);
        }

        public WaterRequest(@Nonnull Vector3d origin,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius) {
            this(origin, radius, verticalScanRadius, consumeRadius, MAX_CANDIDATES);
        }

        boolean hasValidRange() {
            return Double.isFinite(originX)
                    && Double.isFinite(originY)
                    && Double.isFinite(originZ)
                    && Double.isFinite(radius)
                    && radius > 0.0
                    && Double.isFinite(consumeRadius)
                    && consumeRadius >= 0.0;
        }

        int searchRadius() {
            return Math.max(1, (int) Math.ceil(radius));
        }
    }

    /** A fluid section view used by the request-local traversal seam. */
    interface FluidSectionView {
    }

    /**
     * Supplies loaded water and trough values to one synchronous search.
     * Implementations must not be retained after the search returns.
     */
    interface WaterSearchAccess {
        @Nullable
        FluidSectionView sectionAt(int x, int y, int z);

        boolean isEmpty(@Nonnull FluidSectionView section);

        int fluidId(@Nonnull FluidSectionView section, int x, int y, int z);

        boolean hasConsumableTrough(int x, int y, int z);
    }

    private static final class WorldSearchAccess implements WaterSearchAccess {
        private final ChunkStore chunkStore;
        private final Store<ChunkStore> chunkStoreStore;
        private final Long2ObjectOpenHashMap<WorldChunk> chunkCache = new Long2ObjectOpenHashMap<>();

        private WorldSearchAccess(@Nonnull ChunkStore chunkStore,
                                  @Nonnull Store<ChunkStore> chunkStoreStore) {
            this.chunkStore = chunkStore;
            this.chunkStoreStore = chunkStoreStore;
        }

        @Override
        public FluidSectionView sectionAt(int x, int y, int z) {
            WorldChunk chunk = resolveChunk(x, z);
            if (chunk == null) {
                return null;
            }
            Ref<ChunkStore> chunkRef = chunk.getReference();
            if (chunkRef == null || !chunkRef.isValid()) {
                return null;
            }
            ChunkColumn column = chunkStoreStore.getComponent(chunkRef, ChunkColumn.getComponentType());
            if (column == null) {
                return null;
            }
            int sectionY = ChunkUtil.chunkCoordinate(y);
            Ref<ChunkStore> sectionRef = column.getSection(sectionY);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            FluidSection section = chunkStoreStore.getComponent(sectionRef, FluidSection.getComponentType());
            return section == null ? null : new HytaleFluidSection(section);
        }

        @Override
        public boolean isEmpty(@Nonnull FluidSectionView section) {
            return ((HytaleFluidSection) section).section.isEmpty();
        }

        @Override
        public int fluidId(@Nonnull FluidSectionView section, int x, int y, int z) {
            return ((HytaleFluidSection) section).section.getFluidId(x, y, z);
        }

        @Override
        public boolean hasConsumableTrough(int x, int y, int z) {
            WorldChunk chunk = resolveChunk(x, z);
            return chunk != null && isWaterTroughBlock(chunk, chunkStoreStore, x, y, z);
        }

        @Nullable
        private WorldChunk resolveChunk(int x, int z) {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            if (chunkCache.containsKey(chunkIndex)) {
                return chunkCache.get(chunkIndex);
            }
            Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
            if (chunkRef == null || !chunkRef.isValid()) {
                chunkCache.put(chunkIndex, null);
                return null;
            }
            WorldChunk chunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
            chunkCache.put(chunkIndex, chunk);
            return chunk;
        }
    }

    private record HytaleFluidSection(@Nonnull FluidSection section) implements FluidSectionView {
    }

    private static final class SectionCache {
        private static final SectionState MISSING = new SectionState(null, true);
        private final WaterSearchAccess access;
        private final Long2ObjectOpenHashMap<SectionStates> byChunk = new Long2ObjectOpenHashMap<>();

        private SectionCache(@Nonnull WaterSearchAccess access) {
            this.access = access;
        }

        @Nonnull
        private SectionState get(int x, int y, int z) {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            int sectionY = ChunkUtil.chunkCoordinate(y);
            if (sectionY < 0 || sectionY >= ChunkUtil.HEIGHT_SECTIONS) {
                return MISSING;
            }
            SectionStates sections = byChunk.get(chunkIndex);
            if (sections == null) {
                sections = new SectionStates();
                byChunk.put(chunkIndex, sections);
            }
            SectionState state = sections.states[sectionY];
            if (state != null) {
                return state;
            }
            FluidSectionView section = access.sectionAt(x, y, z);
            state = section == null ? MISSING : new SectionState(section, access.isEmpty(section));
            sections.states[sectionY] = state;
            return state;
        }
    }

    private static final class SectionStates {
        private final SectionState[] states = new SectionState[ChunkUtil.HEIGHT_SECTIONS];
    }

    private record SectionState(@Nullable FluidSectionView section, boolean empty) {
    }

    private static final class CandidateBuffer {
        private final int maxCandidates;
        private final int[] x;
        private final int[] y;
        private final int[] z;
        private final double[] distanceSquared;
        private int count;

        private CandidateBuffer(int maxCandidates) {
            this.maxCandidates = maxCandidates;
            this.x = new int[maxCandidates];
            this.y = new int[maxCandidates];
            this.z = new int[maxCandidates];
            this.distanceSquared = new double[maxCandidates];
        }

        private void add(int candidateX,
                         int candidateY,
                         int candidateZ,
                         double originX,
                         double originY,
                         double originZ) {
            for (int index = 0; index < count; index++) {
                if (x[index] == candidateX && y[index] == candidateY && z[index] == candidateZ) {
                    return;
                }
            }
            if (maxCandidates == 0) {
                return;
            }
            double dx = candidateX + 0.5 - originX;
            double dy = candidateY + 0.5 - originY;
            double dz = candidateZ + 0.5 - originZ;
            double score = (dx * dx) + (dy * dy) + (dz * dz);
            if (!Double.isFinite(score)) {
                return;
            }
            int insertAt = 0;
            while (insertAt < count && !comesBefore(score, candidateX, candidateY, candidateZ, insertAt)) {
                insertAt++;
            }
            if (insertAt >= maxCandidates) {
                return;
            }
            int newCount = Math.min(maxCandidates, count + 1);
            for (int index = newCount - 1; index > insertAt; index--) {
                x[index] = x[index - 1];
                y[index] = y[index - 1];
                z[index] = z[index - 1];
                distanceSquared[index] = distanceSquared[index - 1];
            }
            x[insertAt] = candidateX;
            y[insertAt] = candidateY;
            z[insertAt] = candidateZ;
            distanceSquared[insertAt] = score;
            count = newCount;
        }

        private boolean comesBefore(double score, int candidateX, int candidateY, int candidateZ, int index) {
            if (score + SCORE_EPSILON < distanceSquared[index]) {
                return true;
            }
            if (Math.abs(score - distanceSquared[index]) > SCORE_EPSILON) {
                return false;
            }
            if (candidateX != x[index]) {
                return candidateX < x[index];
            }
            if (candidateY != y[index]) {
                return candidateY < y[index];
            }
            return candidateZ < z[index];
        }

        @Nonnull
        private NeedsResourceCandidates.Snapshot toSnapshot(boolean foundSource,
                                                             boolean foundSourceInConsumeRange,
                                                             long ttlMs) {
            List<NeedsResourceCandidates.Candidate> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                result.add(new NeedsResourceCandidates.Candidate(
                        x[index],
                        y[index],
                        z[index],
                        WATER_APPROACH_RADIUS
                ));
            }
            return new NeedsResourceCandidates.Snapshot(
                    result,
                    foundSource,
                    foundSourceInConsumeRange,
                    ttlMs
            );
        }

        private int count() {
            return count;
        }
    }
}
