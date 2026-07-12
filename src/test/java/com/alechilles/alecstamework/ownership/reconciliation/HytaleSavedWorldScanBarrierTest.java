package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HytaleSavedWorldScanBarrierTest {
    @Test
    void successfulLeaseHoldsEverySaveSourceUntilIdempotentRelease() {
        FakeWorld first = new FakeWorld(false);
        FakeWorld second = new FakeWorld(false);
        FakeAccess access = new FakeAccess(List.of(first, second));

        HytaleSavedWorldScanBarrier.Lease lease =
                new HytaleSavedWorldScanBarrier(access).acquireAsync().join();

        assertTrue(access.locked);
        assertEquals(1, access.pendingWaits);
        assertTrue(first.paused);
        assertTrue(second.paused);

        lease.releaseAsync().join();
        lease.releaseAsync().join();

        assertFalse(access.locked);
        assertEquals(1, access.unlocks);
        assertEquals(1, first.resumes);
        assertEquals(1, second.resumes);
    }

    @Test
    void acquisitionFailureResumesEveryPartiallyPausedWorldAndUnlocksUniverse() {
        FakeWorld successful = new FakeWorld(false);
        FakeWorld failed = new FakeWorld(true);
        FakeAccess access = new FakeAccess(List.of(successful, failed));

        assertThrows(
                CompletionException.class,
                () -> new HytaleSavedWorldScanBarrier(access).acquireAsync().join()
        );

        assertFalse(access.locked);
        assertEquals(1, access.unlocks);
        assertEquals(1, successful.resumes);
        assertEquals(1, failed.resumes);
    }

    @Test
    void synchronousResumeRejectionStillUnlocksUniverseAndLeaseRetriesCleanup() {
        FakeWorld rejecting = new FakeWorld(false);
        rejecting.synchronousResumeFailures = 1;
        FakeWorld healthy = new FakeWorld(false);
        FakeAccess access = new FakeAccess(List.of(rejecting, healthy));
        HytaleSavedWorldScanBarrier.Lease lease =
                new HytaleSavedWorldScanBarrier(access).acquireAsync().join();

        assertThrows(CompletionException.class, () -> lease.releaseAsync().join());

        assertFalse(access.locked);
        assertEquals(1, access.unlocks);
        assertTrue(rejecting.paused);
        assertFalse(healthy.paused);
        assertEquals(1, rejecting.resumeAttempts);
        assertEquals(1, healthy.resumeAttempts);

        lease.releaseAsync().join();

        assertFalse(rejecting.paused);
        assertEquals(2, rejecting.resumeAttempts);
        assertEquals(1, rejecting.resumes);
        assertEquals(1, access.unlocks);
    }

    @Test
    void backgroundAndWorldUnlockFailuresAreBothAttemptedAndRemainRetryable() {
        AtomicBoolean backgroundPaused = new AtomicBoolean(true);
        AtomicBoolean worldLocked = new AtomicBoolean(true);
        AtomicInteger backgroundAttempts = new AtomicInteger();
        AtomicInteger unlockAttempts = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                HytaleSavedWorldScanBarrier.releaseWorldState(
                        backgroundPaused,
                        worldLocked,
                        () -> {
                            backgroundAttempts.incrementAndGet();
                            throw new IllegalStateException("background resume failed");
                        },
                        () -> {
                            unlockAttempts.incrementAndGet();
                            throw new IllegalStateException("world unlock failed");
                        }));

        assertEquals(1, backgroundAttempts.get());
        assertEquals(1, unlockAttempts.get());
        assertTrue(backgroundPaused.get());
        assertTrue(worldLocked.get());
        assertEquals(1, failure.getCause().getSuppressed().length);

        HytaleSavedWorldScanBarrier.releaseWorldState(
                backgroundPaused, worldLocked,
                backgroundAttempts::incrementAndGet,
                unlockAttempts::incrementAndGet);

        assertFalse(backgroundPaused.get());
        assertFalse(worldLocked.get());
        assertEquals(2, backgroundAttempts.get());
        assertEquals(2, unlockAttempts.get());
    }

    @Test
    void worldAndUniverseCleanupFailuresAreAggregatedBeforeRetry() {
        FakeWorld world = new FakeWorld(false);
        world.synchronousResumeFailures = 1;
        FakeAccess access = new FakeAccess(List.of(world));
        access.unlockFailures = 1;
        HytaleSavedWorldScanBarrier.Lease lease =
                new HytaleSavedWorldScanBarrier(access).acquireAsync().join();

        CompletionException failure = assertThrows(
                CompletionException.class, () -> lease.releaseAsync().join());

        assertTrue(access.locked);
        assertTrue(world.paused);
        assertEquals(1, failure.getCause().getSuppressed().length);

        lease.releaseAsync().join();

        assertFalse(access.locked);
        assertFalse(world.paused);
        assertEquals(2, world.resumeAttempts);
        assertEquals(1, access.unlocks);
    }

    private static final class FakeAccess
            implements HytaleSavedWorldScanBarrier.SavingAccess {
        private final List<HytaleSavedWorldScanBarrier.WorldSavingAccess> worlds;
        private boolean locked;
        private int pendingWaits;
        private int unlocks;
        private int unlockFailures;

        private FakeAccess(List<? extends HytaleSavedWorldScanBarrier.WorldSavingAccess> worlds) {
            this.worlds = List.copyOf(worlds);
        }

        @Override
        public boolean tryLockUniverse() {
            if (locked) {
                return false;
            }
            locked = true;
            return true;
        }

        @Override
        public void unlockUniverse() {
            if (locked) {
                if (unlockFailures > 0) {
                    unlockFailures--;
                    throw new IllegalStateException("universe unlock failed");
                }
                locked = false;
                unlocks++;
            }
        }

        @Override
        public CompletableFuture<Void> awaitUniverseWrites() {
            pendingWaits++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public List<HytaleSavedWorldScanBarrier.WorldSavingAccess> worlds() {
            return worlds;
        }
    }

    private static final class FakeWorld
            implements HytaleSavedWorldScanBarrier.WorldSavingAccess {
        private final boolean failDrain;
        private boolean paused;
        private int resumes;
        private int resumeAttempts;
        private int synchronousResumeFailures;

        private FakeWorld(boolean failDrain) {
            this.failDrain = failDrain;
        }

        @Override
        public CompletableFuture<Void> pauseAndDrainAsync() {
            paused = true;
            return failDrain
                    ? CompletableFuture.failedFuture(new IllegalStateException("save failed"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeAsync() {
            resumeAttempts++;
            if (synchronousResumeFailures > 0) {
                synchronousResumeFailures--;
                throw new IllegalStateException("resume scheduling rejected");
            }
            if (paused) {
                paused = false;
                resumes++;
            }
            return CompletableFuture.completedFuture(null);
        }
    }
}
