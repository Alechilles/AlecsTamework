package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class NeedsResourceAreaSearchCacheTest {
    @Test
    void oneRankedSnapshotLetsNpcPredicatesSelectDifferentCandidates() {
        NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                List.of(
                        new NeedsResourceCandidates.Candidate(2, 64, 2, 1.5),
                        new NeedsResourceCandidates.Candidate(4, 64, 2, 2.0)
                ),
                true,
                false,
                1_500L
        );

        NeedsResourceCandidates.Candidate firstNpc = snapshot.select(
                new Vector3d(0.0, 64.0, 0.0),
                8.0,
                1,
                candidate -> candidate.x() < 3
        );
        NeedsResourceCandidates.Candidate secondNpc = snapshot.select(
                new Vector3d(0.0, 64.0, 0.0),
                8.0,
                1,
                candidate -> candidate.x() >= 3
        );

        assertEquals(2, firstNpc.x());
        assertEquals(4, secondNpc.x());
    }

    @Test
    void snapshotCopiesInputAndCapsRankedCandidates() {
        List<NeedsResourceCandidates.Candidate> input = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            input.add(new NeedsResourceCandidates.Candidate(index, 64, 0, 2.0));
        }

        NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                input,
                true,
                false,
                1_500L
        );
        input.clear();
        input.add(new NeedsResourceCandidates.Candidate(99, 64, 99, 2.0));

        assertEquals(16, snapshot.candidates().size());
        assertEquals(0, snapshot.candidates().get(0).x());
        assertEquals(15, snapshot.candidates().get(15).x());
    }

    @Test
    void cacheRoundTripKeepsImmutableRankedSnapshot() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);
        List<NeedsResourceCandidates.Candidate> input = new ArrayList<>(List.of(
                new NeedsResourceCandidates.Candidate(12, 64, 12, 2.0),
                new NeedsResourceCandidates.Candidate(13, 64, 12, 2.0)
        ));
        NeedsResourceCandidates.Snapshot snapshot = new NeedsResourceCandidates.Snapshot(
                input,
                true,
                true,
                1_500L
        );

        cache.put(key, snapshot, 1_000L);
        input.set(0, new NeedsResourceCandidates.Candidate(99, 64, 99, 2.0));

        NeedsResourceCandidates.Snapshot cached = cache.getSnapshot(key, 1_100L);

        assertEquals(12, cached.candidates().get(0).x());
        assertEquals(13, cached.candidates().get(1).x());
        assertTrue(cached.foundSource());
        assertTrue(cached.sourceInConsumeRange());
        assertEquals(1_400L, cached.ttlMs());
    }

    @Test
    void nearbyPositionsShareAreaKeyWithoutNpcUuid() {
        NeedsResourceAreaSearchCache.AreaKey first = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.25, 64.0, 10.25), 16.0, 2, 3.0, 0);
        NeedsResourceAreaSearchCache.AreaKey second = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(11.75, 64.0, 11.75), 16.0, 2, 3.0, 0);

        assertEquals(first, second);
    }

    @Test
    void cachedTargetIsCopiedOnWriteAndRead() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);
        Vector3d target = new Vector3d(12.0, 64.0, 12.0);

        cache.put(key, NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(target, 2.0, 1_500L), 1_000L);
        target.set(99.0, 99.0, 99.0);
        NeedsResourceAreaSearchCache.AreaSearchSnapshot cached =
                cache.get(key, new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 1_100L);

        assertTrue(cached.hasTarget());
        assertNotSame(target, cached.target());
        assertEquals(12.0, cached.target().x);
    }

    @Test
    void expiredEntriesAreNotReturned() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

        cache.put(key, NeedsResourceAreaSearchCache.AreaSearchSnapshot.sourceAbsentMiss(15_000L), 1_000L);

        assertNull(cache.get(key, new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 16_001L));
    }

    @Test
    void targetOutsideCurrentRadiusIsRejected() {
        NeedsResourceAreaSearchCache cache = new NeedsResourceAreaSearchCache(16);
        NeedsResourceAreaSearchCache.AreaKey key = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

        cache.put(
                key,
                NeedsResourceAreaSearchCache.AreaSearchSnapshot.hit(new Vector3d(20.0, 64.0, 10.0), 2.0, 1_500L),
                1_000L
        );

        assertNull(cache.get(key, new Vector3d(10.0, 64.0, 10.0), 4.0, 2, 1_100L));
    }

    @Test
    void sourcePresentMissIsNotAreaReusable() {
        assertFalse(NeedsResourceAreaSearchCache.shouldShareResult(false, true));
    }

    @Test
    void hitAndSourceAbsentMissAreAreaReusable() {
        assertTrue(NeedsResourceAreaSearchCache.shouldShareResult(true, true));
        assertTrue(NeedsResourceAreaSearchCache.shouldShareResult(false, false));
    }

    @Test
    void foodItemHashSeparatesAreaKeys() {
        NeedsResourceAreaSearchCache.AreaKey beefKey = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 123);
        NeedsResourceAreaSearchCache.AreaKey wheatKey = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 456);

        assertFalse(beefKey.equals(wheatKey));
    }

    @Test
    void foodAndWaterDoNotShareAreaKeys() {
        NeedsResourceAreaSearchCache.AreaKey foodKey = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "food_container", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);
        NeedsResourceAreaSearchCache.AreaKey waterKey = NeedsResourceAreaSearchCache.AreaKey.from(
                "world", "water", new Vector3d(10.0, 64.0, 10.0), 16.0, 2, 3.0, 0);

        assertFalse(foodKey.equals(waterKey));
    }
}
