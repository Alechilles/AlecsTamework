package com.alechilles.alecstamework.items.persistence.maintenance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.BinaryOperator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concurrency behavior tests for bounded newest-wins maintenance work. */
class LatestWorkCoordinatorTest {
    @Test
    void newerPendingValueReplacesOlderPendingValueAndSharesWaiters() {
        List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, Integer> coordinator =
                new LatestWorkCoordinator<>(2, (key, value) -> {
                    calls.add(value);
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        CompletionStage<Void> first = coordinator.submit("cow", 1);
        CompletionStage<Void> superseded = coordinator.submit("cow", 2);
        CompletionStage<Void> newest = coordinator.submit("cow", 3);

        assertEquals(List.of(1), calls);
        assertFalse(superseded.toCompletableFuture().isDone());
        assertFalse(newest.toCompletableFuture().isDone());
        assertEquals(1, coordinator.metrics().replacements());

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        assertEquals(List.of(1, 3), calls);
        assertTrue(first.toCompletableFuture().isDone());
        assertFalse(superseded.toCompletableFuture().isDone());
        assertFalse(newest.toCompletableFuture().isDone());

        take(invocations).completion.complete(null);
        assertTrue(superseded.toCompletableFuture().isDone());
        assertTrue(newest.toCompletableFuture().isDone());

        MaintenanceMetricsSnapshot metrics = coordinator.metrics();
        assertEquals(3, metrics.submissions());
        assertEquals(2, metrics.completions());
        assertEquals(0, metrics.failures());
        assertEquals(0, metrics.pendingKeys());
        assertEquals(0, metrics.inFlightWork());
    }

    @Test
    void handlerFailureCompletesCurrentWaitersAndRunsRetainedNewestValue() {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        CompletionStage<Void> failed = coordinator.submit("cow", 1);
        CompletionStage<Void> retained = coordinator.submit("cow", 2);

        RuntimeException failure = new RuntimeException("write failed");
        take(invocations).completion.completeExceptionally(failure);

        assertThrows(RuntimeException.class, () -> failed.toCompletableFuture().join());
        await(() -> invocations.size() == 1);
        assertFalse(retained.toCompletableFuture().isDone());

        take(invocations).completion.complete(null);
        assertTrue(retained.toCompletableFuture().isDone());
        assertEquals(1, coordinator.metrics().failures());
        assertEquals(1, coordinator.metrics().completions());
        assertEquals(0, coordinator.metrics().pendingKeys());
        assertEquals(0, coordinator.metrics().inFlightWork());
    }

    @Test
    void globalInFlightLimitBoundsDistinctKeys() {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(2, (key, value) -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        List<CompletionStage<Void>> stages = new ArrayList<>();
        for (int key = 0; key < 100; key++) {
            stages.add(coordinator.submit(key, key));
        }

        for (int completed = 0; completed < 100; completed++) {
            active.decrementAndGet();
            take(invocations).completion.complete(null);
        }

        stages.forEach(stage -> assertTrue(stage.toCompletableFuture().isDone()));
        assertEquals(2, maximumActive.get());
        assertEquals(0, active.get());
        assertEquals(100, coordinator.metrics().completions());
        assertEquals(0, coordinator.metrics().pendingKeys());
        assertEquals(0, coordinator.metrics().inFlightWork());
        assertEquals(2, coordinator.metrics().maximumInFlightWork());
    }

    @Test
    void flushFenceSharesAReplacementAcceptedAfterTheFlushCall() {
        List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    calls.add(value);
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        coordinator.submit("cow", 1);
        coordinator.submit("cow", 2);
        CompletionStage<Void> flush = coordinator.flush("cow");
        CompletionStage<Void> replacement = coordinator.submit("cow", 3);

        assertFalse(flush.toCompletableFuture().isDone());
        take(invocations).completion.complete(null);
        await(() -> invocations.size() == 1);
        assertEquals(List.of(1, 3), calls);
        assertFalse(flush.toCompletableFuture().isDone());

        take(invocations).completion.complete(null);
        assertTrue(flush.toCompletableFuture().isDone());
        assertTrue(replacement.toCompletableFuture().isDone());
    }

    @Test
    void priorityRunsBeforeRoutineAfterCurrentInFlight() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<String>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, String> coordinator =
                outcomeCoordinator(1, calls, invocations);

        coordinator.submit("cow", "routine-1");
        CompletionStage<Void> priority = coordinator.submitPriority(
                "cow", "priority", keepNewValue()
        );
        CompletionStage<Void> routine = coordinator.submit("cow", "routine-2");

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        assertEquals(List.of("routine-1", "priority"), calls);
        assertFalse(priority.toCompletableFuture().isDone());
        assertFalse(routine.toCompletableFuture().isDone());

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 3);
        assertEquals(List.of("routine-1", "priority", "routine-2"), calls);
        take(invocations).completion.complete(null);
        assertTrue(priority.toCompletableFuture().isDone());
        assertTrue(routine.toCompletableFuture().isDone());
    }

    @Test
    void prioritySelectorCanRetainTheOldValue() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<String>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, String> coordinator =
                outcomeCoordinator(1, calls, invocations);

        coordinator.submit("cow", "routine");
        CompletionStage<Void> oldPriority = coordinator.submitPriority(
                "cow", "old", (oldValue, newValue) -> oldValue
        );
        CompletionStage<Void> newPriority = coordinator.submitPriority(
                "cow", "new", keepNewValue()
        );

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        assertEquals(List.of("routine", "old"), calls);
        take(invocations).completion.complete(null);
        assertTrue(oldPriority.toCompletableFuture().isDone());
        assertTrue(newPriority.toCompletableFuture().isDone());
    }

    @Test
    void prioritySelectorCanChooseTheNewValue() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<String>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, String> coordinator =
                outcomeCoordinator(1, calls, invocations);

        coordinator.submit("cow", "routine");
        CompletionStage<Void> oldPriority = coordinator.submitPriority(
                "cow", "old", keepNewValue()
        );
        CompletionStage<Void> newPriority = coordinator.submitPriority(
                "cow", "new", keepNewValue()
        );

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        assertEquals(List.of("routine", "new"), calls);
        take(invocations).completion.complete(null);
        assertTrue(oldPriority.toCompletableFuture().isDone());
        assertTrue(newPriority.toCompletableFuture().isDone());
    }

    @Test
    void priorityAndRoutineWaitersCompleteSeparatelyAndFlushWaitsThroughBoth() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<String>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, String> coordinator =
                outcomeCoordinator(1, calls, invocations);

        coordinator.submit("cow", "routine-1");
        CompletionStage<Void> priority = coordinator.submitPriority(
                "cow", "priority", keepNewValue()
        );
        CompletionStage<Void> routine = coordinator.submit("cow", "routine-2");
        CompletionStage<Void> flush = coordinator.flush("cow");

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        take(invocations).completion.complete(null);
        assertTrue(priority.toCompletableFuture().isDone());
        assertFalse(routine.toCompletableFuture().isDone());
        assertFalse(flush.toCompletableFuture().isDone());

        take(invocations).completion.complete(null);
        assertTrue(routine.toCompletableFuture().isDone());
        assertTrue(flush.toCompletableFuture().isDone());

        MaintenanceMetricsSnapshot metrics = coordinator.metrics();
        assertEquals(0, metrics.pendingKeys());
        assertEquals(0, metrics.pendingWork());
    }

    @Test
    void flushFailsWhenPriorityFailsBeforeTrailingRoutineSucceeds() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Invocation<String>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<String, String> coordinator =
                outcomeCoordinator(1, calls, invocations);

        coordinator.submit("cow", "in-flight");
        CompletionStage<Void> priority = coordinator.submitPriority(
                "cow", "priority", keepNewValue()
        );
        CompletionStage<Void> routine = coordinator.submit("cow", "routine");
        CompletionStage<Void> flush = coordinator.flush("cow");

        take(invocations).completion.complete(null);
        await(() -> calls.size() == 2);
        take(invocations).completion.completeExceptionally(
                new IllegalStateException("priority failed")
        );
        await(() -> calls.size() == 3);
        take(invocations).completion.complete(null);

        assertThrows(
                RuntimeException.class,
                () -> priority.toCompletableFuture().join()
        );
        assertTrue(routine.toCompletableFuture().isDone());
        assertThrows(
                RuntimeException.class,
                () -> flush.toCompletableFuture().join()
        );
        assertEquals(List.of("in-flight", "priority", "routine"), calls);
    }

    @Test
    void deferredKeysReleaseSlotsForNewWork() {
        BlockingQueue<Runnable> resumes = new LinkedBlockingQueue<>();
        List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(
                        4,
                        (key, value) -> {
                            calls.add(value);
                            return CompletableFuture.completedFuture(
                                    value < 4
                                            ? MaintenanceWorkOutcome.deferred()
                                            : MaintenanceWorkOutcome.durable()
                            );
                        },
                        resumes::add
                );

        for (int key = 0; key < 5; key++) {
            coordinator.submit(key, key);
        }

        assertEquals(List.of(0, 1, 2, 3, 4), calls);
        assertEquals(4, coordinator.metrics().pendingKeys());
        assertEquals(4, coordinator.metrics().pendingWork());
        assertEquals(0, coordinator.metrics().inFlightWork());
        assertEquals(4, resumes.size());
    }

    @Test
    void deferredResumeCompletesOriginalWaiters() {
        BlockingQueue<Runnable> resumes = new LinkedBlockingQueue<>();
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        AtomicInteger attempts = new AtomicInteger();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            if (attempts.getAndIncrement() == 0) {
                                return CompletableFuture.completedFuture(
                                        MaintenanceWorkOutcome.deferred()
                                );
                            }
                            Invocation<Integer> invocation = new Invocation<>(value);
                            invocations.add(invocation);
                            return invocation.completion.thenApply(
                                    ignored -> MaintenanceWorkOutcome.durable()
                            );
                        },
                        resumes::add
                );

        CompletionStage<Void> submitted = coordinator.submit(1, 1);
        takeRunnable(resumes).run();
        assertFalse(submitted.toCompletableFuture().isDone());
        take(invocations).completion.complete(null);
        assertTrue(submitted.toCompletableFuture().isDone());
    }

    @Test
    void newerRoutineSupersedesDeferredValueAndStaleResumeIsHarmless() {
        BlockingQueue<Runnable> resumes = new LinkedBlockingQueue<>();
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        List<Integer> calls = Collections.synchronizedList(new ArrayList<>());
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            calls.add(value);
                            if (value == 1) {
                                return CompletableFuture.completedFuture(
                                        MaintenanceWorkOutcome.deferred()
                                );
                            }
                            Invocation<Integer> invocation = new Invocation<>(value);
                            invocations.add(invocation);
                            return invocation.completion.thenApply(
                                    ignored -> MaintenanceWorkOutcome.durable()
                            );
                        },
                        resumes::add
                );

        CompletionStage<Void> stale = coordinator.submit(1, 1);
        CompletionStage<Void> newest = coordinator.submit(1, 2);
        await(() -> calls.size() == 2);
        assertEquals(List.of(1, 2), calls);
        takeRunnable(resumes).run();
        assertEquals(List.of(1, 2), calls);
        take(invocations).completion.complete(null);
        assertTrue(stale.toCompletableFuture().isDone());
        assertTrue(newest.toCompletableFuture().isDone());
    }

    @Test
    void priorityWorkWakesAheadOfADeferredRoutine() {
        BlockingQueue<Runnable> resumes = new LinkedBlockingQueue<>();
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger routineAttempts = new AtomicInteger();
        LatestWorkCoordinator<String, String> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            calls.add(value);
                            if ("routine".equals(value)
                                    && routineAttempts.getAndIncrement() == 0) {
                                return CompletableFuture.completedFuture(
                                        MaintenanceWorkOutcome.deferred()
                                );
                            }
                            return CompletableFuture.completedFuture(
                                    MaintenanceWorkOutcome.durable()
                            );
                        },
                        resumes::add
                );

        CompletionStage<Void> routine = coordinator.submit("cow", "routine");
        CompletionStage<Void> priority = coordinator.submitPriority(
                "cow", "priority", keepNewValue()
        );

        assertEquals(List.of("routine", "priority", "routine"), calls);
        assertTrue(priority.toCompletableFuture().isDone());
        assertTrue(routine.toCompletableFuture().isDone());
        takeRunnable(resumes).run();
        assertEquals(List.of("routine", "priority", "routine"), calls);
    }

    @Test
    void shutdownPromotesDeferredWorkAndTurnsFinalDeferralIntoFailure() {
        BlockingQueue<Runnable> resumes = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            calls.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    MaintenanceWorkOutcome.deferred()
                            );
                        },
                        resumes::add
                );

        CompletionStage<Void> submitted = coordinator.submit(1, 1);
        MaintenanceDrainResult result = coordinator.shutdown(Duration.ofSeconds(1));

        assertTrue(result.drained());
        assertEquals(2, calls.get());
        assertThrows(RuntimeException.class, () -> submitted.toCompletableFuture().join());
        assertEquals(0, result.pendingKeys());
        assertEquals(0, result.pendingWork());
        assertEquals(0, result.inFlightWork());
        assertEquals(1, coordinator.metrics().failures());
        takeRunnable(resumes).run();
        assertEquals(2, calls.get());
    }

    @Test
    void prioritySelectorFailureDuringDeferredMergeRunsNewerPriority() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<MaintenanceWorkOutcome<String>> firstOutcome =
                new CompletableFuture<>();
        LatestWorkCoordinator<String, String> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            calls.add(value);
                            if ("priority-1".equals(value)) {
                                return firstOutcome;
                            }
                            return CompletableFuture.completedFuture(
                                    MaintenanceWorkOutcome.durable()
                            );
                        },
                        ignored -> { }
                );

        CompletionStage<Void> failed = coordinator.submitPriority(
                "cow",
                "priority-1",
                (oldValue, newValue) -> {
                    throw new IllegalStateException("selector failed");
                }
        );
        CompletionStage<Void> retained = coordinator.submitPriority(
                "cow", "priority-2", keepNewValue()
        );

        firstOutcome.complete(MaintenanceWorkOutcome.deferred());

        assertThrows(
                RuntimeException.class,
                () -> failed.toCompletableFuture().join()
        );
        assertTrue(retained.toCompletableFuture().isDone());
        assertEquals(List.of("priority-1", "priority-2"), calls);
        MaintenanceMetricsSnapshot metrics = coordinator.metrics();
        assertEquals(1, metrics.failures());
        assertEquals(1, metrics.completions());
        assertEquals(0, metrics.pendingKeys());
        assertEquals(0, metrics.pendingWork());
        assertEquals(0, metrics.inFlightWork());
    }

    @Test
    void schedulerRejectionFailsDeferredLaneAndRunsOtherLane() {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Runnable> staleResume = new AtomicReference<>();
        CompletableFuture<MaintenanceWorkOutcome<String>> priorityOutcome =
                new CompletableFuture<>();
        CompletableFuture<MaintenanceWorkOutcome<String>> routineOutcome =
                new CompletableFuture<>();
        LatestWorkCoordinator<String, String> coordinator =
                new LatestWorkCoordinator<>(
                        1,
                        (key, value) -> {
                            calls.add(value);
                            return "priority".equals(value)
                                    ? priorityOutcome : routineOutcome;
                        },
                        resume -> {
                            staleResume.set(resume);
                            throw new IllegalStateException(
                                    "resume scheduler rejected work"
                            );
                        }
                );

        CompletionStage<Void> priority = coordinator.submitPriority(
                "cow", "priority", keepNewValue()
        );
        CompletionStage<Void> routine = coordinator.submit("cow", "routine");

        priorityOutcome.complete(MaintenanceWorkOutcome.deferred());

        assertThrows(
                RuntimeException.class,
                () -> priority.toCompletableFuture().join()
        );
        assertEquals(List.of("priority", "routine"), calls);
        assertFalse(routine.toCompletableFuture().isDone());
        assertNotNull(staleResume.get());
        staleResume.get().run();
        assertEquals(List.of("priority", "routine"), calls);

        routineOutcome.complete(MaintenanceWorkOutcome.durable());
        assertTrue(routine.toCompletableFuture().isDone());
        MaintenanceMetricsSnapshot metrics = coordinator.metrics();
        assertEquals(1, metrics.failures());
        assertEquals(1, metrics.completions());
        assertEquals(0, metrics.pendingKeys());
        assertEquals(0, metrics.pendingWork());
        assertEquals(0, metrics.inFlightWork());
    }

    @Test
    void callbackRegistrationFailureFailsWaitersReleasesSlotAndDrains() throws Exception {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        RuntimeException registrationFailure =
                new RuntimeException("completion registration failed");
        AtomicReference<CompletionStage<Void>> firstStage =
                new AtomicReference<>();
        LatestWorkCoordinator<String, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    if (value == 1) {
                        handlerEntered.countDown();
                        awaitLatch(releaseHandler);
                        return new RegistrationFailureStage(registrationFailure);
                    }
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        Thread submitter = new Thread(
                () -> firstStage.set(coordinator.submit("cow", 1)),
                "latest-work-coordinator-registration-test"
        );
        submitter.start();
        awaitLatch(handlerEntered);
        CompletionStage<Void> flush = coordinator.flush("cow");
        CompletionStage<Void> retained = coordinator.submit("cow", 2);
        releaseHandler.countDown();
        submitter.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(submitter.isAlive());
        assertNotNull(firstStage.get());
        assertThrows(RuntimeException.class, () -> firstStage.get().toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> flush.toCompletableFuture().join());
        assertEquals(1, coordinator.metrics().inFlightWork());

        take(invocations).completion.complete(null);
        assertTrue(retained.toCompletableFuture().isDone());
        assertTrue(coordinator.shutdown(Duration.ofSeconds(1)).drained());
        assertEquals(1, coordinator.metrics().failures());
        assertEquals(1, coordinator.metrics().completions());
        assertEquals(0, coordinator.metrics().inFlightWork());
    }

    @Test
    void shutdownDrainsAcceptedWorkAndStopsAdmission() throws Exception {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        coordinator.submit(1, 1);
        coordinator.submit(2, 2);
        AtomicReference<MaintenanceDrainResult> result = new AtomicReference<>();
        Thread drainer = new Thread(
                () -> result.set(coordinator.shutdown(Duration.ofSeconds(2))),
                "latest-work-coordinator-test-drainer"
        );
        drainer.start();
        await(() -> drainer.isAlive());

        take(invocations).completion.complete(null);
        take(invocations).completion.complete(null);
        drainer.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(drainer.isAlive());
        assertNotNull(result.get());
        assertTrue(result.get().drained());
        assertEquals(0, result.get().pendingKeys());
        assertEquals(0, result.get().inFlightWork());
    }

    @Test
    void shutdownTimeoutReportsExactPendingAndInFlightCounts() {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        coordinator.submit(1, 1);
        coordinator.submit(2, 2);

        MaintenanceDrainResult result = coordinator.shutdown(Duration.ZERO);

        assertFalse(result.drained());
        assertEquals(1, result.pendingKeys());
        assertEquals(1, result.inFlightWork());
        CompletionStage<Void> rejected = coordinator.submit(3, 3);
        assertTrue(rejected.toCompletableFuture().isCompletedExceptionally());

        take(invocations).completion.complete(null);
        take(invocations).completion.complete(null);
        MaintenanceDrainResult completed = coordinator.shutdown(Duration.ofSeconds(1));
        assertTrue(completed.drained());
    }

    @Test
    void pendingAgeIsReportedWithoutExposingWorkValues() throws Exception {
        BlockingQueue<Invocation<Integer>> invocations =
                new LinkedBlockingQueue<>();
        LatestWorkCoordinator<Integer, Integer> coordinator =
                new LatestWorkCoordinator<>(1, (key, value) -> {
                    Invocation<Integer> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion;
                });

        coordinator.submit(1, 1);
        coordinator.submit(2, 2);
        Thread.sleep(2);

        MaintenanceMetricsSnapshot metrics = coordinator.metrics();
        assertEquals(1, metrics.pendingKeys());
        assertTrue(metrics.oldestPendingAgeNanos() >= 0);
        assertTrue(metrics.oldestPendingAge().compareTo(Duration.ZERO) >= 0);

        take(invocations).completion.complete(null);
        take(invocations).completion.complete(null);
    }

    private static <T> Invocation<T> take(BlockingQueue<Invocation<T>> invocations) {
        try {
            Invocation<T> invocation = invocations.poll(5, TimeUnit.SECONDS);
            assertNotNull(invocation);
            return invocation;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for handler", interrupted);
        }
    }

    private static Runnable takeRunnable(BlockingQueue<Runnable> resumes) {
        try {
            Runnable resume = resumes.poll(5, TimeUnit.SECONDS);
            assertNotNull(resume);
            return resume;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for resume", interrupted);
        }
    }

    private static BinaryOperator<String> keepNewValue() {
        return (oldValue, newValue) -> newValue;
    }

    private static LatestWorkCoordinator<String, String> outcomeCoordinator(
            int maxInFlight,
            List<String> calls,
            BlockingQueue<Invocation<String>> invocations
    ) {
        return new LatestWorkCoordinator<>(
                maxInFlight,
                (key, value) -> {
                    calls.add(value);
                    Invocation<String> invocation = new Invocation<>(value);
                    invocations.add(invocation);
                    return invocation.completion.thenApply(
                            ignored -> MaintenanceWorkOutcome.durable()
                    );
                },
                ignored -> { }
        );
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition did not become true");
            }
            Thread.yield();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test handler", interrupted);
        }
    }

    private static final class Invocation<T> {
        private final T value;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private Invocation(T value) {
            this.value = value;
        }
    }

    private static final class RegistrationFailureStage
            extends CompletableFuture<Void> {
        private final RuntimeException failure;

        private RegistrationFailureStage(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public CompletableFuture<Void> whenComplete(
                BiConsumer<? super Void, ? super Throwable> action
        ) {
            throw failure;
        }
    }
}
