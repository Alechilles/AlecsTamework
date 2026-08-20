package com.alechilles.alecstamework.items.persistence.maintenance;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Bounds asynchronous per-key work with one priority fence and newest-wins
 * routine retention.
 *
 * <p>One monitor owns all state. Handlers, schedulers, and waiter callbacks
 * always run after that monitor is released.</p>
 */
public final class LatestWorkCoordinator<K, V> {
    private final Object stateLock = new Object();
    private final int maxInFlight;
    private final MaintenanceWorkHandler<? super K, V> handler;
    private final Consumer<Runnable> resumeScheduler;
    private final Map<K, KeyState<V>> states = new HashMap<>();
    private final ArrayDeque<K> readyKeys = new ArrayDeque<>();
    private final ThreadLocal<Boolean> dispatching =
            ThreadLocal.withInitial(() -> false);

    private boolean accepting = true;
    private int inFlightWork;
    private int maximumInFlightWork;
    private long submissions;
    private long replacements;
    private long completions;
    private long failures;

    /** Creates a newest-wins coordinator whose handler cannot defer. */
    public LatestWorkCoordinator(
            int maxInFlight,
            @Nonnull BiFunction<? super K, ? super V,
                    ? extends CompletionStage<Void>> handler
    ) {
        this(maxInFlight, durableHandler(handler), ignored -> { });
    }

    /** Creates a coordinator with caller-scheduled pre-write deferral. */
    public LatestWorkCoordinator(
            int maxInFlight,
            @Nonnull MaintenanceWorkHandler<? super K, V> handler,
            @Nonnull Consumer<Runnable> resumeScheduler
    ) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException(
                    "At least one in-flight slot is required"
            );
        }
        this.maxInFlight = maxInFlight;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.resumeScheduler = Objects.requireNonNull(
                resumeScheduler, "resumeScheduler"
        );
    }

    /** Accepts routine work and replaces only older pending routine work. */
    @Nonnull
    public CompletionStage<Void> submit(@Nonnull K key, @Nonnull V value) {
        return submit(key, value, null);
    }

    /**
     * Accepts sealed priority work. The selector chooses the retained value
     * when another priority value is already pending. The selector must be a
     * pure, non-blocking function because selection is part of admission.
     */
    @Nonnull
    public CompletionStage<Void> submitPriority(
            @Nonnull K key,
            @Nonnull V value,
            @Nonnull BinaryOperator<V> selector
    ) {
        return submit(key, value, Objects.requireNonNull(selector, "selector"));
    }

    /** Waits for all work accepted for this key at call time. */
    @Nonnull
    public CompletionStage<Void> flush(@Nonnull K key) {
        Objects.requireNonNull(key, "key");
        List<CompletableFuture<Void>> dependencies = new ArrayList<>(3);
        synchronized (stateLock) {
            KeyState<V> state = states.get(key);
            if (state != null) {
                addFlushDependency(state.inFlight, dependencies);
                addFlushDependency(state.priority, dependencies);
                addFlushDependency(state.routine, dependencies);
            }
        }
        if (dependencies.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (dependencies.size() == 1) {
            return dependencies.get(0);
        }
        return CompletableFuture.allOf(
                dependencies.toArray(CompletableFuture[]::new)
        );
    }

    /** Returns immutable point-in-time coordinator activity. */
    @Nonnull
    public MaintenanceMetricsSnapshot metrics() {
        synchronized (stateLock) {
            return new MaintenanceMetricsSnapshot(
                    submissions, replacements, completions, failures,
                    pendingKeysLocked(), pendingWorkLocked(), inFlightWork,
                    maximumInFlightWork, oldestPendingAgeLocked()
            );
        }
    }

    /**
     * Stops admission, promotes deferred work for one final probe, and waits
     * for retained work. A final deferral becomes a terminal failure.
     */
    @Nonnull
    public MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Shutdown timeout must be non-negative"
            );
        }
        long deadline = deadline(System.nanoTime(), timeout);
        synchronized (stateLock) {
            accepting = false;
            for (KeyState<V> state : states.values()) {
                state.deferred = false;
                state.deferralGeneration++;
            }
            enqueuePendingKeysLocked();
        }
        startReadyWork();
        synchronized (stateLock) {
            while (inFlightWork > 0 || pendingWorkLocked() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !awaitNanosLocked(remaining)) {
                    return drainResultLocked(false);
                }
            }
            return drainResultLocked(true);
        }
    }

    private CompletionStage<Void> submit(
            K key,
            V value,
            BinaryOperator<V> prioritySelector
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        boolean rejected;
        Throwable admissionFailure = null;
        synchronized (stateLock) {
            rejected = !accepting;
            if (!rejected) {
                try {
                    acceptLocked(key, value, waiter, prioritySelector);
                    submissions++;
                } catch (Throwable failure) {
                    admissionFailure = failure;
                }
            }
        }
        if (rejected) {
            waiter.completeExceptionally(new RejectedExecutionException(
                    "Maintenance coordinator is closed"
            ));
            return waiter;
        }
        if (admissionFailure != null) {
            waiter.completeExceptionally(admissionFailure);
            return waiter;
        }
        startReadyWork();
        return waiter;
    }

    private void acceptLocked(
            K key,
            V value,
            CompletableFuture<Void> waiter,
            BinaryOperator<V> prioritySelector
    ) {
        KeyState<V> state = states.computeIfAbsent(key, ignored -> new KeyState<>());
        Lane lane = prioritySelector == null ? Lane.ROUTINE : Lane.PRIORITY;
        Work<V> candidate = new Work<>(
                value, waiter, System.nanoTime(), prioritySelector
        );
        if (lane == Lane.PRIORITY) {
            if (state.priority == null) {
                state.priority = candidate;
            } else {
                state.priority = mergePriority(state.priority, candidate);
                replacements++;
            }
        } else if (state.routine == null) {
            state.routine = candidate;
        } else {
            state.routine = inheritRoutine(candidate, state.routine);
            replacements++;
        }
        wakeDeferredLocked(state, lane);
        enqueueIfReadyLocked(key, state);
    }

    private void wakeDeferredLocked(KeyState<V> state, Lane submittedLane) {
        if (state.deferred && (state.deferredLane == submittedLane
                || submittedLane == Lane.PRIORITY)) {
            state.deferred = false;
            state.deferralGeneration++;
        }
    }

    private void enqueueIfReadyLocked(K key, KeyState<V> state) {
        if (state.inFlight == null && !state.deferred
                && hasPending(state) && !state.ready) {
            state.ready = true;
            readyKeys.addLast(key);
        }
    }

    private void enqueuePendingKeysLocked() {
        for (Map.Entry<K, KeyState<V>> entry : states.entrySet()) {
            enqueueIfReadyLocked(entry.getKey(), entry.getValue());
        }
    }

    private void startReadyWork() {
        if (Boolean.TRUE.equals(dispatching.get())) {
            return;
        }
        dispatching.set(true);
        try {
            Launch<K, V> launch;
            while ((launch = claimReadyWork()) != null) {
                invokeHandler(launch);
            }
        } finally {
            dispatching.remove();
        }
    }

    private Launch<K, V> claimReadyWork() {
        synchronized (stateLock) {
            while (inFlightWork < maxInFlight && !readyKeys.isEmpty()) {
                K key = readyKeys.removeFirst();
                KeyState<V> state = states.get(key);
                if (state == null) {
                    continue;
                }
                state.ready = false;
                if (state.inFlight != null || state.deferred) {
                    continue;
                }
                Lane lane = state.priority != null
                        ? Lane.PRIORITY : Lane.ROUTINE;
                Work<V> work = takePending(state, lane);
                if (work == null) {
                    continue;
                }
                state.inFlight = work;
                inFlightWork++;
                maximumInFlightWork = Math.max(
                        maximumInFlightWork, inFlightWork
                );
                return new Launch<>(key, work, lane);
            }
            return null;
        }
    }

    private void invokeHandler(Launch<K, V> launch) {
        CompletionStage<? extends MaintenanceWorkOutcome<V>> completion;
        try {
            completion = handler.apply(launch.key, launch.work.value);
            if (completion == null) {
                throw new NullPointerException(
                        "Maintenance handler returned null"
                );
            }
        } catch (Throwable failure) {
            complete(launch, null, failure);
            return;
        }
        try {
            completion.whenComplete((outcome, failure) ->
                    complete(launch, outcome, failure));
        } catch (Throwable failure) {
            complete(launch, null, failure);
        }
    }

    private void complete(
            Launch<K, V> launch,
            MaintenanceWorkOutcome<V> outcome,
            Throwable failure
    ) {
        if (failure == null
                && outcome instanceof MaintenanceWorkOutcome.Failed<V> failed) {
            failure = failed.failure();
        }
        if (failure == null
                && outcome instanceof MaintenanceWorkOutcome.Deferred<V>) {
            defer(launch);
            return;
        }
        if (failure == null
                && !(outcome instanceof MaintenanceWorkOutcome.Durable<V>)) {
            failure = new NullPointerException(
                    "Maintenance handler returned no outcome"
            );
        }
        finish(launch, failure);
    }

    private void defer(Launch<K, V> launch) {
        Resume<K> resume = null;
        Throwable terminal = null;
        synchronized (stateLock) {
            KeyState<V> state = liveState(launch);
            if (state == null) {
                return;
            }
            state.inFlight = null;
            inFlightWork--;
            if (!accepting) {
                terminal = new RejectedExecutionException(
                        "Maintenance work remained deferred during shutdown"
                );
            } else {
                try {
                    boolean sameLaneNewer = pending(
                            state, launch.lane
                    ) != null;
                    boolean shouldRun = sameLaneNewer
                            || (launch.lane == Lane.ROUTINE
                            && state.priority != null);
                    restoreDeferred(state, launch);
                    if (sameLaneNewer) {
                        replacements++;
                    }
                    if (shouldRun) {
                        enqueueIfReadyLocked(launch.key, state);
                    } else {
                        state.deferred = true;
                        state.deferredLane = launch.lane;
                        long generation = ++state.deferralGeneration;
                        resume = new Resume<>(
                                launch.key, launch.lane, generation
                        );
                    }
                } catch (Throwable failure) {
                    terminal = failure;
                    enqueueIfReadyLocked(launch.key, state);
                }
                stateLock.notifyAll();
            }
        }
        if (terminal != null) {
            finishDetached(launch, terminal);
        } else if (resume != null) {
            scheduleResume(resume);
        }
        startReadyWork();
    }

    private void scheduleResume(Resume<K> resume) {
        try {
            resumeScheduler.accept(() -> resume(resume));
        } catch (Throwable failure) {
            failDeferred(resume, failure);
        }
    }

    private void resume(Resume<K> resume) {
        synchronized (stateLock) {
            KeyState<V> state = states.get(resume.key);
            if (state == null || !state.deferred
                    || state.deferredLane != resume.lane
                    || state.deferralGeneration != resume.generation) {
                return;
            }
            state.deferred = false;
            enqueueIfReadyLocked(resume.key, state);
        }
        startReadyWork();
    }

    private void failDeferred(Resume<K> resume, Throwable failure) {
        List<CompletableFuture<Void>> waiters = List.of();
        synchronized (stateLock) {
            KeyState<V> state = states.get(resume.key);
            if (state == null || !state.deferred
                    || state.deferredLane != resume.lane
                    || state.deferralGeneration != resume.generation) {
                return;
            }
            state.deferred = false;
            Work<V> work = takePending(state, resume.lane);
            if (work != null) {
                waiters = new ArrayList<>(work.waiters);
                failures++;
            }
            cleanOrEnqueueLocked(resume.key, state);
            stateLock.notifyAll();
        }
        completeWaiters(waiters, failure);
        startReadyWork();
    }

    private void finish(Launch<K, V> launch, Throwable failure) {
        List<CompletableFuture<Void>> waiters;
        synchronized (stateLock) {
            KeyState<V> state = liveState(launch);
            if (state == null) {
                return;
            }
            state.inFlight = null;
            inFlightWork--;
            if (failure == null) {
                completions++;
            } else {
                failures++;
            }
            waiters = new ArrayList<>(launch.work.waiters);
            cleanOrEnqueueLocked(launch.key, state);
            stateLock.notifyAll();
        }
        completeWaiters(waiters, failure);
        startReadyWork();
    }

    private void finishDetached(Launch<K, V> launch, Throwable failure) {
        List<CompletableFuture<Void>> waiters;
        synchronized (stateLock) {
            KeyState<V> state = states.get(launch.key);
            failures++;
            waiters = new ArrayList<>(launch.work.waiters);
            if (state != null) {
                cleanOrEnqueueLocked(launch.key, state);
            }
            stateLock.notifyAll();
        }
        completeWaiters(waiters, failure);
    }

    private KeyState<V> liveState(Launch<K, V> launch) {
        KeyState<V> state = states.get(launch.key);
        return state != null && state.inFlight == launch.work
                ? state : null;
    }

    private void cleanOrEnqueueLocked(K key, KeyState<V> state) {
        if (state.inFlight == null && !hasPending(state)) {
            states.remove(key);
        } else {
            enqueueIfReadyLocked(key, state);
        }
    }

    private void restoreDeferred(KeyState<V> state, Launch<K, V> launch) {
        Work<V> newer = pending(state, launch.lane);
        if (newer == null) {
            setPending(state, launch.lane, launch.work);
        } else if (launch.lane == Lane.PRIORITY) {
            setPending(state, launch.lane, mergePriority(launch.work, newer));
        } else {
            setPending(state, launch.lane, inheritRoutine(newer, launch.work));
        }
    }

    private static <V> Work<V> mergePriority(Work<V> old, Work<V> candidate) {
        BinaryOperator<V> selector = old.prioritySelector;
        V retained = Objects.requireNonNull(
                selector.apply(old.value, candidate.value),
                "Priority selector returned null"
        );
        Work<V> merged = new Work<>(
                retained,
                Math.min(old.createdAtNanos, candidate.createdAtNanos),
                selector
        );
        merged.waiters.addAll(old.waiters);
        merged.waiters.addAll(candidate.waiters);
        return merged;
    }

    private static <V> Work<V> inheritRoutine(Work<V> newer, Work<V> old) {
        Work<V> retained = new Work<>(
                newer.value,
                Math.min(old.createdAtNanos, newer.createdAtNanos),
                null
        );
        retained.waiters.addAll(old.waiters);
        retained.waiters.addAll(newer.waiters);
        return retained;
    }

    private static void completeWaiters(
            List<CompletableFuture<Void>> waiters,
            Throwable failure
    ) {
        for (CompletableFuture<Void> waiter : waiters) {
            if (failure == null) {
                waiter.complete(null);
            } else {
                waiter.completeExceptionally(failure);
            }
        }
    }

    private MaintenanceDrainResult drainResultLocked(boolean drained) {
        return new MaintenanceDrainResult(
                drained, pendingKeysLocked(), pendingWorkLocked(), inFlightWork
        );
    }

    private int pendingKeysLocked() {
        int count = 0;
        for (KeyState<V> state : states.values()) {
            if (hasPending(state)) {
                count++;
            }
        }
        return count;
    }

    private int pendingWorkLocked() {
        int count = 0;
        for (KeyState<V> state : states.values()) {
            count += state.priority == null ? 0 : 1;
            count += state.routine == null ? 0 : 1;
        }
        return count;
    }

    private long oldestPendingAgeLocked() {
        long now = System.nanoTime();
        long oldest = 0;
        for (KeyState<V> state : states.values()) {
            oldest = Math.max(oldest, age(now, state.priority));
            oldest = Math.max(oldest, age(now, state.routine));
        }
        return oldest;
    }

    private boolean awaitNanosLocked(long remaining) {
        try {
            stateLock.wait(
                    remaining / 1_000_000L,
                    (int) (remaining % 1_000_000L)
            );
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long deadline(long start, Duration timeout) {
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return start > 0 && nanos >= Long.MAX_VALUE - start
                ? Long.MAX_VALUE : start + nanos;
    }

    private static long age(long now, Work<?> work) {
        if (work == null) {
            return 0;
        }
        long age = now - work.createdAtNanos;
        return age < 0 ? Long.MAX_VALUE : age;
    }

    private static boolean hasPending(KeyState<?> state) {
        return state.priority != null || state.routine != null;
    }

    private static void addFlushDependency(
            Work<?> work,
            List<CompletableFuture<Void>> dependencies
    ) {
        if (work != null) {
            CompletableFuture<Void> dependency = new CompletableFuture<>();
            work.waiters.add(dependency);
            dependencies.add(dependency);
        }
    }

    private static <V> Work<V> pending(KeyState<V> state, Lane lane) {
        return lane == Lane.PRIORITY ? state.priority : state.routine;
    }

    private static <V> Work<V> takePending(KeyState<V> state, Lane lane) {
        Work<V> work = pending(state, lane);
        setPending(state, lane, null);
        return work;
    }

    private static <V> void setPending(
            KeyState<V> state,
            Lane lane,
            Work<V> work
    ) {
        if (lane == Lane.PRIORITY) {
            state.priority = work;
        } else {
            state.routine = work;
        }
    }

    private static <K, V> MaintenanceWorkHandler<K, V> durableHandler(
            BiFunction<? super K, ? super V,
                    ? extends CompletionStage<Void>> handler
    ) {
        Objects.requireNonNull(handler, "handler");
        return (key, value) -> {
            CompletionStage<Void> completion = handler.apply(key, value);
            if (completion == null) {
                throw new NullPointerException(
                        "Maintenance handler returned null"
                );
            }
            CompletableFuture<MaintenanceWorkOutcome<V>> adapted =
                    new CompletableFuture<>();
            completion.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    adapted.complete(MaintenanceWorkOutcome.durable());
                } else {
                    adapted.completeExceptionally(failure);
                }
            });
            return adapted;
        };
    }

    private enum Lane {
        PRIORITY,
        ROUTINE
    }

    private static final class KeyState<V> {
        private Work<V> inFlight;
        private Work<V> priority;
        private Work<V> routine;
        private boolean ready;
        private boolean deferred;
        private Lane deferredLane;
        private long deferralGeneration;
    }

    private static final class Work<V> {
        private final V value;
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
        private final long createdAtNanos;
        private final BinaryOperator<V> prioritySelector;

        private Work(
                V value,
                CompletableFuture<Void> waiter,
                long createdAtNanos,
                BinaryOperator<V> prioritySelector
        ) {
            this(value, createdAtNanos, prioritySelector);
            waiters.add(waiter);
        }

        private Work(
                V value,
                long createdAtNanos,
                BinaryOperator<V> prioritySelector
        ) {
            this.value = value;
            this.createdAtNanos = createdAtNanos;
            this.prioritySelector = prioritySelector;
        }
    }

    private record Launch<K, V>(K key, Work<V> work, Lane lane) {
    }

    private record Resume<K>(K key, Lane lane, long generation) {
    }
}
