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
