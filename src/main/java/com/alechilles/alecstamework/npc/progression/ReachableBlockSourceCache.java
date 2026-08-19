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
    public static final int MAX_BLOCK_PROBES_PER_PASS = 512;
    public static final int MAX_SELECTOR_OFFERS_PER_PASS = 512;
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
            if (state.pendingScans.size() >= MAX_SNAPSHOTS_PER_STORE) {
                Iterator<SourceKey> pendingIterator = state.pendingScans.keySet().iterator();
                while (pendingIterator.hasNext()) {
                    SourceKey pendingKey = pendingIterator.next();
                    if (!state.activeScans.containsKey(pendingKey)) {
                        pendingIterator.remove();
                        break;
                    }
                }
                if (state.pendingScans.size() >= MAX_SNAPSHOTS_PER_STORE) {
                    return ColdScanStart.deferred();
                }
            }
            if (!admitColdScan(state, nowMs)) {
                return ColdScanStart.deferred();
            }
            state.pendingScans.put(
                    key,
                    new ReachableBlockScanSession(
                            key.searchBounds(),
                            key.authorityOriginX(),
                            key.authorityOriginY(),
                            key.authorityOriginZ(),
                            key.horizontalRange(),
                            key.verticalRadius()
                    )
            );
            ColdScanPermit permit = new ColdScanPermit(state, key, System.nanoTime());
            state.activeScans.put(key, permit);
            return ColdScanStart.acquired(permit);
        }
    }

    /**
     * Runs one bounded pass of an admitted scan. The scanner is called only for this pass and is
     * never retained. A partial result keeps only the scalar cursor and selector state in the
     * store-local pending scan.
     */
    @Nonnull
    public Lookup scanColdSlice(@Nonnull ColdScanPermit permit,
                                @Nonnull ScanSliceScanner scanner,
                                long nowMs) {
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(scanner, "scanner");
        ScanSlice slice;
        synchronized (permit.state()) {
            if (!isActivePermit(permit)) {
                return Lookup.deferred();
            }
            if (permit.scanning) {
                return Lookup.deferred();
            }
            ReachableBlockScanSession pending = permit.state().pendingScans.get(permit.key());
            if (pending == null) {
                return Lookup.deferred();
            }
            permit.scanning = true;
            slice = pending.nextSlice();
        }

        ScanResult result;
        try {
            result = scanner.scan(slice);
            if (result == null) {
                result = ScanResult.empty(slice.probeLimit());
            }
        } catch (RuntimeException ignored) {
            result = ScanResult.empty(slice.probeLimit());
        }

        synchronized (permit.state()) {
            permit.scanning = false;
            if (!isActivePermit(permit)) {
                return Lookup.deferred();
            }
            ReachableBlockScanSession pending = permit.state().pendingScans.get(permit.key());
            if (pending == null) {
                permit.consume();
                permit.state().activeScans.remove(permit.key(), permit);
                return Lookup.deferred();
            }
            try {
                pending.accept(result);
            } catch (RuntimeException ignored) {
                // A malformed scanner result must not leave a live permit or partial state.
                pending.markComplete();
            }
            permit.consume();
            permit.state().activeScans.remove(permit.key(), permit);
            if (!pending.complete()) {
                return Lookup.deferred();
            }
            permit.state().pendingScans.remove(permit.key(), pending);
            Snapshot snapshot = pending.snapshot();
            permit.state().put(
                    permit.key(),
                    snapshot,
                    nowMs + SNAPSHOT_TTL_MS,
                    nowMs,
                    maxSnapshotsPerStore
            );
            pressureService.recordWork(
                    RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                    Math.max(0L, System.nanoTime() - permit.startedNs()),
                    nowMs
            );
            return Lookup.from(snapshot);
        }
    }

    /** Attempts to admit the next pass for a partial scan. */
    @Nonnull
    public ColdScanStart resumeColdScan(@Nonnull Store<EntityStore> store,
                                        @Nonnull SourceKey key,
                                        long nowMs) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(key, "key");
        StoreState state = statesByStore.get(store);
        synchronized (state) {
            if (state.closed) {
                return ColdScanStart.deferred();
            }
            Lookup existing = lookupLocked(state, key, nowMs);
            if (existing.status() != Lookup.Status.DEFERRED) {
                return ColdScanStart.from(existing);
            }
            if (state.activeScans.containsKey(key) || !state.pendingScans.containsKey(key)) {
                return ColdScanStart.deferred();
            }
            if (!admitColdScan(state, nowMs)) {
                return ColdScanStart.deferred();
            }
            ColdScanPermit permit = new ColdScanPermit(state, key, System.nanoTime());
            state.activeScans.put(key, permit);
            return ColdScanStart.acquired(permit);
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
        if (state.pendingScans.containsKey(key)) {
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
            state.pendingScans.clear();
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
        private final LinkedHashMap<SourceKey, ReachableBlockScanSession> pendingScans =
                new LinkedHashMap<>();
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

    private boolean isActivePermit(@Nonnull ColdScanPermit permit) {
        return !permit.state().closed
                && !permit.isConsumed()
                && permit.state().activeScans.get(permit.key()) == permit;
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

    /** One bounded source-discovery pass. All fields are scalar scan state. */
    public record ScanSlice(@Nonnull SearchBounds bounds,
                             int startX,
                             int startY,
                             int startZ,
                             int probeLimit) {
        public ScanSlice {
            Objects.requireNonNull(bounds, "bounds");
            if (probeLimit <= 0 || probeLimit > MAX_BLOCK_PROBES_PER_PASS) {
                throw new IllegalArgumentException("probeLimit outside cold scan budget");
            }
        }
    }

    /** Matching coordinates found by one bounded pass. Coordinates are transient scanner output. */
    public record ScanResult(@Nonnull List<SourceCoordinate> matches, int probes) {
        public ScanResult {
            Objects.requireNonNull(matches, "matches");
            if (probes < 0 || probes > MAX_BLOCK_PROBES_PER_PASS) {
                throw new IllegalArgumentException("probes outside cold scan budget");
            }
            if (matches.size() > MAX_SELECTOR_OFFERS_PER_PASS) {
                throw new IllegalArgumentException("matches outside selector offer budget");
            }
            matches = List.copyOf(matches);
        }

        @Nonnull
        public static ScanResult empty(int probes) {
            return new ScanResult(List.of(), probes);
        }

        @Nonnull
        public static ScanResult of(@Nonnull List<SourceCoordinate> matches, int probes) {
            return new ScanResult(matches, probes);
        }
    }

    /** Compatibility facade for callers that use the public selector API. */
    public static final class AuthoritySourceSelector {
        private final ReachableBlockAuthoritySelector delegate;

        public AuthoritySourceSelector(@Nonnull SearchBounds bounds,
                                       int originX,
                                       int originY,
                                       int originZ,
                                       double horizontalRange,
                                       int verticalRadius) {
            delegate = new ReachableBlockAuthoritySelector(
                    bounds,
                    originX,
                    originY,
                    originZ,
                    horizontalRange,
                    verticalRadius
            );
        }

        public void offer(@Nonnull SourceCoordinate coordinate) {
            delegate.offer(coordinate);
        }

        @Nonnull
        public List<SourceCoordinate> finish() {
            return delegate.finish();
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
        private boolean scanning;

        private ColdScanPermit(@Nonnull StoreState state,
                               @Nonnull SourceKey key,
                               long startedNs) {
            this.state = state;
            this.key = key;
            this.startedNs = startedNs;
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

    /** Performs one bounded pass without retaining a world or ECS callback. */
    @FunctionalInterface
    public interface ScanSliceScanner {
        @Nonnull
        ScanResult scan(@Nonnull ScanSlice slice);
    }
}
