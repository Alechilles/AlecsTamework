package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.items.FeedTroughContainerCompat;
import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Performs one bounded, world-thread food-container search.
 *
 * <p>Only immutable block-coordinate candidates leave this service. Chunk and
 * container objects remain local to the synchronous search request.
 */
public final class NeedsFoodTargetSearchService {
    private static final double SCORE_EPSILON = 0.000001;
    private static final double DEFAULT_APPROACH_RADIUS = 2.0;
    private static final int MAX_CANDIDATES = NeedsResourceCandidates.MAX_CANDIDATES;

    /**
     * Searches loaded world chunks on the current world thread.
     */
    @Nonnull
    public NeedsResourceCandidates.Snapshot search(@Nullable Store<EntityStore> store,
                                                   @Nullable Ref<EntityStore> npcRef,
                                                   @Nonnull FoodRequest request) {
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
     * Test seam for the traversal algorithm. It passes canonical item IDs to
     * each source check and does not require an ECS world fixture.
     */
    @Nonnull
    NeedsResourceCandidates.Snapshot search(@Nonnull FoodRequest request,
                                            @Nonnull FoodSearchAccess access) {
        if (request == null || access == null || !request.hasValidRange() || request.itemIds().isEmpty()) {
            return emptySnapshot(false);
        }
        return scan(request, access);
    }

    private static NeedsResourceCandidates.Snapshot scan(@Nonnull FoodRequest request,
                                                         @Nonnull FoodSearchAccess access) {
        int blockX = floorBlock(request.originX());
        int blockY = floorBlock(request.originY());
        int blockZ = floorBlock(request.originZ());
        double radiusSquared = request.radius() * request.radius();
        for (int horizontalRadius = 0; horizontalRadius <= request.searchRadius(); horizontalRadius++) {
            CandidateBuffer candidates = new CandidateBuffer(request.maxCandidates(), request.approachRadius());
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
                        if (!access.hasAllowedFood(x, y, z, request.itemIds())) {
                            continue;
                        }
                        foundSource = true;
                        if (request.consumeRadius() > SCORE_EPSILON
                                && horizontalDistanceSquared <= request.consumeRadius() * request.consumeRadius()
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

    private static boolean containsAllowedFood(@Nullable ItemContainer container,
                                               @Nonnull List<String> allowedIds) {
        if (container == null || allowedIds.isEmpty()) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            String trimmedItemId = itemId.trim();
            for (String allowedId : allowedIds) {
                if (allowedId.equalsIgnoreCase(trimmedItemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Value-only food search input. Item IDs are copied, normalized, and
     * sorted once at request construction.
     */
    public record FoodRequest(double originX,
                              double originY,
                              double originZ,
                              double radius,
                              int verticalScanRadius,
                              double consumeRadius,
                              int maxCandidates,
                              List<String> itemIds) {
        public FoodRequest {
            verticalScanRadius = Math.max(0, verticalScanRadius);
            maxCandidates = Math.max(0, Math.min(MAX_CANDIDATES, maxCandidates));
            itemIds = canonicalItemIds(itemIds);
        }

        public FoodRequest(double originX,
                           double originY,
                           double originZ,
                           double radius,
                           int verticalScanRadius,
                           double consumeRadius,
                           List<String> itemIds) {
            this(originX, originY, originZ, radius, verticalScanRadius, consumeRadius, MAX_CANDIDATES, itemIds);
        }

        public FoodRequest(@Nonnull Vector3d origin,
                           double radius,
                           int verticalScanRadius,
                           double consumeRadius,
                           int maxCandidates,
                           List<String> itemIds) {
            this(origin.x, origin.y, origin.z, radius, verticalScanRadius, consumeRadius, maxCandidates, itemIds);
        }

        public FoodRequest(@Nonnull Vector3d origin,
                           double radius,
                           int verticalScanRadius,
                           double consumeRadius,
                           List<String> itemIds) {
            this(origin, radius, verticalScanRadius, consumeRadius, MAX_CANDIDATES, itemIds);
        }

        public List<String> allowedItemIds() {
            return itemIds;
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

        double approachRadius() {
            return Double.isFinite(consumeRadius) && consumeRadius > SCORE_EPSILON
                    ? consumeRadius
                    : DEFAULT_APPROACH_RADIUS;
        }

        @Nonnull
        private static List<String> canonicalItemIds(@Nullable List<String> values) {
            TreeSet<String> canonical = new TreeSet<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        canonical.add(value.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return List.copyOf(canonical);
        }
    }

    /** Supplies food-container state to one synchronous search. */
    interface FoodSearchAccess {
        boolean hasAllowedFood(int x, int y, int z, @Nonnull List<String> allowedIds);
    }

    private static final class WorldSearchAccess implements FoodSearchAccess {
        private final ChunkStore chunkStore;
        private final Store<ChunkStore> chunkStoreStore;
        private final Long2ObjectOpenHashMap<WorldChunk> chunkCache = new Long2ObjectOpenHashMap<>();

        private WorldSearchAccess(@Nonnull ChunkStore chunkStore,
                                  @Nonnull Store<ChunkStore> chunkStoreStore) {
            this.chunkStore = chunkStore;
            this.chunkStoreStore = chunkStoreStore;
        }

        @Override
        public boolean hasAllowedFood(int x, int y, int z, @Nonnull List<String> allowedIds) {
            WorldChunk chunk = resolveChunk(x, z);
            if (chunk == null) {
                return false;
            }
            Object state = FeedTroughContainerCompat.resolveContainerState(
                    chunk,
                    chunkStoreStore,
                    x,
                    y,
                    z
            );
            return containsAllowedFood(FeedTroughContainerCompat.getItemContainer(state), allowedIds);
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

    private static final class CandidateBuffer {
        private final int maxCandidates;
        private final double approachRadius;
        private final int[] x;
        private final int[] y;
        private final int[] z;
        private final double[] distanceSquared;
        private int count;

        private CandidateBuffer(int maxCandidates, double approachRadius) {
            this.maxCandidates = maxCandidates;
            this.approachRadius = approachRadius;
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
                        approachRadius
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
