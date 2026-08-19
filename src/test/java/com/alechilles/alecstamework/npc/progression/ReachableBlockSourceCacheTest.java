package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReachableBlockSourceCacheTest {
    private final ReachableBlockSourceCache cache = new ReachableBlockSourceCache();

    @AfterEach
    void clearPressure() {
        com.alechilles.alecstamework.performance.TameworkRuntimePressureService.getInstance()
                .clearForTests();
    }

    @Test
    void sameStoreAndKeyShareOneSnapshotUntilExpiry() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            AtomicInteger scans = new AtomicInteger();

            ReachableBlockSourceCache.Lookup first = cache.getOrScan(
                    store,
                    key,
                    1_000L,
                    bounds -> {
                        scans.incrementAndGet();
                        return snapshot(2, 64, 3);
                    }
            );
            ReachableBlockSourceCache.Lookup cached = cache.getOrScan(
                    store,
                    key,
                    3_999L,
                    bounds -> {
                        scans.incrementAndGet();
                        return snapshot(8, 64, 3);
                    }
            );
            ReachableBlockSourceCache.Lookup expired = cache.getOrScan(
                    store,
                    key,
                    4_000L,
                    bounds -> {
                        scans.incrementAndGet();
                        return snapshot(8, 64, 3);
                    }
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, first.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, cached.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, expired.status());
            assertEquals(2, scans.get());
            assertNotNull(cached.snapshot());
            assertEquals(2, cached.snapshot().coordinates().get(0).x());
            assertNotNull(expired.snapshot());
            assertEquals(8, expired.snapshot().coordinates().get(0).x());
        }
    }

    @Test
    void differentStoreAreaAndConfigurationDoNotShareSnapshots() {
        try (TestEntityComponentStore firstStore = newStore();
             TestEntityComponentStore secondStore = newStore()) {
            AtomicInteger scans = new AtomicInteger();
            ReachableBlockSourceCache.SourceKey same = key(0, "hytale:hay_bale");
            ReachableBlockSourceCache.SourceKey differentArea = key(4, "hytale:hay_bale");
            ReachableBlockSourceCache.SourceKey differentConfig = key(0, "hytale:chest");

            cache.getOrScan(firstStore, same, 1_000L, bounds -> scan(scans));
            cache.getOrScan(firstStore, differentArea, 1_000L, bounds -> scan(scans));
            cache.getOrScan(firstStore, differentConfig, 1_050L, bounds -> scan(scans));
            cache.getOrScan(secondStore, same, 1_051L, bounds -> scan(scans));

            assertEquals(4, scans.get());
        }
    }

    @Test
    void canonicalConfigurationSharesRegardlessOfInputOrderAndCase() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey first = ReachableBlockSourceCache.keyFor(
                    " WORLD-A ",
                    1,
                    64,
                    2,
                    " Hytale:Hay_Bale ",
                    List.of("MOD:Feeder", "hytale:hay_bale"),
                    12.0,
                    4
            );
            ReachableBlockSourceCache.SourceKey equivalent = ReachableBlockSourceCache.keyFor(
                    "world-a",
                    3,
                    65,
                    0,
                    "hytale:hay_bale",
                    List.of("hytale:hay_bale", "mod:feeder"),
                    12.0,
                    4
            );
            AtomicInteger scans = new AtomicInteger();

            cache.getOrScan(store, first, 1_000L, bounds -> scan(scans));
            cache.getOrScan(store, equivalent, 1_001L, bounds -> scan(scans));

            assertEquals(first, equivalent);
            assertEquals(1, scans.get());
        }
    }

    @Test
    void clearingStoreRemovesSnapshots() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            AtomicInteger scans = new AtomicInteger();
            cache.getOrScan(store, key, 1_000L, bounds -> scan(scans));

            cache.clear(store);
            ReachableBlockSourceCache.Lookup result = cache.getOrScan(
                    store,
                    key,
                    1_001L,
                    bounds -> scan(scans)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, result.status());
            assertEquals(2, scans.get());
            assertEquals(1, cache.snapshotCountForTests(store));
        }
    }

    @Test
    void thirdColdScanDefersWithinOneWindowAndSnapshotStateStaysBounded() {
        try (TestEntityComponentStore store = newStore()) {
            AtomicInteger scans = new AtomicInteger();
            ReachableBlockSourceCache.Lookup first = cache.getOrScan(
                    store,
                    key(0, "hytale:hay_bale"),
                    1_000L,
                    bounds -> scan(scans)
            );
            com.alechilles.alecstamework.performance.TameworkRuntimePressureService.getInstance()
                    .clearForTests();
            ReachableBlockSourceCache.Lookup second = cache.getOrScan(
                    store,
                    key(4, "hytale:hay_bale"),
                    1_001L,
                    bounds -> scan(scans)
            );
            ReachableBlockSourceCache.Lookup deferred = cache.getOrScan(
                    store,
                    key(8, "hytale:hay_bale"),
                    1_002L,
                    bounds -> scan(scans)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, first.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, second.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.DEFERRED, deferred.status());
            assertEquals(2, scans.get());
            assertTrue(cache.snapshotCountForTests(store)
                    <= ReachableBlockSourceCache.MAX_SNAPSHOTS_PER_STORE);
        }
    }

    @Test
    void sameColdKeyDefersWhileAnotherCallerOwnsTheScan() throws Exception {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            CountDownLatch scanStarted = new CountDownLatch(1);
            CountDownLatch allowScan = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);
            AtomicReference<ReachableBlockSourceCache.Lookup> firstResult = new AtomicReference<>();
            AtomicReference<ReachableBlockSourceCache.Lookup> secondResult = new AtomicReference<>();
            Thread first = new Thread(() -> firstResult.set(cache.getOrScan(
                    store,
                    key,
                    1_000L,
                    bounds -> {
                        scanStarted.countDown();
                        try {
                            allowScan.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return snapshot(2, 64, 3);
                    }
            )));
            first.setDaemon(true);
            first.start();

            assertTrue(scanStarted.await(5, TimeUnit.SECONDS));
            Thread second = new Thread(() -> {
                secondResult.set(cache.getOrScan(
                        store,
                        key,
                        1_001L,
                        bounds -> snapshot(8, 64, 3)
                ));
                secondFinished.countDown();
            });
            second.setDaemon(true);
            second.start();

            assertTrue(secondFinished.await(1, TimeUnit.SECONDS));
            allowScan.countDown();
            first.join(5_000L);
            second.join(5_000L);

            assertNotNull(firstResult.get());
            assertNotNull(secondResult.get());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.DEFERRED, secondResult.get().status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, firstResult.get().status());
        }
    }

    @Test
    void stalePermitCannotCompleteAReacquiredSameKey() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            ReachableBlockSourceCache.ColdScanStart first = cache.startColdScan(store, key, 0L);

            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, first.status());
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.HIT,
                    cache.completeColdScan(first.permit(), snapshot(1, 64, 1), 0L).status()
            );

            ReachableBlockSourceCache.ColdScanStart second = cache.startColdScan(
                    store,
                    key,
                    ReachableBlockSourceCache.SNAPSHOT_TTL_MS
            );
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, second.status());

            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.completeColdScan(first.permit(), snapshot(2, 64, 2), 3_001L).status()
            );
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.lookup(store, key, 3_001L).status()
            );

            ReachableBlockSourceCache.Lookup completed = cache.completeColdScan(
                    second.permit(),
                    snapshot(3, 64, 3),
                    3_001L
            );
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, completed.status());
            assertEquals(3, completed.snapshot().coordinates().get(0).x());
        }
    }

    @Test
    void injectedSnapshotCapEvictsOldestAndPrunesExpiredEntriesFirst() {
        ReachableBlockSourceCache boundedCache = new ReachableBlockSourceCache(2);
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey first = key(0, "hytale:first");
            ReachableBlockSourceCache.SourceKey second = key(4, "hytale:second");
            ReachableBlockSourceCache.SourceKey third = key(8, "hytale:third");
            AtomicInteger scans = new AtomicInteger();

            boundedCache.getOrScan(store, first, 0L, bounds -> scan(scans));
            boundedCache.getOrScan(store, second, 1_000L, bounds -> scan(scans));
            boundedCache.getOrScan(store, third, 1_500L, bounds -> scan(scans));

            ReachableBlockSourceCache.Lookup evicted = boundedCache.getOrScan(
                    store,
                    first,
                    2_000L,
                    bounds -> scan(scans)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, evicted.status());
            assertEquals(2, boundedCache.snapshotCountForTests(store));
            assertEquals(4, scans.get());
        }
        ReachableBlockSourceCache expiredCache = new ReachableBlockSourceCache(2);
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey first = key(0, "hytale:expired-first");
            ReachableBlockSourceCache.SourceKey second = key(4, "hytale:still-live");
            ReachableBlockSourceCache.SourceKey third = key(8, "hytale:new-entry");
            AtomicInteger scans = new AtomicInteger();

            expiredCache.getOrScan(store, first, 0L, bounds -> scan(scans));
            expiredCache.getOrScan(store, second, 1_000L, bounds -> scan(scans));
            expiredCache.getOrScan(store, third, 3_000L, bounds -> scan(scans));

            ReachableBlockSourceCache.Lookup retained = expiredCache.getOrScan(
                    store,
                    second,
                    3_500L,
                    bounds -> scan(scans)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, retained.status());
            assertEquals(2, expiredCache.snapshotCountForTests(store));
            assertEquals(3, scans.get());
        }
    }

    @Test
    void elevatedPressureAdmitsOnlyOneColdScanPerWindow() {
        com.alechilles.alecstamework.performance.TameworkRuntimePressureService pressure =
                com.alechilles.alecstamework.performance.TameworkRuntimePressureService.getInstance();
        pressure.recordWork(
                com.alechilles.alecstamework.performance.RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                8_000_000L,
                1_000L
        );
        pressure.recordWork(
                com.alechilles.alecstamework.performance.RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                8_000_000L,
                1_000L
        );
        try (TestEntityComponentStore store = newStore()) {
            AtomicInteger scans = new AtomicInteger();
            ReachableBlockSourceCache.Lookup first = cache.getOrScan(
                    store,
                    key(0, "hytale:first"),
                    1_000L,
                    bounds -> scan(scans)
            );
            ReachableBlockSourceCache.Lookup deferred = cache.getOrScan(
                    store,
                    key(4, "hytale:second"),
                    1_001L,
                    bounds -> scan(scans)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, first.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.DEFERRED, deferred.status());
            assertEquals(1, scans.get());
        }
    }

    private static ReachableBlockSourceCache.Snapshot scan(AtomicInteger scans) {
        scans.incrementAndGet();
        return snapshot(2, 64, 3);
    }

    private static ReachableBlockSourceCache.Snapshot snapshot(int x, int y, int z) {
        return new ReachableBlockSourceCache.Snapshot(
                List.of(new ReachableBlockSourceCache.SourceCoordinate(x, y, z))
        );
    }

    private static ReachableBlockSourceCache.SourceKey key(int x, String blockType) {
        return ReachableBlockSourceCache.keyFor(
                "world-a",
                x,
                64,
                0,
                null,
                List.of(blockType),
                12.0,
                4
        );
    }

    private static TestEntityComponentStore newStore() {
        return new TestEntityComponentStore(new EntityStore(null));
    }
}
