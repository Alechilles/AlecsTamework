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

            ReachableBlockSourceCache.Lookup first = boundedScan(
                    cache,
                    store,
                    key,
                    1_000L,
                    scans,
                    snapshot(2, 64, 3)
            );
            ReachableBlockSourceCache.Lookup cached = boundedScan(
                    cache,
                    store,
                    key,
                    3_999L,
                    scans,
                    snapshot(8, 64, 3)
            );
            ReachableBlockSourceCache.Lookup expired = boundedScan(
                    cache,
                    store,
                    key,
                    4_000L,
                    scans,
                    snapshot(8, 64, 3)
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

            boundedScan(cache, firstStore, same, 1_000L, scans, snapshot(2, 64, 3));
            boundedScan(cache, firstStore, differentArea, 1_000L, scans, snapshot(2, 64, 3));
            boundedScan(cache, firstStore, differentConfig, 1_050L, scans, snapshot(2, 64, 3));
            boundedScan(cache, secondStore, same, 1_051L, scans, snapshot(2, 64, 3));

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

            boundedScan(cache, store, first, 1_000L, scans, snapshot(2, 64, 3));
            boundedScan(cache, store, equivalent, 1_001L, scans, snapshot(2, 64, 3));

            assertEquals(first, equivalent);
            assertEquals(1, scans.get());
        }
    }

    @Test
    void clearingStoreRemovesSnapshots() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            AtomicInteger scans = new AtomicInteger();
            boundedScan(cache, store, key, 1_000L, scans, snapshot(2, 64, 3));

            cache.clear(store);
            ReachableBlockSourceCache.Lookup result = boundedScan(
                    cache,
                    store,
                    key,
                    1_001L,
                    scans,
                    snapshot(2, 64, 3)
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
            ReachableBlockSourceCache.Lookup first = boundedScan(
                    cache,
                    store,
                    smallKey(0, "hytale:hay_bale"),
                    1_000L,
                    scans,
                    snapshot(2, 64, 3)
            );
            com.alechilles.alecstamework.performance.TameworkRuntimePressureService.getInstance()
                    .clearForTests();
            ReachableBlockSourceCache.Lookup second = boundedScan(
                    cache,
                    store,
                    smallKey(4, "hytale:hay_bale"),
                    1_001L,
                    scans,
                    snapshot(6, 64, 3)
            );
            ReachableBlockSourceCache.ColdScanStart deferredStart = cache.startColdScan(
                    store,
                    smallKey(8, "hytale:hay_bale"),
                    1_002L
            );
            ReachableBlockSourceCache.Lookup deferred = lookup(deferredStart);

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
            ReachableBlockSourceCache.SourceKey key = smallKey(0, "hytale:hay_bale");
            CountDownLatch scanStarted = new CountDownLatch(1);
            CountDownLatch allowScan = new CountDownLatch(1);
            CountDownLatch secondFinished = new CountDownLatch(1);
            AtomicReference<ReachableBlockSourceCache.Lookup> firstResult = new AtomicReference<>();
            AtomicReference<ReachableBlockSourceCache.Lookup> secondResult = new AtomicReference<>();
            Thread first = new Thread(() -> {
                ReachableBlockSourceCache.ColdScanStart start = cache.startColdScan(
                        store,
                        key,
                        1_000L
                );
                if (start.status() != ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED) {
                    firstResult.set(lookup(start));
                    return;
                }
                firstResult.set(cache.scanColdSlice(
                        start.permit(),
                        slice -> {
                            scanStarted.countDown();
                            try {
                                allowScan.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return ReachableBlockSourceCache.ScanResult.of(
                                    List.of(new ReachableBlockSourceCache.SourceCoordinate(2, 64, 3)),
                                    slice.probeLimit()
                            );
                        },
                        1_000L
                ));
            });
            first.setDaemon(true);
            first.start();

            assertTrue(scanStarted.await(5, TimeUnit.SECONDS));
            Thread second = new Thread(() -> {
                ReachableBlockSourceCache.ColdScanStart start = cache.startColdScan(
                        store,
                        key,
                        1_001L
                );
                secondResult.set(lookup(start));
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
    void stalePermitCannotPublishAReacquiredSameKey() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = smallKey(0, "hytale:hay_bale");
            ReachableBlockSourceCache.ColdScanStart first = cache.startColdScan(store, key, 0L);

            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, first.status());
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.scanColdSlice(
                            first.permit(),
                            slice -> ReachableBlockSourceCache.ScanResult.empty(1),
                            0L
                    ).status()
            );

            ReachableBlockSourceCache.ColdScanStart second = cache.startColdScan(
                    store,
                    key,
                    50L
            );
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.DEFERRED, second.status());
            second = cache.resumeColdScan(store, key, 50L);
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, second.status());

            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.scanColdSlice(
                            first.permit(),
                            slice -> ReachableBlockSourceCache.ScanResult.of(
                                    List.of(new ReachableBlockSourceCache.SourceCoordinate(2, 64, 2)),
                                    slice.probeLimit()
                            ),
                            50L
                    ).status()
            );
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.lookup(store, key, 50L).status()
            );

            ReachableBlockSourceCache.Lookup completed = cache.scanColdSlice(
                    second.permit(),
                    slice -> ReachableBlockSourceCache.ScanResult.of(
                            List.of(new ReachableBlockSourceCache.SourceCoordinate(3, 64, 3)),
                            slice.probeLimit()
                    ),
                    50L
            );
            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, completed.status());
            assertEquals(3, completed.snapshot().coordinates().get(0).x());
        }
    }

    @Test
    void injectedSnapshotCapEvictsOldestAndPrunesExpiredEntriesFirst() {
        ReachableBlockSourceCache boundedCache = new ReachableBlockSourceCache(2);
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey first = smallKey(0, "hytale:first");
            ReachableBlockSourceCache.SourceKey second = smallKey(4, "hytale:second");
            ReachableBlockSourceCache.SourceKey third = smallKey(8, "hytale:third");
            AtomicInteger scans = new AtomicInteger();

            boundedScan(boundedCache, store, first, 0L, scans, snapshot(2, 64, 3));
            boundedScan(boundedCache, store, second, 1_000L, scans, snapshot(2, 64, 3));
            boundedScan(boundedCache, store, third, 1_500L, scans, snapshot(2, 64, 3));

            ReachableBlockSourceCache.Lookup evicted = boundedScan(
                    boundedCache,
                    store,
                    first,
                    2_000L,
                    scans,
                    snapshot(2, 64, 3)
            );

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, evicted.status());
            assertEquals(2, boundedCache.snapshotCountForTests(store));
            assertEquals(4, scans.get());
        }
        ReachableBlockSourceCache expiredCache = new ReachableBlockSourceCache(2);
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey first = smallKey(0, "hytale:expired-first");
            ReachableBlockSourceCache.SourceKey second = smallKey(4, "hytale:still-live");
            ReachableBlockSourceCache.SourceKey third = smallKey(8, "hytale:new-entry");
            AtomicInteger scans = new AtomicInteger();

            boundedScan(expiredCache, store, first, 0L, scans, snapshot(2, 64, 3));
            boundedScan(expiredCache, store, second, 1_000L, scans, snapshot(6, 64, 3));
            boundedScan(expiredCache, store, third, 3_000L, scans, snapshot(2, 64, 3));

            ReachableBlockSourceCache.Lookup retained = boundedScan(
                    expiredCache,
                    store,
                    second,
                    3_500L,
                    scans,
                    snapshot(6, 64, 3)
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
            ReachableBlockSourceCache.Lookup first = boundedScan(
                    cache,
                    store,
                    smallKey(0, "hytale:first"),
                    1_000L,
                    scans,
                    snapshot(2, 64, 3)
            );
            ReachableBlockSourceCache.Lookup deferred = lookup(cache.startColdScan(
                    store,
                    smallKey(4, "hytale:second"),
                    1_001L
            ));

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, first.status());
            assertEquals(ReachableBlockSourceCache.Lookup.Status.DEFERRED, deferred.status());
            assertEquals(1, scans.get());
        }
    }

    @Test
    void defaultRangeColdScanResumesWithAtMost512ProbesPerPass() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            AtomicInteger passes = new AtomicInteger();
            AtomicInteger maximumProbes = new AtomicInteger();
            long nowMs = 1_000L;

            ReachableBlockSourceCache.ColdScanStart start = cache.startColdScan(store, key, nowMs);
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, start.status());
            ReachableBlockSourceCache.Lookup result;
            while (true) {
                result = cache.scanColdSlice(
                        start.permit(),
                        slice -> {
                            passes.incrementAndGet();
                            maximumProbes.accumulateAndGet(
                                    slice.probeLimit(),
                                    Math::max
                            );
                            return ReachableBlockSourceCache.ScanResult.of(
                                    List.of(new ReachableBlockSourceCache.SourceCoordinate(
                                            slice.startX(),
                                            slice.startY(),
                                            slice.startZ()
                                    )),
                                    slice.probeLimit()
                            );
                        },
                        nowMs
                );
                if (result.status() != ReachableBlockSourceCache.Lookup.Status.DEFERRED) {
                    break;
                }
                nowMs += 50L;
                start = cache.resumeColdScan(store, key, nowMs);
                assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, start.status());
            }

            assertEquals(ReachableBlockSourceCache.Lookup.Status.HIT, result.status());
            assertTrue(passes.get() > 1);
            assertTrue(maximumProbes.get() <= ReachableBlockSourceCache.MAX_BLOCK_PROBES_PER_PASS);
            assertTrue(passes.get() >= 18);
        }
    }

    @Test
    void clearingPartialScanConsumesPermitAndPreventsStalePublication() {
        try (TestEntityComponentStore store = newStore()) {
            ReachableBlockSourceCache.SourceKey key = key(0, "hytale:hay_bale");
            ReachableBlockSourceCache.ColdScanStart start = cache.startColdScan(store, key, 1_000L);
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, start.status());
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.scanColdSlice(
                            start.permit(),
                            slice -> ReachableBlockSourceCache.ScanResult.empty(1),
                            1_000L
                    ).status()
            );

            ReachableBlockSourceCache.ColdScanStart resumed = cache.resumeColdScan(
                    store,
                    key,
                    1_050L
            );
            assertEquals(ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED, resumed.status());
            cache.clear(store);
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    cache.scanColdSlice(
                            resumed.permit(),
                            slice -> ReachableBlockSourceCache.ScanResult.of(
                                    List.of(new ReachableBlockSourceCache.SourceCoordinate(1, 64, 1)),
                                    slice.probeLimit()
                            ),
                            1_001L
                    ).status()
            );
            assertEquals(
                    ReachableBlockSourceCache.Lookup.Status.ABSENT,
                    cache.lookup(store, key, 1_001L).status()
            );
        }
    }

    private static ReachableBlockSourceCache.Snapshot snapshot(int x, int y, int z) {
        return new ReachableBlockSourceCache.Snapshot(
                List.of(new ReachableBlockSourceCache.SourceCoordinate(x, y, z))
        );
    }

    private static ReachableBlockSourceCache.Lookup boundedScan(
            ReachableBlockSourceCache cache,
            TestEntityComponentStore store,
            ReachableBlockSourceCache.SourceKey key,
            long nowMs,
            AtomicInteger scans,
            ReachableBlockSourceCache.Snapshot desiredSnapshot) {
        ReachableBlockSourceCache.Lookup existing = cache.lookup(store, key, nowMs);
        if (existing.status() != ReachableBlockSourceCache.Lookup.Status.ABSENT) {
            return existing;
        }
        ReachableBlockSourceCache.ColdScanStart start = cache.startColdScan(store, key, nowMs);
        if (start.status() != ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED) {
            return lookup(start);
        }
        scans.incrementAndGet();
        long admissionNowMs = nowMs;
        while (true) {
            ReachableBlockSourceCache.Lookup result = cache.scanColdSlice(
                    start.permit(),
                    slice -> ReachableBlockSourceCache.ScanResult.of(
                            desiredSnapshot.coordinates(),
                            slice.probeLimit()
                    ),
                    nowMs
            );
            if (result.status() != ReachableBlockSourceCache.Lookup.Status.DEFERRED) {
                return result;
            }
            admissionNowMs += 50L;
            start = cache.resumeColdScan(store, key, admissionNowMs);
            if (start.status() != ReachableBlockSourceCache.ColdScanStart.Status.ACQUIRED) {
                return lookup(start);
            }
        }
    }

    private static ReachableBlockSourceCache.Lookup lookup(
            ReachableBlockSourceCache.ColdScanStart start) {
        return switch (start.status()) {
            case HIT -> new ReachableBlockSourceCache.Lookup(
                    ReachableBlockSourceCache.Lookup.Status.HIT,
                    start.snapshot()
            );
            case MISS -> new ReachableBlockSourceCache.Lookup(
                    ReachableBlockSourceCache.Lookup.Status.MISS,
                    start.snapshot()
            );
            case ACQUIRED, DEFERRED -> new ReachableBlockSourceCache.Lookup(
                    ReachableBlockSourceCache.Lookup.Status.DEFERRED,
                    null
            );
        };
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

    private static ReachableBlockSourceCache.SourceKey smallKey(int x, String blockType) {
        return ReachableBlockSourceCache.keyFor(
                "world-a",
                x,
                64,
                0,
                null,
                List.of(blockType),
                1.0,
                0
        );
    }

    private static TestEntityComponentStore newStore() {
        return new TestEntityComponentStore(new EntityStore(null));
    }
}
