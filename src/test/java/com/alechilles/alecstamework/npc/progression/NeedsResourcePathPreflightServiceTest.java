package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightStatus;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NeedsResourcePathPreflightServiceTest {
    @AfterEach
    void clearRuntimePressure() {
        TameworkRuntimePressureService.getInstance().clearForTests();
    }

    @Test
    void readyResultIsCachedWithoutReRunningComputation() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(1);
        AtomicInteger factoryCalls = new AtomicInteger();

        PathPreflightResult first = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_001L
        );

        assertTrue(first.ready());
        assertTrue(second.ready());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void computingResultKeepsTheSameBoundedComputationPending() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(2);
        FakeComputation computation = new FakeComputation(
                PathPreflightStatus.COMPUTING,
                PathPreflightStatus.READY
        );
        AtomicInteger factoryCalls = new AtomicInteger();

        PathPreflightResult first = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return computation;
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_050L
        );

        assertTrue(first.computing());
        assertTrue(second.ready());
        assertEquals(1, factoryCalls.get());
        assertEquals(2, computation.computeCalls());
        assertEquals(1, computation.clearCalls());
    }

    @Test
    void noPathResultIsCachedWithoutReRunningComputation() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(3);
        AtomicInteger factoryCalls = new AtomicInteger();

        PathPreflightResult first = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_001L
        );

        assertTrue(first.noPath());
        assertTrue(second.noPath());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void noPathResultExpiresQuicklyEnoughForSensorFalseNegatives() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(33);
        AtomicInteger factoryCalls = new AtomicInteger();

        PathPreflightResult first = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                key,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L + NeedsResourcePathPreflightService.NO_PATH_TTL_MS + 1L
        );

        assertTrue(first.noPath());
        assertTrue(second.ready());
        assertTrue(NeedsResourcePathPreflightService.NO_PATH_TTL_MS <= 10_000L);
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void noPathCacheTtlUsesRuntimePressureBackoff() {
        TameworkRuntimePressureService pressure = TameworkRuntimePressureService.getInstance();
        long baseTtl = NeedsResourcePathPreflightService.terminalTtlMsForTests(PathPreflightStatus.NO_PATH);

        for (int i = 0; i < 700; i++) {
            pressure.recordWork(RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, 100_000L, System.currentTimeMillis());
        }

        assertTrue(NeedsResourcePathPreflightService.terminalTtlMsForTests(PathPreflightStatus.NO_PATH) > baseTtl);
    }

    @Test
    void computingEntriesAreClearedWhenServiceIsReset() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        FakeComputation computation = new FakeComputation(PathPreflightStatus.COMPUTING);

        service.preflight(keyFor(4), () -> computation, 1_000L);
        service.preflight(
                keyFor(5),
                () -> new FakeComputation(PathPreflightStatus.READY),
                1_000L
        );
        service.clearForTests();

        assertEquals(1, computation.clearCalls());
        assertEquals(0, service.cacheSizeForTests());
        assertEquals(0, service.recentReadySizeForTests());
    }

    @Test
    void cacheSizeRemainsBoundedByPreflightConstant() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        int maxEntries = NeedsResourcePathPreflightService.PRECHECK_CACHE_MAX_ENTRIES;

        for (int i = 0; i < maxEntries + 25; i++) {
            long nowMs = 1_000L + ((long) i / 16L) * 50L;
            service.preflight(
                    keyFor(i + 10),
                    () -> new FakeComputation(PathPreflightStatus.NO_PATH),
                    nowMs
            );
        }

        assertTrue(service.cacheSizeForTests() <= maxEntries);
    }

    @Test
    void looseGoalAcceptsNearbyPathNodeWithoutExactFinalProbe() {
        assertTrue(NeedsSeekPathEvaluator.isWithinPreflightGoal(0.65, 3.99, 4.0));
    }

    @Test
    void looseGoalStillRejectsBadHeightOrDistance() {
        assertFalse(NeedsSeekPathEvaluator.isWithinPreflightGoal(1.25, 1.0, 4.0));
        assertFalse(NeedsSeekPathEvaluator.isWithinPreflightGoal(0.0, 4.25, 4.0));
    }

    @Test
    void preflightCacheKeyIncludesApproachDistance() {
        NeedsResourcePathPreflightService.PreflightKey nearKey = keyFor(40, 1.0);
        NeedsResourcePathPreflightService.PreflightKey farKey = keyFor(40, 3.0);

        assertFalse(nearKey.equals(farKey));
    }

    @Test
    void recentReadyTargetAvoidsRecomputingWhenStartMovesNearby() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(50, 2.0);
        NeedsResourcePathPreflightService.PreflightKey secondKey = NeedsResourcePathPreflightService.PreflightKey.from(
                new UUID(0L, 50L),
                "test-world",
                "FoodContainer",
                "Walk",
                new Vector3d(51.0, 64.0, 0.0),
                new Vector3d(54.0, 64.0, 0.0),
                2.0
        );

        PathPreflightResult first = service.preflight(
                firstKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                secondKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );

        assertTrue(first.ready());
        assertTrue(second.ready());
        assertEquals("path_preflight_recent_ready_target", second.reason());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void recentReadyTargetExpires() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(60, 2.0);
        NeedsResourcePathPreflightService.PreflightKey secondKey = NeedsResourcePathPreflightService.PreflightKey.from(
                new UUID(0L, 60L),
                "test-world",
                "FoodContainer",
                "Walk",
                new Vector3d(61.0, 64.0, 0.0),
                new Vector3d(64.0, 64.0, 0.0),
                2.0
        );

        service.preflight(
                firstKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L
        );
        PathPreflightResult second = service.preflight(
                secondKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_000L + NeedsResourcePreflightPolicy.RECENT_READY_TTL_MS + 1L
        );

        assertTrue(second.noPath());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void recentReadyLeaseRemainsReusableUntil9999MsAndExpiresAt10000Ms() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(
                new UUID(0L, 61L),
                "test-world",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );
        NeedsResourcePathPreflightService.PreflightKey progressedKey = keyFor(
                new UUID(0L, 61L),
                "test-world",
                "Water",
                new Vector3d(1.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );

        service.preflight(
                firstKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L
        );
        PathPreflightResult beforeExpiry = service.preflight(
                progressedKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                10_999L
        );
        PathPreflightResult atExpiry = service.preflight(
                progressedKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                11_000L
        );

        assertTrue(beforeExpiry.ready());
        assertTrue(atExpiry.noPath());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void recentReadyLeaseReusesAStartThatMovesForwardAlongTheTargetCorridor() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(
                new UUID(0L, 62L),
                "test-world",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );
        NeedsResourcePathPreflightService.PreflightKey progressedKey = keyFor(
                new UUID(0L, 62L),
                "test-world",
                "Water",
                new Vector3d(3.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );

        PathPreflightResult first = service.preflight(
                firstKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.READY);
                },
                1_000L
        );
        PathPreflightResult progressed = service.preflight(
                progressedKey,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );

        assertTrue(first.ready());
        assertTrue(progressed.ready());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void recentReadyLeaseRecomputesAfterMovementAwayFromTarget() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(
                new UUID(0L, 63L),
                "test-world",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );
        NeedsResourcePathPreflightService.PreflightKey awayKey = keyFor(
                new UUID(0L, 63L),
                "test-world",
                "Water",
                new Vector3d(-1.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );

        service.preflight(firstKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.READY);
        }, 1_000L);
        PathPreflightResult result = service.preflight(awayKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.NO_PATH);
        }, 1_100L);

        assertTrue(result.noPath());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void recentReadyLeaseRecomputesAfterAHorizontalSideJumpBeyondTheCorridor() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(
                new UUID(0L, 64L),
                "test-world",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );
        NeedsResourcePathPreflightService.PreflightKey sideJumpKey = keyFor(
                new UUID(0L, 64L),
                "test-world",
                "Water",
                new Vector3d(3.0, 64.0, 5.0),
                new Vector3d(10.0, 64.0, 0.0)
        );

        service.preflight(firstKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.READY);
        }, 1_000L);
        PathPreflightResult result = service.preflight(sideJumpKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.NO_PATH);
        }, 1_100L);

        assertTrue(result.noPath());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void recentReadyLeaseRecomputesAfterAStartMovesBeyondVerticalTolerance() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        AtomicInteger factoryCalls = new AtomicInteger();
        NeedsResourcePathPreflightService.PreflightKey firstKey = keyFor(
                new UUID(0L, 65L),
                "test-world",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );
        NeedsResourcePathPreflightService.PreflightKey verticalJumpKey = keyFor(
                new UUID(0L, 65L),
                "test-world",
                "Water",
                new Vector3d(3.0, 67.0, 0.0),
                new Vector3d(10.0, 64.0, 0.0)
        );

        service.preflight(firstKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.READY);
        }, 1_000L);
        PathPreflightResult result = service.preflight(verticalJumpKey, () -> {
            factoryCalls.incrementAndGet();
            return new FakeComputation(PathPreflightStatus.NO_PATH);
        }, 1_100L);

        assertTrue(result.noPath());
        assertEquals(2, factoryCalls.get());
    }

    @Test
    void targetInvalidationRemovesOnlyTheMatchingNpcWorldResourceAndBlock() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        UUID npc = new UUID(0L, 66L);
        Vector3d target = new Vector3d(10.25, 64.75, -2.25);
        NeedsResourcePathPreflightService.PreflightKey matching = keyFor(
                npc, "world-a", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey matchingProgress = keyFor(
                npc, "world-a", "Water", new Vector3d(1.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey otherNpc = keyFor(
                new UUID(0L, 67L), "world-a", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey otherWorld = keyFor(
                npc, "world-b", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey otherResource = keyFor(
                npc, "world-a", "FoodContainer", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey otherTarget = keyFor(
                npc,
                "world-a",
                "Water",
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(11.25, 64.75, -2.25)
        );
        FakeComputation computing = new FakeComputation(PathPreflightStatus.COMPUTING);
        AtomicInteger factoryCalls = new AtomicInteger();

        service.preflight(matching, () -> ready(factoryCalls), 1_000L);
        service.preflight(otherNpc, () -> ready(factoryCalls), 1_000L);
        service.preflight(otherWorld, () -> ready(factoryCalls), 1_000L);
        service.preflight(otherResource, () -> ready(factoryCalls), 1_000L);
        service.preflight(otherTarget, () -> ready(factoryCalls), 1_000L);
        NeedsResourcePathPreflightService.PreflightKey matchingComputing = keyFor(
                npc, "world-a", "Water", new Vector3d(-1.0, 64.0, 0.0), target
        );
        service.preflight(matchingComputing, () -> computing, 1_000L);

        service.invalidateTarget(npc, "WORLD-A", "water", new Vector3d(10.99, 64.01, -2.01));

        PathPreflightResult invalidated = service.preflight(
                matchingProgress,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );

        assertTrue(invalidated.noPath());
        assertTrue(service.preflight(otherNpc, () -> failComputation(factoryCalls), 1_100L).ready());
        assertTrue(service.preflight(otherWorld, () -> failComputation(factoryCalls), 1_100L).ready());
        assertTrue(service.preflight(otherResource, () -> failComputation(factoryCalls), 1_100L).ready());
        assertTrue(service.preflight(otherTarget, () -> failComputation(factoryCalls), 1_100L).ready());
        assertEquals(1, computing.clearCalls());
    }

    @Test
    void absentWorldTargetInvalidationRemovesMatchingLeasesAcrossWorlds() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        UUID npc = new UUID(0L, 68L);
        Vector3d target = new Vector3d(4.25, 64.25, 8.25);
        NeedsResourcePathPreflightService.PreflightKey worldA = keyFor(
                npc, "world-a", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey worldB = keyFor(
                npc, "world-b", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        AtomicInteger factoryCalls = new AtomicInteger();
        service.preflight(worldA, () -> ready(factoryCalls), 1_000L);
        service.preflight(worldB, () -> ready(factoryCalls), 1_000L);

        service.invalidateTarget(npc, null, "Water", target);

        PathPreflightResult resultA = service.preflight(
                worldA,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );
        PathPreflightResult resultB = service.preflight(
                worldB,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );

        assertTrue(resultA.noPath());
        assertTrue(resultB.noPath());
        assertEquals(4, factoryCalls.get());
    }

    @Test
    void worldCleanupClearsReadyAndComputingEntriesAndLeavesAnotherWorldIntact() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        UUID npc = new UUID(0L, 69L);
        Vector3d target = new Vector3d(10.25, 64.25, 0.25);
        NeedsResourcePathPreflightService.PreflightKey worldA = keyFor(
                npc, "world-a", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey worldAComputing = keyFor(
                npc, "world-a", "Water", new Vector3d(-1.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey worldB = keyFor(
                npc, "world-b", "Water", new Vector3d(0.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey worldAProgress = keyFor(
                npc, "world-a", "Water", new Vector3d(1.0, 64.0, 0.0), target
        );
        NeedsResourcePathPreflightService.PreflightKey worldBProgress = keyFor(
                npc, "world-b", "Water", new Vector3d(1.0, 64.0, 0.0), target
        );
        FakeComputation computing = new FakeComputation(PathPreflightStatus.COMPUTING);
        AtomicInteger factoryCalls = new AtomicInteger();

        service.preflight(worldA, () -> ready(factoryCalls), 1_000L);
        service.preflight(worldAComputing, () -> computing, 1_000L);
        service.preflight(worldB, () -> ready(factoryCalls), 1_000L);

        service.clearWorld("WORLD-A");

        PathPreflightResult worldAResult = service.preflight(
                worldAProgress,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );
        PathPreflightResult worldBResult = service.preflight(
                worldBProgress,
                () -> {
                    factoryCalls.incrementAndGet();
                    return new FakeComputation(PathPreflightStatus.NO_PATH);
                },
                1_100L
        );

        assertTrue(worldAResult.noPath());
        assertTrue(worldBResult.ready());
        assertEquals(1, computing.clearCalls());
    }

    @Test
    void recentReadyAdmissionNeverExceedsTheHardBound() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        int maxEntries = NeedsResourcePathPreflightService.PRECHECK_CACHE_MAX_ENTRIES;

        for (int i = 0; i < maxEntries + 25; i++) {
            long nowMs = 1_000L + ((long) i / 512L) * 50L;
            NeedsResourcePathPreflightService.PreflightKey key = keyFor(
                    new UUID(0L, 100_000L + i),
                    "bound-world",
                    "Water",
                    new Vector3d(i, 64.0, 0.0),
                    new Vector3d(i + 4.0, 64.0, 0.0)
            );
            service.preflight(
                    key,
                    () -> new FakeComputation(PathPreflightStatus.READY),
                    nowMs
            );
        }

        assertTrue(service.recentReadySizeForTests() <= maxEntries);
    }

    @Test
    void sharedAndIsolatedServicesHaveTheSameResultContract() {
        NeedsResourcePathPreflightService shared = NeedsResourcePathPreflightService.shared();
        shared.clearForTests();
        NeedsResourcePathPreflightService isolated = new NeedsResourcePathPreflightService();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(70);

        PathPreflightResult sharedFirst = shared.preflight(
                key,
                () -> new FakeComputation(PathPreflightStatus.READY),
                1_000L
        );
        PathPreflightResult isolatedFirst = isolated.preflight(
                key,
                () -> new FakeComputation(PathPreflightStatus.READY),
                1_000L
        );
        PathPreflightResult sharedCached = shared.preflight(
                key,
                () -> new FakeComputation(PathPreflightStatus.NO_PATH),
                1_001L
        );
        PathPreflightResult isolatedCached = isolated.preflight(
                key,
                () -> new FakeComputation(PathPreflightStatus.NO_PATH),
                1_001L
        );

        assertTrue(sharedFirst.ready());
        assertTrue(isolatedFirst.ready());
        assertTrue(sharedCached.ready());
        assertTrue(isolatedCached.ready());
    }

    @Test
    void invalidationDuringComputeDoesNotClearConcurrentlyOrPublishStaleReadyState() throws Exception {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        service.clearForTests();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(80_000);
        BlockingComputation computation = new BlockingComputation();
        AtomicReference<PathPreflightResult> result = new AtomicReference<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        CountDownLatch invalidationFinished = new CountDownLatch(1);
        AtomicReference<Throwable> invalidationFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(service.preflight(key, () -> computation, 1_000L));
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        });
        worker.setDaemon(true);
        worker.start();

        assertTrue(computation.computeStarted.await(5, TimeUnit.SECONDS));
        Thread invalidator = new Thread(() -> {
            try {
                service.invalidateTarget(
                        key.npcUuid(),
                        key.worldName(),
                        key.resourceType(),
                        new Vector3d(key.targetX() + 0.25, key.targetY() + 0.25, key.targetZ() + 0.25)
                );
            } catch (Throwable failure) {
                invalidationFailure.set(failure);
            } finally {
                invalidationFinished.countDown();
            }
        });
        invalidator.setDaemon(true);
        invalidator.start();
        boolean invalidationReturnedBeforeComputeFinished =
                invalidationFinished.await(1, TimeUnit.SECONDS);

        computation.allowCompute.countDown();
        worker.join(5_000L);
        invalidator.join(5_000L);

        assertTrue(invalidationReturnedBeforeComputeFinished);
        assertFalse(worker.isAlive());
        assertFalse(invalidator.isAlive());
        assertTrue(workerFailure.get() == null);
        assertTrue(invalidationFailure.get() == null);
        PathPreflightResult completed = result.get();
        assertNotNull(completed);
        assertFalse(completed.ready());
        assertFalse(completed.computing());
        assertEquals(0, service.cacheSizeForTests());
        assertEquals(0, service.recentReadySizeForTests());
        assertEquals(1, computation.clearCalls());
        assertFalse(computation.clearDuringCompute.get());
    }

    @Test
    void secondCallerDefersWhenSameComputationIsAlreadyRunning() throws Exception {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        service.clearForTests();
        NeedsResourcePathPreflightService.PreflightKey key = keyFor(80_001);
        BlockingComputation computation = new BlockingComputation();
        AtomicReference<PathPreflightResult> firstResult = new AtomicReference<>();
        AtomicReference<PathPreflightResult> secondResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        CountDownLatch secondFinished = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                firstResult.set(service.preflight(key, () -> computation, 1_000L));
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        first.setDaemon(true);
        first.start();

        assertTrue(computation.computeStarted.await(5, TimeUnit.SECONDS));
        Thread second = new Thread(() -> {
            try {
                secondResult.set(service.preflight(key, () -> computation, 1_000L));
            } catch (Throwable failure) {
                secondFailure.set(failure);
            } finally {
                secondFinished.countDown();
            }
        });
        second.setDaemon(true);
        second.start();

        boolean secondReturnedBeforeRelease = secondFinished.await(1, TimeUnit.SECONDS);
        computation.allowCompute.countDown();
        first.join(5_000L);
        second.join(5_000L);

        assertTrue(secondReturnedBeforeRelease);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertTrue(firstFailure.get() == null);
        assertTrue(secondFailure.get() == null);
        assertNotNull(firstResult.get());
        assertNotNull(secondResult.get());
        assertTrue(firstResult.get().ready());
        assertTrue(secondResult.get().computing());
        assertEquals(1, computation.computeCalls());
        assertFalse(computation.concurrentCompute.get());
    }

    private static NeedsResourcePathPreflightService.PreflightKey keyFor(int index) {
        return keyFor(index, 2.0);
    }

    private static NeedsResourcePathPreflightService.PreflightKey keyFor(int index, double approachDistance) {
        return NeedsResourcePathPreflightService.PreflightKey.from(
                new UUID(0L, index),
                "test-world",
                "FoodContainer",
                "Walk",
                new Vector3d(index, 64.0, 0.0),
                new Vector3d(index + 4.0, 64.0, 0.0),
                approachDistance
        );
    }

    private static NeedsResourcePathPreflightService.PreflightKey keyFor(UUID npcUuid,
                                                                          String worldName,
                                                                          String resourceType,
                                                                          Vector3d start,
                                                                          Vector3d target) {
        return NeedsResourcePathPreflightService.PreflightKey.from(
                npcUuid,
                worldName,
                resourceType,
                "Walk",
                start,
                target,
                2.0
        );
    }

    private static FakeComputation ready(AtomicInteger factoryCalls) {
        factoryCalls.incrementAndGet();
        return new FakeComputation(PathPreflightStatus.READY);
    }

    private static FakeComputation failComputation(AtomicInteger factoryCalls) {
        factoryCalls.incrementAndGet();
        return new FakeComputation(PathPreflightStatus.NO_PATH);
    }

    private static final class FakeComputation implements NeedsResourcePathPreflightService.PathComputation {
        private final PathPreflightStatus[] statuses;
        private int computeCalls;
        private int clearCalls;

        private FakeComputation(PathPreflightStatus... statuses) {
            this.statuses = statuses;
        }

        @Override
        public PathPreflightStatus compute(int maxNodes) {
            int index = Math.min(computeCalls, statuses.length - 1);
            computeCalls++;
            return statuses[index];
        }

        @Override
        public void clear() {
            clearCalls++;
        }

        private int computeCalls() {
            return computeCalls;
        }

        private int clearCalls() {
            return clearCalls;
        }
    }

    private static final class BlockingComputation implements NeedsResourcePathPreflightService.PathComputation {
        private final CountDownLatch computeStarted = new CountDownLatch(1);
        private final CountDownLatch allowCompute = new CountDownLatch(1);
        private final AtomicBoolean computing = new AtomicBoolean();
        private final AtomicBoolean clearDuringCompute = new AtomicBoolean();
        private final AtomicBoolean concurrentCompute = new AtomicBoolean();
        private final AtomicInteger activeComputeCalls = new AtomicInteger();
        private final AtomicInteger computeCalls = new AtomicInteger();
        private final AtomicInteger clearCalls = new AtomicInteger();

        @Override
        public PathPreflightStatus compute(int maxNodes) {
            computeCalls.incrementAndGet();
            int active = activeComputeCalls.incrementAndGet();
            if (active > 1) {
                concurrentCompute.set(true);
            }
            computing.set(true);
            computeStarted.countDown();
            try {
                allowCompute.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return PathPreflightStatus.NO_PATH;
            } finally {
                if (activeComputeCalls.decrementAndGet() == 0) {
                    computing.set(false);
                }
            }
            return PathPreflightStatus.READY;
        }

        @Override
        public void clear() {
            if (computing.get()) {
                clearDuringCompute.set(true);
            }
            clearCalls.incrementAndGet();
        }

        private int clearCalls() {
            return clearCalls.get();
        }

        private int computeCalls() {
            return computeCalls.get();
        }
    }
}
