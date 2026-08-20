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
import javax.annotation.Nonnull;

/**
 * Bounds asynchronous newest-wins work while preserving durable completion
 * outcomes for every accepted caller.
 *
 * <p>The coordinator owns one synchronized state machine. User handlers and
 * completion callbacks are always invoked after that state lock is released.
 */
public final class LatestWorkCoordinator<K, V> {
    private final Object stateLock = new Object();
    private final int maxInFlight;
    private final BiFunction<? super K, ? super V,
            ? extends CompletionStage<Void>> handler;
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

    public LatestWorkCoordinator(
            int maxInFlight,
            @Nonnull BiFunction<? super K, ? super V,
                    ? extends CompletionStage<Void>> handler
    ) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("At least one in-flight slot is required");
        }
        this.maxInFlight = maxInFlight;
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /**
     * Accepts a value, replacing only an older pending value for the same key.
     * The returned stage completes when the retained value is durable.
     */
    @Nonnull
    public CompletionStage<Void> submit(@Nonnull K key, @Nonnull V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        boolean rejected;
        synchronized (stateLock) {
            rejected = !accepting;
            if (!rejected) {
                submissions++;
                acceptLocked(key, value, waiter);
            }
        }
        if (rejected) {
            waiter.completeExceptionally(
                    new RejectedExecutionException("Maintenance coordinator is closed")
            );
            return waiter;
        }
        startReadyWork();
        return waiter;
    }

    /**
     * Waits for the newest value currently accepted for a key.
     * A later pending replacement may satisfy this call-time fence because it
     * supersedes the value observed by the flush. A missing key is flushed.
     */
    @Nonnull
    public CompletionStage<Void> flush(@Nonnull K key) {
        Objects.requireNonNull(key, "key");
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        boolean alreadyFlushed = false;
        synchronized (stateLock) {
            KeyState<V> state = states.get(key);
            if (state == null) {
                alreadyFlushed = true;
            } else {
                Work<V> target = state.pending != null
                        ? state.pending : state.inFlight;
                if (target == null) {
                    alreadyFlushed = true;
                } else {
                    target.waiters.add(waiter);
                }
            }
        }
        if (alreadyFlushed) {
            waiter.complete(null);
        }
        return waiter;
    }

    /** Returns an immutable point-in-time view of coordinator activity. */
    @Nonnull
    public MaintenanceMetricsSnapshot metrics() {
        synchronized (stateLock) {
            return new MaintenanceMetricsSnapshot(
                    submissions,
                    replacements,
                    completions,
                    failures,
                    pendingKeysLocked(),
                    inFlightWork,
                    maximumInFlightWork,
                    oldestPendingAgeLocked()
            );
        }
    }

    /**
     * Stops admission, starts retained work, and waits for the given deadline.
     * This coordinator owns no executor, so shutdown never interrupts work.
     * Handlers must return their completion stage promptly; the coordinator
     * cannot preempt a handler blocked inside {@code apply}.
     */
    @Nonnull
    public MaintenanceDrainResult shutdown(@Nonnull Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Shutdown timeout must be non-negative");
        }
        long deadline = deadline(System.nanoTime(), timeout);
        synchronized (stateLock) {
            accepting = false;
            enqueuePendingKeysLocked();
        }
        startReadyWork();
        synchronized (stateLock) {
            while (inFlightWork > 0 || pendingKeysLocked() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return drainResultLocked(false);
                }
                if (!awaitNanosLocked(remaining)) {
                    return drainResultLocked(false);
                }
            }
            return drainResultLocked(true);
        }
    }

    private void acceptLocked(K key, V value, CompletableFuture<Void> waiter) {
        KeyState<V> state = states.computeIfAbsent(key, ignored -> new KeyState<>());
        if (state.pending == null) {
            state.pending = new Work<>(value, waiter, System.nanoTime());
        } else {
            state.pending = state.pending.replace(value, waiter);
            replacements++;
        }
        enqueueIfReadyLocked(key, state);
    }

    private void enqueueIfReadyLocked(K key, KeyState<V> state) {
        if (state.inFlight == null && state.pending != null && !state.ready) {
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
                if (state.inFlight != null || state.pending == null) {
                    continue;
                }
                Work<V> work = state.pending;
                state.pending = null;
                state.inFlight = work;
                inFlightWork++;
                maximumInFlightWork = Math.max(
                        maximumInFlightWork,
                        inFlightWork
                );
                return new Launch<>(key, work);
            }
            return null;
        }
    }

    private void invokeHandler(Launch<K, V> launch) {
        CompletionStage<Void> completion;
        try {
            completion = handler.apply(launch.key, launch.work.value);
            if (completion == null) {
                throw new NullPointerException("Maintenance handler returned null");
            }
        } catch (Throwable failure) {
            complete(launch, failure);
            return;
        }
        try {
            completion.whenComplete((ignored, failure) -> complete(launch, failure));
        } catch (Throwable failure) {
            complete(launch, failure);
        }
    }

    private void complete(Launch<K, V> launch, Throwable failure) {
        List<CompletableFuture<Void>> waiters;
        synchronized (stateLock) {
            KeyState<V> state = states.get(launch.key);
            if (state == null || state.inFlight != launch.work) {
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
            if (state.pending == null) {
                states.remove(launch.key);
            } else {
                enqueueIfReadyLocked(launch.key, state);
            }
            stateLock.notifyAll();
        }
        completeWaiters(waiters, failure);
        startReadyWork();
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
                drained,
                pendingKeysLocked(),
                inFlightWork
        );
    }

    private int pendingKeysLocked() {
        int pending = 0;
        for (KeyState<V> state : states.values()) {
            if (state.pending != null) {
                pending++;
            }
        }
        return pending;
    }

    private long oldestPendingAgeLocked() {
        long now = System.nanoTime();
        long oldest = 0;
        for (KeyState<V> state : states.values()) {
            if (state.pending == null) {
                continue;
            }
            long age = now - state.pending.createdAtNanos;
            if (age < 0) {
                age = Long.MAX_VALUE;
            }
            oldest = Math.max(oldest, age);
        }
        return oldest;
    }

    private boolean awaitNanosLocked(long remaining) {
        try {
            long millis = remaining / 1_000_000L;
            int nanos = (int) (remaining % 1_000_000L);
            stateLock.wait(millis, nanos);
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
        if (start > 0 && nanos >= Long.MAX_VALUE - start) {
            return Long.MAX_VALUE;
        }
        return start + nanos;
    }

    private static final class KeyState<V> {
        private Work<V> inFlight;
        private Work<V> pending;
        private boolean ready;
    }

    private static final class Work<V> {
        private final V value;
        private final List<CompletableFuture<Void>> waiters = new ArrayList<>();
        private final long createdAtNanos;

        private Work(V value, CompletableFuture<Void> waiter, long createdAtNanos) {
            this.value = value;
            this.waiters.add(waiter);
            this.createdAtNanos = createdAtNanos;
        }

        private Work<V> replace(V replacement, CompletableFuture<Void> waiter) {
            Work<V> next = new Work<>(replacement, waiter, createdAtNanos);
            next.waiters.addAll(waiters);
            return next;
        }
    }

    private record Launch<K, V>(K key, Work<V> work) {
    }
}
