package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoalescedCompanionPopulationWriterTest {
    @Test
    void executorSchedulingOccursOutsideWriterMonitor() throws Exception {
        AtomicReference<Object> writerMonitor = new AtomicReference<>();
        AtomicBoolean scheduledWhileLocked = new AtomicBoolean();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                Object monitor = writerMonitor.get();
                if (monitor != null && Thread.holdsLock(monitor)) {
                    scheduledWhileLocked.set(true);
                }
                return super.schedule(command, delay, unit);
            }
        };
        CoalescedCompanionPopulationWriter writer = new CoalescedCompanionPopulationWriter(
                observation -> CompletableFuture.completedFuture(committed(observation, 4L)),
                (observation, result) -> { },
                executor,
                TimeUnit.MINUTES.toMillis(1),
                0L
        );
        Field lockField = CoalescedCompanionPopulationWriter.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        writerMonitor.set(lockField.get(writer));
        try {
            assertTrue(writer.record(observation(UUID.randomUUID(), UUID.randomUUID(), 1, 1)));
            assertFalse(scheduledWhileLocked.get());
        } finally {
            writer.close();
        }
    }

    @Test
    void latestObservationWinsWithinTheDebounceWindow() throws Exception {
        List<CompanionPopulationObservation> persisted = new ArrayList<>();
        CompanionPopulationObservationPersistence persistence = observation -> {
            persisted.add(observation);
            return CompletableFuture.completedFuture(new CompanionPopulationObservationPersistResult(
                    CompanionPopulationObservationPersistResult.Status.COMMITTED,
                    observation.expectedRevision() + 1L,
                    null
            ));
        };
        CoalescedCompanionPopulationWriter writer = new CoalescedCompanionPopulationWriter(
                persistence,
                (observation, result) -> { },
                Executors.newSingleThreadScheduledExecutor(),
                TimeUnit.MINUTES.toMillis(1),
                0L
        );
        try {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            writer.record(observation(npcUuid, ownerUuid, 1, 1));
            CompanionPopulationObservation latest = observation(npcUuid, ownerUuid, 8, -4);
            writer.record(latest);

            writer.flushPendingNow().get(2, TimeUnit.SECONDS);

            assertEquals(List.of(latest), persisted);
            assertEquals(2L, writer.metrics().observations());
            assertEquals(1L, writer.metrics().submissions());
            assertEquals(1L, writer.metrics().coalescedObservations());
        } finally {
            writer.close();
        }
    }

    @Test
    void flushPublishesItsInFlightFutureBeforeCallingPersistence() throws Exception {
        CountDownLatch enteredPersistence = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CompanionPopulationObservationPersistence persistence = observation -> {
            enteredPersistence.countDown();
            try {
                assertTrue(releasePersistence.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(exception);
            }
            return CompletableFuture.completedFuture(new CompanionPopulationObservationPersistResult(
                    CompanionPopulationObservationPersistResult.Status.COMMITTED,
                    observation.expectedRevision() + 1L,
                    null
            ));
        };
        CoalescedCompanionPopulationWriter writer = writer(persistence);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            writer.record(observation(UUID.randomUUID(), UUID.randomUUID(), 1, 1));
            Future<CompletableFuture<Void>> firstCall = caller.submit(writer::flushPendingNow);
            assertTrue(enteredPersistence.await(2, TimeUnit.SECONDS));

            CompletableFuture<Void> concurrentFlush = writer.flushPendingNow();

            assertFalse(concurrentFlush.isDone());
            releasePersistence.countDown();
            firstCall.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS);
            concurrentFlush.get(2, TimeUnit.SECONDS);
        } finally {
            releasePersistence.countDown();
            caller.shutdownNow();
            writer.close();
        }
    }

    @Test
    void flushDrainsANewerCutoffThatArrivesDuringAnOlderWrite() throws Exception {
        List<CompanionPopulationObservation> submissions = new ArrayList<>();
        List<CompletableFuture<CompanionPopulationObservationPersistResult>> completions =
                new ArrayList<>();
        CompanionPopulationObservationPersistence persistence = observation -> {
            submissions.add(observation);
            CompletableFuture<CompanionPopulationObservationPersistResult> completion =
                    new CompletableFuture<>();
            completions.add(completion);
            return completion;
        };
        CoalescedCompanionPopulationWriter writer = writer(persistence);
        try {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationObservation first = observation(npcUuid, ownerUuid, 1, 1);
            CompanionPopulationObservation second = observation(npcUuid, ownerUuid, 9, 9);
            writer.record(first);
            CompletableFuture<Void> firstFlush = writer.flushPendingNow();
            writer.record(second);

            CompletableFuture<Void> cutoffFlush = writer.flushPendingNow();
            completions.getFirst().complete(committed(first, 4L));

            assertEquals(2, submissions.size());
            assertEquals(second.withExpectedRevision(4L), submissions.getLast());
            assertFalse(cutoffFlush.isDone());
            completions.getLast().complete(committed(second, 5L));

            firstFlush.get(2, TimeUnit.SECONDS);
            cutoffFlush.get(2, TimeUnit.SECONDS);
        } finally {
            writer.close();
        }
    }

    @Test
    void currentAttemptFlushDoesNotWaitForeverForPendingOperation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CompanionPopulationObservationPersistence persistence = observation -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(pendingOperation(observation));
        };
        CoalescedCompanionPopulationWriter writer = new CoalescedCompanionPopulationWriter(
                persistence,
                (observation, result) -> { },
                Executors.newSingleThreadScheduledExecutor(),
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MINUTES.toMillis(1)
        );
        try {
            writer.record(observation(UUID.randomUUID(), UUID.randomUUID(), 1, 1));

            writer.flushCurrentAttemptsNow().get(2, TimeUnit.SECONDS);

            assertEquals(1, attempts.get());
            assertEquals(1, writer.metrics().pendingProfiles());
            assertEquals(1L, writer.metrics().submissions());
        } finally {
            writer.close();
        }
    }

    /** Regression: exhausted SQLite-busy batches remain queued until a later write succeeds. */
    @Test
    void transientPersistenceFailureRetriesWithoutDroppingLatestObservation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CompanionPopulationObservationPersistence persistence = observation -> {
            if (attempts.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(
                        new CompanionPopulationObservationPersistResult(
                                CompanionPopulationObservationPersistResult.Status.TRANSIENT_FAILURE,
                                observation.expectedRevision(),
                                "sqlite_write_failed:companion_population_live_observation:SQLiteException"
                        )
                );
            }
            return CompletableFuture.completedFuture(committed(observation, 4L));
        };
        CoalescedCompanionPopulationWriter writer = writer(persistence);
        try {
            writer.record(observation(UUID.randomUUID(), UUID.randomUUID(), 1, 1));

            writer.flushPendingNow().get(2, TimeUnit.SECONDS);

            assertEquals(2, attempts.get());
            assertEquals(0, writer.metrics().pendingProfiles());
        } finally {
            writer.close();
        }
    }

    @Test
    void currentAttemptFlushCarriesANewerCutoffPastAnOlderInFlightWrite() throws Exception {
        List<CompanionPopulationObservation> submissions = new ArrayList<>();
        List<CompletableFuture<CompanionPopulationObservationPersistResult>> completions =
                new ArrayList<>();
        CompanionPopulationObservationPersistence persistence = observation -> {
            submissions.add(observation);
            CompletableFuture<CompanionPopulationObservationPersistResult> completion =
                    new CompletableFuture<>();
            completions.add(completion);
            return completion;
        };
        CoalescedCompanionPopulationWriter writer = writer(persistence);
        try {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            CompanionPopulationObservation first = observation(npcUuid, ownerUuid, 1, 1);
            CompanionPopulationObservation second = observation(npcUuid, ownerUuid, 9, 9);
            writer.record(first);
            CompletableFuture<Void> firstAttempt = writer.flushCurrentAttemptsNow();
            writer.record(second);

            CompletableFuture<Void> cutoffAttempt = writer.flushCurrentAttemptsNow();
            completions.getFirst().complete(pendingOperation(first));

            assertEquals(2, submissions.size());
            assertEquals(second.withExpectedRevision(first.expectedRevision()), submissions.getLast());
            assertTrue(firstAttempt.isDone());
            assertFalse(cutoffAttempt.isDone());
            completions.getLast().complete(pendingOperation(second));

            cutoffAttempt.get(2, TimeUnit.SECONDS);
            assertEquals(1, writer.metrics().pendingProfiles());
        } finally {
            writer.close();
        }
    }

    private static CoalescedCompanionPopulationWriter writer(
            CompanionPopulationObservationPersistence persistence
    ) {
        return new CoalescedCompanionPopulationWriter(
                persistence,
                (observation, result) -> { },
                Executors.newSingleThreadScheduledExecutor(),
                TimeUnit.MINUTES.toMillis(1),
                0L
        );
    }

    private static CompanionPopulationObservationPersistResult committed(
            CompanionPopulationObservation observation,
            long revision
    ) {
        return new CompanionPopulationObservationPersistResult(
                CompanionPopulationObservationPersistResult.Status.COMMITTED,
                revision,
                null
        );
    }

    private static CompanionPopulationObservationPersistResult pendingOperation(
            CompanionPopulationObservation observation
    ) {
        return new CompanionPopulationObservationPersistResult(
                CompanionPopulationObservationPersistResult.Status.PENDING_OPERATION,
                observation.expectedRevision(),
                "population-operation-pending"
        );
    }

    private static CompanionPopulationObservation observation(UUID npcUuid,
                                                               UUID ownerUuid,
                                                               int chunkX,
                                                               int chunkZ) {
        return new CompanionPopulationObservation(
                "profile",
                npcUuid,
                ownerUuid,
                "default",
                CompanionLifecycleState.ACTIVE,
                "default",
                chunkX,
                chunkZ,
                3L,
                "test"
        );
    }
}
