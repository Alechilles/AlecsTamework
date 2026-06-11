package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightStatus;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class NeedsResourcePathPreflightServiceTest {

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
    void computingEntriesAreClearedWhenServiceIsReset() {
        NeedsResourcePathPreflightService service = new NeedsResourcePathPreflightService();
        FakeComputation computation = new FakeComputation(PathPreflightStatus.COMPUTING);

        service.preflight(keyFor(4), () -> computation, 1_000L);
        service.clearForTests();

        assertEquals(1, computation.clearCalls());
        assertEquals(0, service.cacheSizeForTests());
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

    private static NeedsResourcePathPreflightService.PreflightKey keyFor(int index) {
        return NeedsResourcePathPreflightService.PreflightKey.from(
                new UUID(0L, index),
                "test-world",
                "FoodContainer",
                "Walk",
                new Vector3d(index, 64.0, 0.0),
                new Vector3d(index + 4.0, 64.0, 0.0)
        );
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
}
