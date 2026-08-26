package com.alechilles.alecstamework.items;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies concurrent dirty callbacks keep target refresh state consistent. */
class CommandTargetHudDirtyTrackerTest {
    @Test
    void concurrentDirtyCallbacksRetainEveryVersion() throws Exception {
        int workers = 8;
        int marksPerWorker = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger invalidations = new AtomicInteger();
        UUID playerUuid = UUID.randomUUID();
        CommandTargetHudDirtyTracker tracker = new CommandTargetHudDirtyTracker(
                (store, player) -> invalidations.incrementAndGet());
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            for (int worker = 0; worker < workers; worker++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int mark = 0; mark < marksPerWorker; mark++) {
                            tracker.markDirty(null, playerUuid);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        long version = tracker.version(playerUuid);
        assertEquals(workers * marksPerWorker, version);
        assertEquals(workers * marksPerWorker, invalidations.get());
        assertTrue(tracker.pending(playerUuid));
        tracker.markPresented(playerUuid, version);
        assertFalse(tracker.pending(playerUuid));
    }
}
