package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Maintains one immutable five-second NPC population snapshot per entity store.
 *
 * <p>The snapshot contains only scalar coordinates, UUIDs, and role text. Store and ECS objects
 * remain in the bounded cold-scan call and are not retained across queries.</p>
 */
public final class CompanionPopulationSpatialIndex {
    private static final long SNAPSHOT_TTL_MS = 5_000L;
    private static final int CELL_SIZE = 8;
    /**
     * Maximum population-query radius in blocks. With eight-block cells this visits at most
     * 61 cells on each axis, or 226,981 local cells, under the 262,144-cell work ceiling.
     */
    static final double MAX_POPULATION_QUERY_RADIUS = 240.0;
    private static final long MAX_CELL_VISITS = 262_144L;
    private static final AtomicLong GLOBAL_INVALIDATION_GENERATION = new AtomicLong();
    private static final CompanionPopulationSpatialIndex SHARED =
            new CompanionPopulationSpatialIndex(System::currentTimeMillis);

    private final StoreScopedState<StoreState> states = new StoreScopedState<>(StoreState::new);
    private final LongSupplier clock;
    @Nullable
    private final ComponentType<EntityStore, NPCEntity> npcTypeOverride;
    @Nullable
    private final ComponentType<EntityStore, TransformComponent> transformTypeOverride;

    /** Creates the shared runtime index. */
    public static CompanionPopulationSpatialIndex shared() {
        return SHARED;
    }

    CompanionPopulationSpatialIndex(@Nonnull LongSupplier clock) {
        this(clock, null, null);
    }

    CompanionPopulationSpatialIndex(
            @Nonnull LongSupplier clock,
            @Nullable ComponentType<EntityStore, NPCEntity> npcType,
            @Nullable ComponentType<EntityStore, TransformComponent> transformType
    ) {
        this.clock = clock;
        this.npcTypeOverride = npcType;
        this.transformTypeOverride = transformType;
    }

    /**
     * Counts matching NPCs in the exact three-dimensional radius around the source.
     *
     * <p>The first query after expiry scans the store once. Later queries use the same snapshot,
     * even when their source, type, radius, or spatial cell differs.</p>
     */
    public int countNearby(@Nullable Store<EntityStore> store,
                           @Nullable UUID sourceUuid,
                           @Nullable Vector3d sourcePosition,
                           double radius,
                           @Nullable String sourceTypeKey,
                           @Nullable TwBreedingConfig sourceBreedingConfig) {
        if (store == null
                || sourcePosition == null
                || !isFinite(sourcePosition)
                || !Double.isFinite(radius)
                || radius <= 0.0
                || sourceTypeKey == null
                || sourceTypeKey.isBlank()) {
            return 0;
        }
        String normalizedSourceType = normalizePopulationTypeKey(sourceTypeKey);
        if (normalizedSourceType == null) {
            return 0;
        }
        ComponentType<EntityStore, NPCEntity> npcType = npcTypeOverride != null
                ? npcTypeOverride : NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = transformTypeOverride != null
                ? transformTypeOverride : TransformComponent.getComponentType();
        Snapshot snapshot = states.get(store).snapshotFor(
                store,
                clock,
                npcType,
                transformType
        );
        return snapshot.countNearby(
                sourceUuid,
                sourcePosition,
                effectiveQueryRadius(radius),
                normalizedSourceType,
                sourceBreedingConfig
        );
    }

    /** Removes one exact entity-store snapshot on its owning world thread. */
    public void remove(@Nonnull Store<EntityStore> store) {
        states.remove(store);
    }

    /** Invalidates all existing snapshots without retaining their store keys. */
    public void invalidateAll() {
        GLOBAL_INVALIDATION_GENERATION.incrementAndGet();
    }

    @Nullable
    static String normalizePopulationTypeKey(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return roleId.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    static String resolvePopulationTypeKey(@Nullable String roleId,
                                           @Nullable TwBreedingConfig breedingConfig) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        String canonicalRole = roleId;
        if (breedingConfig != null) {
            TwBreedingConfig.RoleFamily family = breedingConfig.resolveLifecycleFamilyForRole(roleId);
            if (family != null && family.getAdultRoleId() != null && !family.getAdultRoleId().isBlank()) {
                canonicalRole = family.getAdultRoleId();
            }
        }
        return normalizePopulationTypeKey(canonicalRole);
    }

    /**
     * Plans the bounded part of a spatial query before it starts allocating cell keys.
     *
     * <p>The radius is capped before cell bounds are calculated. The product is saturated so
     * extreme coordinates cannot overflow into a small loop.</p>
     */
    static QueryPlan planForQuery(@Nonnull Vector3d sourcePosition,
                                  double radius) {
        double effectiveRadius = effectiveQueryRadius(radius);
        long cellOffset = cellOffsetForRadius(effectiveRadius);
        long centerX = cellCoordinate(sourcePosition.x);
        long centerY = cellCoordinate(sourcePosition.y);
        long centerZ = cellCoordinate(sourcePosition.z);
        long minX = saturatedSubtract(centerX, cellOffset);
        long minY = saturatedSubtract(centerY, cellOffset);
        long minZ = saturatedSubtract(centerZ, cellOffset);
        long maxX = saturatedAdd(centerX, cellOffset);
        long maxY = saturatedAdd(centerY, cellOffset);
        long maxZ = saturatedAdd(centerZ, cellOffset);
        long xSpan = inclusiveSpan(minX, maxX);
        long ySpan = inclusiveSpan(minY, maxY);
        long zSpan = inclusiveSpan(minZ, maxZ);
        long localCellVisits = saturatedMultiply(
                saturatedMultiply(xSpan, ySpan), zSpan);
        return new QueryPlan(
                minX, minY, minZ, maxX, maxY, maxZ,
                localCellVisits, MAX_CELL_VISITS
        );
    }

    private static double effectiveQueryRadius(double radius) {
        if (Double.isNaN(radius) || radius <= 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(radius)) {
            return MAX_POPULATION_QUERY_RADIUS;
        }
        return Math.min(radius, MAX_POPULATION_QUERY_RADIUS);
    }

    private static long cellOffsetForRadius(double radius) {
        double offset = Math.ceil(radius / CELL_SIZE);
        return offset >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) offset;
    }

    private static long saturatedAdd(long value, long amount) {
        if (amount <= 0L || value > Long.MAX_VALUE - amount) {
            return amount <= 0L ? value : Long.MAX_VALUE;
        }
        return value + amount;
    }

    private static long saturatedSubtract(long value, long amount) {
        if (amount <= 0L || value < Long.MIN_VALUE + amount) {
            return amount <= 0L ? value : Long.MIN_VALUE;
        }
        return value - amount;
    }

    private static boolean isFinite(@Nonnull Vector3d position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    private static String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0) {
            NPCPlugin plugin = NPCPlugin.get();
            if (plugin != null) {
                String resolved = plugin.getName(roleIndex);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        String roleName = npc.getRoleName();
        return roleName == null || roleName.isBlank() ? null : roleName;
    }

    private static long cellCoordinate(double value) {
        double scaled = value / CELL_SIZE;
        if (scaled <= Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        if (scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.floor(scaled);
    }

    private static long expiryAt(long now) {
        return now > Long.MAX_VALUE - SNAPSHOT_TTL_MS
                ? Long.MAX_VALUE
                : now + SNAPSHOT_TTL_MS;
    }

    private static long inclusiveSpan(long min, long max) {
        if (max < min) {
            return 0L;
        }
        long difference = max - min;
        if (difference < 0L || difference == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return difference + 1L;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static final class StoreState {
        private Snapshot snapshot;

        private synchronized Snapshot snapshotFor(@Nonnull Store<EntityStore> store,
                                                  @Nonnull LongSupplier clock,
                                                  @Nullable ComponentType<EntityStore, NPCEntity> npcType,
                                                  @Nullable ComponentType<EntityStore, TransformComponent> transformType) {
            while (true) {
                long generation = GLOBAL_INVALIDATION_GENERATION.get();
                long now = clock.getAsLong();
                Snapshot current = snapshot;
                if (current != null
                        && current.generation() == generation
                        && now < current.expiresAtMs()) {
                    return current;
                }
                Snapshot rebuilt = Snapshot.build(
                        store, expiryAt(now), generation, npcType, transformType);
                if (GLOBAL_INVALIDATION_GENERATION.get() != generation) {
                    continue;
                }
                snapshot = rebuilt;
                return rebuilt;
            }
        }
    }

    private record Snapshot(Map<CellKey, List<Entry>> buckets,
                            long expiresAtMs,
                            long generation) {
        private static Snapshot build(@Nonnull Store<EntityStore> store,
                                       long expiresAtMs,
                                       long generation,
                                       @Nullable ComponentType<EntityStore, NPCEntity> npcType,
                                       @Nullable ComponentType<EntityStore, TransformComponent> transformType) {
            if (npcType == null || transformType == null) {
                return new Snapshot(Map.of(), expiresAtMs, generation);
            }
            Map<CellKey, List<Entry>> mutableBuckets = new HashMap<>();
            store.forEachChunk(
                    Query.and(npcType, transformType),
                    (ArchetypeChunk<EntityStore> chunk,
                     CommandBuffer<EntityStore> commandBuffer) -> collectChunk(
                            chunk, npcType, transformType, mutableBuckets)
            );
            Map<CellKey, List<Entry>> immutableBuckets = new HashMap<>(mutableBuckets.size());
            for (Map.Entry<CellKey, List<Entry>> bucket : mutableBuckets.entrySet()) {
                immutableBuckets.put(bucket.getKey(), List.copyOf(bucket.getValue()));
            }
            return new Snapshot(Map.copyOf(immutableBuckets), expiresAtMs, generation);
        }

        private static void collectChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                         @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                         @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                         @Nonnull Map<CellKey, List<Entry>> buckets) {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, npcType);
                TransformComponent transform = chunk.getComponent(i, transformType);
                if (npc == null || transform == null || npc.getUuid() == null) {
                    continue;
                }
                String roleId = resolveRoleId(npc);
                Vector3d position = transform.getPosition();
                if (roleId == null || roleId.isBlank() || position == null || !isFinite(position)) {
                    continue;
                }
                Entry entry = new Entry(npc.getUuid(), roleId, position.x, position.y, position.z);
                buckets.computeIfAbsent(
                        new CellKey(cellCoordinate(position.x), cellCoordinate(position.y), cellCoordinate(position.z)),
                        ignored -> new ArrayList<>()
                ).add(entry);
            }
        }

        private int countNearby(@Nullable UUID sourceUuid,
                                @Nonnull Vector3d sourcePosition,
                                double radius,
                                @Nonnull String sourceTypeKey,
                                @Nullable TwBreedingConfig sourceBreedingConfig) {
            double radiusSquared = radius * radius;
            QueryPlan plan = planForQuery(sourcePosition, radius);
            int count = 0;
            for (long x = plan.minX(); ; x++) {
                for (long y = plan.minY(); ; y++) {
                    for (long z = plan.minZ(); ; z++) {
                        if (!cellIntersectsSphere(x, y, z, sourcePosition, radiusSquared)) {
                            if (z == plan.maxZ()) {
                                break;
                            }
                            continue;
                        }
                        List<Entry> entries = buckets.get(new CellKey(x, y, z));
                        if (entries != null) {
                            count += countEntries(
                                    entries, sourceUuid, sourcePosition, radiusSquared,
                                    sourceTypeKey, sourceBreedingConfig
                            );
                        }
                        if (z == plan.maxZ()) {
                            break;
                        }
                    }
                    if (y == plan.maxY()) {
                        break;
                    }
                }
                if (x == plan.maxX()) {
                    break;
                }
            }
            return count;
        }

        private static boolean cellIntersectsSphere(long cellX,
                                                    long cellY,
                                                    long cellZ,
                                                    @Nonnull Vector3d sphereCenter,
                                                    double radiusSquared) {
            if (Double.isInfinite(radiusSquared)) {
                return true;
            }
            double distanceSquared = axisDistanceSquared(
                    cellX, sphereCenter.x)
                    + axisDistanceSquared(cellY, sphereCenter.y)
                    + axisDistanceSquared(cellZ, sphereCenter.z);
            return Double.isFinite(distanceSquared) && distanceSquared <= radiusSquared;
        }

        private static double axisDistanceSquared(long cellCoordinate, double point) {
            // Saturated coordinates represent all positions beyond the long cell range. Keep the
            // test conservative there so the exact entry-distance check remains authoritative.
            if (cellCoordinate == Long.MIN_VALUE || cellCoordinate == Long.MAX_VALUE) {
                return 0.0;
            }
            double min = cellCoordinate * (double) CELL_SIZE;
            double max = min + CELL_SIZE;
            double distance = point < min ? min - point : point > max ? point - max : 0.0;
            return distance * distance;
        }

        private static int countEntries(@Nonnull List<Entry> entries,
                                        @Nullable UUID sourceUuid,
                                        @Nonnull Vector3d sourcePosition,
                                        double radiusSquared,
                                        @Nonnull String sourceTypeKey,
                                        @Nullable TwBreedingConfig sourceBreedingConfig) {
            int count = 0;
            for (Entry entry : entries) {
                if (sourceUuid != null && sourceUuid.equals(entry.uuid())) {
                    continue;
                }
                String candidateType = resolvePopulationTypeKey(entry.roleId(), sourceBreedingConfig);
                if (!sourceTypeKey.equals(candidateType)) {
                    continue;
                }
                double dx = entry.x() - sourcePosition.x;
                double dy = entry.y() - sourcePosition.y;
                double dz = entry.z() - sourcePosition.z;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                if (Double.isFinite(distanceSquared) && distanceSquared <= radiusSquared) {
                    count++;
                }
            }
            return count;
        }
    }

    private record CellKey(long x, long y, long z) {
    }

    private record Entry(UUID uuid, String roleId, double x, double y, double z) {
    }

    static record QueryPlan(long minX,
                            long minY,
                            long minZ,
                            long maxX,
                            long maxY,
                            long maxZ,
                            long localCellVisits,
                            long maxCellVisits) {
    }
}
