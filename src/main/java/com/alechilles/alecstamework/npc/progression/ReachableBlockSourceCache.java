package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.RuntimePressureLevel;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Shares short-lived matching-source coordinates between reachable-block sensors in one store.
 *
 * <p>The store identity is held only by {@link StoreScopedState}. Entries contain scalar keys and
 * immutable coordinates. A cold scanner is supplied only for the duration of one admission and
 * is never retained by this cache.</p>
 */
public final class ReachableBlockSourceCache {
    public static final int AREA_CELL_SIZE_BLOCKS = 4;
    public static final int MAX_SOURCE_CANDIDATES = 64;
    public static final int MAX_SNAPSHOTS_PER_STORE = 4_096;
    public static final long SNAPSHOT_TTL_MS = 3_000L;

    private static final long ADMISSION_WINDOW_MS = 50L;
    private static final int NORMAL_COLD_SCANS_PER_WINDOW = 2;
    private static final int ELEVATED_COLD_SCANS_PER_WINDOW = 1;
    private static final ReachableBlockSourceCache SHARED = new ReachableBlockSourceCache();

    private final TameworkRuntimePressureService pressureService =
            TameworkRuntimePressureService.getInstance();
    private final StoreScopedState<StoreState> statesByStore =
            new StoreScopedState<>(StoreState::new);

    /** Returns the process-wide source cache used by reachable-block sensors. */
    @Nonnull
    public static ReachableBlockSourceCache shared() {
        return SHARED;
    }

    /** Creates an isolated cache for focused tests or an embedding owner. */
    public ReachableBlockSourceCache() {
        this(MAX_SNAPSHOTS_PER_STORE);
    }

    ReachableBlockSourceCache(int maxSnapshotsPerStore) {
        if (maxSnapshotsPerStore <= 0) {
            throw new IllegalArgumentException("maxSnapshotsPerStore must be positive");
        }
        this.maxSnapshotsPerStore = maxSnapshotsPerStore;
    }

    private final int maxSnapshotsPerStore;

    /**
     * Builds a canonical key for one sensor configuration and four-block area cell.
     *
     * <p>Block IDs and world identity are normalized. The coordinate arguments are block
     * coordinates; callers in one cell therefore receive the same key.</p>
     */
    @Nonnull
    public static SourceKey keyFor(@Nullable String worldName,
                                   int blockX,
                                   int blockY,
                                   int blockZ,
                                   @Nullable String blockSet,
                                   @Nonnull Collection<String> blockTypes,
                                   double horizontalRange,
                                   int verticalRadius) {
        Objects.requireNonNull(blockTypes, "blockTypes");
        if (!Double.isFinite(horizontalRange) || horizontalRange <= 0.0) {
            throw new IllegalArgumentException("horizontalRange must be finite and positive");
        }
        return new SourceKey(
                normalizeWorld(worldName),
                Math.floorDiv(blockX, AREA_CELL_SIZE_BLOCKS),
                Math.floorDiv(blockY, AREA_CELL_SIZE_BLOCKS),
                Math.floorDiv(blockZ, AREA_CELL_SIZE_BLOCKS),
                SensorConfiguration.from(blockSet, blockTypes),
                horizontalRange == 0.0 ? 0.0 : horizontalRange,
                Math.max(0, verticalRadius)
        );
    }

    /** Builds a key from a constructor-canonicalized sensor configuration. */
    @Nonnull
    public static SourceKey keyFor(@Nullable String worldName,
                                   int blockX,
                                   int blockY,
                                   int blockZ,
                                   @Nonnull SensorConfiguration configuration,
                                   double horizontalRange,
                                   int verticalRadius) {
        Objects.requireNonNull(configuration, "configuration");
        if (!Double.isFinite(horizontalRange) || horizontalRange <= 0.0) {
            throw new IllegalArgumentException("horizontalRange must be finite and positive");
        }
        return new SourceKey(
                normalizeWorld(worldName),
                Math.floorDiv(blockX, AREA_CELL_SIZE_BLOCKS),
                Math.floorDiv(blockY, AREA_CELL_SIZE_BLOCKS),
                Math.floorDiv(blockZ, AREA_CELL_SIZE_BLOCKS),
                configuration,
                horizontalRange,
                Math.max(0, verticalRadius)
        );
    }

    /**
     * Looks up one snapshot or admits one bounded cold scan.
     *
     * <p>A deferred result has no snapshot. In particular, it is not stored as a negative result
     * for an individual NPC.</p>
     */
    @Nonnull
    public Lookup getOrScan(@Nonnull Store<EntityStore> store,
                            @Nonnull SourceKey key,
                            long nowMs,
                            @Nonnull SourceScanner scanner) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scanner, "scanner");
        Lookup existing = lookup(store, key, nowMs);
        if (existing.status() != Lookup.Status.ABSENT) {
            return existing;
        }
        ColdScanStart start = startColdScan(store, key, nowMs);
        if (start.status() != ColdScanStart.Status.ACQUIRED) {
            return start.asLookup();
        }
        Snapshot snapshot;
        try {
            snapshot = scanner.scan(start.permit().searchBounds());
            if (snapshot == null) {
                snapshot = Snapshot.empty();
            }
        } catch (RuntimeException ignored) {
            snapshot = Snapshot.empty();
        }
        return completeColdScan(start.permit(), snapshot, nowMs);
    }

    /** Looks up a snapshot without allocating or admitting a cold scanner. */
    @Nonnull
    public Lookup lookup(@Nonnull Store<EntityStore> store,
                         @Nonnull SourceKey key,
                         long nowMs) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        StoreState state = statesByStore.get(store);
        synchronized (state) {
            return lookupLocked(state, key, nowMs);
        }
    }

    /** Attempts to own one cold scan after the caller has observed an absent snapshot. */
    @Nonnull
    public ColdScanStart startColdScan(@Nonnull Store<EntityStore> store,
                                       @Nonnull SourceKey key,
                                       long nowMs) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        StoreState state = statesByStore.get(store);
        synchronized (state) {
            Lookup existing = lookupLocked(state, key, nowMs);
            if (existing.status() != Lookup.Status.ABSENT) {
                return ColdScanStart.from(existing);
            }
            if (!admitColdScan(state, nowMs)) {
                return ColdScanStart.deferred();
            }
            ColdScanPermit permit = new ColdScanPermit(state, key, System.nanoTime());
            state.activeScans.put(key, permit);
            return ColdScanStart.acquired(permit);
        }
    }

    /** Publishes one completed cold scan while keeping clear and publish atomic. */
    @Nonnull
    public Lookup completeColdScan(@Nonnull ColdScanPermit permit,
                                   @Nonnull Snapshot snapshot,
                                   long nowMs) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (permit.state()) {
            ColdScanPermit active = permit.state().activeScans.get(permit.key());
            if (permit.state().closed
                    || permit.isConsumed()
                    || active != permit) {
                return Lookup.deferred();
            }
            permit.consume();
            permit.state().activeScans.remove(permit.key(), permit);
            permit.state().put(
                    permit.key(),
                    snapshot,
                    nowMs + SNAPSHOT_TTL_MS,
                    nowMs,
                    maxSnapshotsPerStore
            );
            Lookup result = Lookup.from(snapshot);
            pressureService.recordWork(
                    RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                    Math.max(0L, System.nanoTime() - permit.startedNs()),
                    nowMs
            );
            return result;
        }
    }

    @Nonnull
    private static Lookup lookupLocked(@Nonnull StoreState state,
                                       @Nonnull SourceKey key,
                                       long nowMs) {
        if (state.closed) {
            return Lookup.deferred();
        }
        CachedSnapshot cached = state.snapshots.get(key);
        if (cached != null) {
            if (nowMs < cached.expiresAtMs()) {
                return Lookup.from(cached.snapshot());
            }
            state.snapshots.remove(key);
        }
        if (state.activeScans.containsKey(key)) {
            return Lookup.deferred();
        }
        return Lookup.absent();
    }

    /** Removes all snapshots and in-flight ownership for one entity store. */
    public void clear(@Nonnull Store<EntityStore> store) {
        Objects.requireNonNull(store, "store");
        StoreState state = statesByStore.get(store);
        synchronized (state) {
            state.closed = true;
            state.snapshots.clear();
            for (ColdScanPermit permit : state.activeScans.values()) {
                permit.consume();
            }
            state.activeScans.clear();
        }
        statesByStore.remove(store);
    }

    int snapshotCountForTests(@Nonnull Store<EntityStore> store) {
        StoreState state = statesByStore.get(Objects.requireNonNull(store, "store"));
        synchronized (state) {
            return state.snapshots.size();
        }
    }

    private boolean admitColdScan(@Nonnull StoreState state, long nowMs) {
        long windowStartMs = nowMs - Math.floorMod(nowMs, ADMISSION_WINDOW_MS);
        if (state.windowStartMs != windowStartMs) {
            state.windowStartMs = windowStartMs;
            state.coldScansInWindow = 0;
        }
        RuntimePressureLevel level = pressureService.level(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                nowMs
        );
        int limit = level.ordinal() >= RuntimePressureLevel.WARM.ordinal()
                ? ELEVATED_COLD_SCANS_PER_WINDOW
                : NORMAL_COLD_SCANS_PER_WINDOW;
        if (state.coldScansInWindow >= limit) {
            return false;
        }
        state.coldScansInWindow++;
        return true;
    }

    @Nonnull
    private static String normalizeWorld(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "<unknown>";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class StoreState {
        private final LinkedHashMap<SourceKey, CachedSnapshot> snapshots = new LinkedHashMap<>();
        private final Map<SourceKey, ColdScanPermit> activeScans = new LinkedHashMap<>();
        private long windowStartMs = Long.MIN_VALUE;
        private int coldScansInWindow;
        private boolean closed;

        private void put(@Nonnull SourceKey key,
                         @Nonnull Snapshot snapshot,
                         long expiresAtMs,
                         long nowMs,
                         int maxSnapshotsPerStore) {
            pruneExpired(nowMs);
            if (snapshots.size() >= maxSnapshotsPerStore) {
                Iterator<SourceKey> iterator = snapshots.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            snapshots.put(key, new CachedSnapshot(snapshot, expiresAtMs));
        }

        private void pruneExpired(long nowMs) {
            snapshots.entrySet().removeIf(entry -> nowMs >= entry.getValue().expiresAtMs());
        }
    }

    private record CachedSnapshot(@Nonnull Snapshot snapshot, long expiresAtMs) {
    }

    /** Immutable source coordinate retained by a shared snapshot. */
    public record SourceCoordinate(int x, int y, int z) {
    }

    /** Immutable matching-source result. Empty coordinates are valid negative snapshots. */
    public record Snapshot(@Nonnull List<SourceCoordinate> coordinates) {
        public Snapshot {
            Objects.requireNonNull(coordinates, "coordinates");
            int size = Math.min(MAX_SOURCE_CANDIDATES, coordinates.size());
            coordinates = List.copyOf(coordinates.subList(0, size));
        }

        @Nonnull
        public static Snapshot empty() {
            return new Snapshot(List.of());
        }
    }

    /** Canonical key for one store-local source snapshot. */
    public record SourceKey(@Nonnull String worldName,
                            int areaX,
                            int areaY,
                            int areaZ,
                            @Nonnull SensorConfiguration configuration,
                            double horizontalRange,
                            int verticalRadius) {
        public SourceKey {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(configuration, "configuration");
            if (!Double.isFinite(horizontalRange) || horizontalRange <= 0.0) {
                throw new IllegalArgumentException("horizontalRange must be finite and positive");
            }
            verticalRadius = Math.max(0, verticalRadius);
        }

        @Nonnull
        public SearchBounds searchBounds() {
            int radius = (int) Math.min(Integer.MAX_VALUE / 4L, Math.ceil(horizontalRange));
            long cellMinX = (long) areaX * AREA_CELL_SIZE_BLOCKS;
            long cellMinY = (long) areaY * AREA_CELL_SIZE_BLOCKS;
            long cellMinZ = (long) areaZ * AREA_CELL_SIZE_BLOCKS;
            return new SearchBounds(
                    toInt(cellMinX - radius),
                    toInt(cellMinX + AREA_CELL_SIZE_BLOCKS - 1L + radius),
                    toInt(cellMinY - verticalRadius),
                    toInt(cellMinY + AREA_CELL_SIZE_BLOCKS - 1L + verticalRadius),
                    toInt(cellMinZ - radius),
                    toInt(cellMinZ + AREA_CELL_SIZE_BLOCKS - 1L + radius)
            );
        }

        public int authorityOriginX() {
            return toInt((long) areaX * AREA_CELL_SIZE_BLOCKS);
        }

        public int authorityOriginY() {
            return toInt((long) areaY * AREA_CELL_SIZE_BLOCKS);
        }

        public int authorityOriginZ() {
            return toInt((long) areaZ * AREA_CELL_SIZE_BLOCKS);
        }

        private static int toInt(long value) {
            return value <= Integer.MIN_VALUE
                    ? Integer.MIN_VALUE
                    : value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }

    /** Canonical block-set and block-type identity for a sensor. */
    public record SensorConfiguration(@Nullable String blockSet,
                                      @Nonnull List<String> blockTypes) {
        public SensorConfiguration {
            blockSet = normalizeId(blockSet);
            Objects.requireNonNull(blockTypes, "blockTypes");
            TreeSet<String> canonical = new TreeSet<>();
            for (String blockType : blockTypes) {
                String normalized = normalizeId(blockType);
                if (normalized != null) {
                    canonical.add(normalized);
                }
            }
            blockTypes = List.copyOf(canonical);
        }

        @Nonnull
        private static SensorConfiguration from(@Nullable String blockSet,
                                                @Nonnull Collection<String> blockTypes) {
            return new SensorConfiguration(blockSet, new ArrayList<>(blockTypes));
        }

        @Nullable
        private static String normalizeId(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** Scalar union bounds passed to one transient cold scanner. */
    public record SearchBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    /** Selects one best source for each of the 64 block authorities in one keyed cell. */
    public static final class AuthoritySourceSelector {
        private static final double RANGE_EPSILON = 0.000001;
        private final SearchBounds bounds;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final double horizontalRange;
        private final int verticalRadius;
        private final SourceCoordinate[] representatives =
                new SourceCoordinate[MAX_SOURCE_CANDIDATES];
        private final double[] distances = new double[MAX_SOURCE_CANDIDATES];

        public AuthoritySourceSelector(@Nonnull SearchBounds bounds,
                                       int originX,
                                       int originY,
                                       int originZ,
                                       double horizontalRange,
                                       int verticalRadius) {
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            if (!Double.isFinite(horizontalRange) || horizontalRange <= 0.0) {
                throw new IllegalArgumentException("horizontalRange must be finite and positive");
            }
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.horizontalRange = horizontalRange;
            this.verticalRadius = Math.max(0, verticalRadius);
        }

        public void offer(@Nonnull SourceCoordinate coordinate) {
            Objects.requireNonNull(coordinate, "coordinate");
            if (coordinate.x() < bounds.minX()
                    || coordinate.x() > bounds.maxX()
                    || coordinate.y() < bounds.minY()
                    || coordinate.y() > bounds.maxY()
                    || coordinate.z() < bounds.minZ()
                    || coordinate.z() > bounds.maxZ()) {
                return;
            }
            for (int xOffset = 0; xOffset < AREA_CELL_SIZE_BLOCKS; xOffset++) {
                for (int yOffset = 0; yOffset < AREA_CELL_SIZE_BLOCKS; yOffset++) {
                    for (int zOffset = 0; zOffset < AREA_CELL_SIZE_BLOCKS; zOffset++) {
                        int authorityX = originX + xOffset;
                        int authorityY = originY + yOffset;
                        int authorityZ = originZ + zOffset;
                        if (!isSourceInRangeForAuthority(
                                coordinate,
                                authorityX,
                                authorityY,
                                authorityZ,
                                horizontalRange,
                                verticalRadius
                        )) {
                            continue;
                        }
                        int index = authorityIndex(xOffset, yOffset, zOffset);
                        double distance = distanceSquared(
                                coordinate,
                                authorityX,
                                authorityY,
                                authorityZ
                        );
                        SourceCoordinate current = representatives[index];
                        if (current == null
                                || isBetter(coordinate, distance, current, distances[index])) {
                            representatives[index] = coordinate;
                            distances[index] = distance;
                        }
                    }
                }
            }
        }

        @Nonnull
        public List<SourceCoordinate> finish() {
            LinkedHashSet<SourceCoordinate> selected = new LinkedHashSet<>(MAX_SOURCE_CANDIDATES);
            for (SourceCoordinate representative : representatives) {
                if (representative != null) {
                    selected.add(representative);
                }
            }
            return List.copyOf(selected);
        }

        private static int authorityIndex(int xOffset, int yOffset, int zOffset) {
            return (xOffset * AREA_CELL_SIZE_BLOCKS * AREA_CELL_SIZE_BLOCKS)
                    + (yOffset * AREA_CELL_SIZE_BLOCKS)
                    + zOffset;
        }

        private static double distanceSquared(@Nonnull SourceCoordinate source,
                                              int authorityX,
                                              int authorityY,
                                              int authorityZ) {
            double dx = source.x() - authorityX;
            double dy = source.y() - authorityY;
            double dz = source.z() - authorityZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }

        private static boolean isBetter(@Nonnull SourceCoordinate candidate,
                                        double candidateDistance,
                                        @Nonnull SourceCoordinate current,
                                        double currentDistance) {
            if (candidateDistance < currentDistance - RANGE_EPSILON) {
                return true;
            }
            if (Math.abs(candidateDistance - currentDistance) > RANGE_EPSILON) {
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
    }

    /** Applies source range rules to an integer NPC block authority. */
    public static boolean isSourceInRangeForAuthority(@Nullable SourceCoordinate source,
                                                      int authorityX,
                                                      int authorityY,
                                                      int authorityZ,
                                                      double horizontalRange,
                                                      int verticalRadius) {
        if (source == null
                || !Double.isFinite(horizontalRange)
                || horizontalRange <= 0.0) {
            return false;
        }
        double dx = source.x() - authorityX;
        double dz = source.z() - authorityZ;
        if ((dx * dx) + (dz * dz) > (horizontalRange * horizontalRange) + 0.000001) {
            return false;
        }
        return Math.abs(source.y() - authorityY) <= Math.max(0, verticalRadius);
    }

    /** Result of a cache lookup or bounded cold-scan admission. */
    public record Lookup(@Nonnull Status status, @Nullable Snapshot snapshot) {
        public Lookup {
            Objects.requireNonNull(status, "status");
            if (status == Status.DEFERRED && snapshot != null) {
                throw new IllegalArgumentException("deferred lookup cannot contain a snapshot");
            }
        }

        @Nonnull
        private static Lookup from(@Nonnull Snapshot snapshot) {
            return new Lookup(
                    snapshot.coordinates().isEmpty() ? Status.MISS : Status.HIT,
                    snapshot
            );
        }

        @Nonnull
        private static Lookup deferred() {
            return new Lookup(Status.DEFERRED, null);
        }

        @Nonnull
        private static Lookup absent() {
            return new Lookup(Status.ABSENT, null);
        }

        public enum Status {
            HIT,
            MISS,
            DEFERRED,
            ABSENT
        }
    }

    /** Result of attempting to acquire a cold scan without building scan state first. */
    public record ColdScanStart(@Nonnull Status status,
                                @Nullable Snapshot snapshot,
                                @Nullable ColdScanPermit permit) {
        public ColdScanStart {
            Objects.requireNonNull(status, "status");
            if (status == Status.ACQUIRED && permit == null) {
                throw new IllegalArgumentException("acquired scan requires a permit");
            }
            if (status != Status.ACQUIRED && permit != null) {
                throw new IllegalArgumentException("non-acquired scan cannot contain a permit");
            }
        }

        @Nonnull
        private static ColdScanStart acquired(@Nonnull ColdScanPermit permit) {
            return new ColdScanStart(Status.ACQUIRED, null, permit);
        }

        @Nonnull
        private static ColdScanStart deferred() {
            return new ColdScanStart(Status.DEFERRED, null, null);
        }

        @Nonnull
        private static ColdScanStart from(@Nonnull Lookup lookup) {
            return switch (lookup.status()) {
                case HIT -> new ColdScanStart(Status.HIT, lookup.snapshot(), null);
                case MISS -> new ColdScanStart(Status.MISS, lookup.snapshot(), null);
                case DEFERRED -> deferred();
                case ABSENT -> throw new IllegalArgumentException("absent lookup cannot become a start result");
            };
        }

        @Nonnull
        private Lookup asLookup() {
            return switch (status) {
                case HIT -> Lookup.from(snapshot == null ? Snapshot.empty() : snapshot);
                case MISS -> Lookup.from(snapshot == null ? Snapshot.empty() : snapshot);
                case DEFERRED, ACQUIRED -> Lookup.deferred();
            };
        }

        public enum Status {
            ACQUIRED,
            HIT,
            MISS,
            DEFERRED
        }
    }

    /** Transient ownership token for one cold scan. It is never retained after publication. */
    public static final class ColdScanPermit {
        private final StoreState state;
        private final SourceKey key;
        private final long startedNs;
        private boolean consumed;

        private ColdScanPermit(@Nonnull StoreState state,
                               @Nonnull SourceKey key,
                               long startedNs) {
            this.state = state;
            this.key = key;
            this.startedNs = startedNs;
        }

        @Nonnull
        public SearchBounds searchBounds() {
            return key.searchBounds();
        }

        private StoreState state() {
            return state;
        }

        private SourceKey key() {
            return key;
        }

        private long startedNs() {
            return startedNs;
        }

        private boolean isConsumed() {
            return consumed;
        }

        private void consume() {
            consumed = true;
        }
    }

    /** Performs one transient source scan. The callback is never retained. */
    @FunctionalInterface
    public interface SourceScanner {
        @Nullable
        Snapshot scan(@Nonnull SearchBounds bounds);
    }
}
