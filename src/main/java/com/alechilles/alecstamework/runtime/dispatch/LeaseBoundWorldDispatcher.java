package com.alechilles.alecstamework.runtime.dispatch;

import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Dispatches lease-bound world work without allowing shutdown to strand terminal cleanup.
 * A lease-aligned start watchdog rejects accepted work that never begins; its queued wrapper then
 * becomes a no-op so the task and rejection callback remain mutually exclusive.
 */
public final class LeaseBoundWorldDispatcher {
    private static final Executor START_TIMEOUT_EXECUTOR = CompletableFuture.delayedExecutor(
            OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos(),
            TimeUnit.NANOSECONDS
    );

    private LeaseBoundWorldDispatcher() {
    }

    public static void execute(@Nonnull World world, @Nonnull Runnable task) {
        execute(world, task, () -> {
        });
    }

    /**
     * Dispatches lease-bound work to a world. The rejection callback may run outside the world
     * thread and therefore must only close thread-safe state, never live ECS or player state.
     */
    public static void execute(@Nonnull World world,
                               @Nonnull Runnable task,
                               @Nonnull Runnable rejected) {
        Objects.requireNonNull(world, "world");
        execute(world::isAlive, world::execute, task, rejected);
    }

    /**
     * Dispatches through an arbitrary world-like executor while retaining the production lease
     * watchdog. This overload also supplies a deterministic seam for executor adapters.
     *
     * <p>The rejection callback can run on the calling thread or the watchdog executor. It must
     * only close thread-safe state and must never access live ECS or player state.</p>
     */
    public static void execute(@Nonnull BooleanSupplier alive,
                               @Nonnull Consumer<Runnable> dispatcher,
                               @Nonnull Runnable task,
                               @Nonnull Runnable rejected) {
        execute(alive, dispatcher, task, rejected, START_TIMEOUT_EXECUTOR);
    }

    static void execute(@Nonnull BooleanSupplier alive,
                        @Nonnull Consumer<Runnable> dispatcher,
                        @Nonnull Runnable task,
                        @Nonnull Runnable rejected,
                        @Nonnull Executor timeoutExecutor) {
        Objects.requireNonNull(alive, "alive");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
        AtomicReference<DispatchState> state = new AtomicReference<>(DispatchState.PENDING);
        AtomicReference<Runnable> pendingTask = new AtomicReference<>(task);
        AtomicReference<Runnable> pendingRejection = new AtomicReference<>(rejected);
        try {
            if (!alive.getAsBoolean()) {
                rejectPending(state, pendingTask, pendingRejection);
                return;
            }
            timeoutExecutor.execute(() -> rejectPending(state, pendingTask, pendingRejection));
        } catch (RuntimeException | LinkageError failure) {
            rejectPending(state, pendingTask, pendingRejection);
            return;
        }
        try {
            if (!alive.getAsBoolean() || state.get() != DispatchState.PENDING) {
                rejectPending(state, pendingTask, pendingRejection);
                return;
            }
            dispatcher.accept(() -> runStarted(state, pendingTask, pendingRejection));
        } catch (RuntimeException | LinkageError failure) {
            rejectPending(state, pendingTask, pendingRejection);
        }
    }

    private static void runStarted(@Nonnull AtomicReference<DispatchState> state,
                                   @Nonnull AtomicReference<Runnable> pendingTask,
                                   @Nonnull AtomicReference<Runnable> pendingRejection) {
        if (state.compareAndSet(DispatchState.PENDING, DispatchState.STARTED)) {
            Runnable task = pendingTask.getAndSet(null);
            pendingRejection.set(null);
            if (task != null) {
                task.run();
            }
        }
    }

    private static void rejectPending(@Nonnull AtomicReference<DispatchState> state,
                                      @Nonnull AtomicReference<Runnable> pendingTask,
                                      @Nonnull AtomicReference<Runnable> pendingRejection) {
        if (state.compareAndSet(DispatchState.PENDING, DispatchState.REJECTED)) {
            pendingTask.set(null);
            Runnable rejected = pendingRejection.getAndSet(null);
            if (rejected != null) {
                runRejected(rejected);
            }
        }
    }

    private static void runRejected(@Nonnull Runnable rejected) {
        try {
            rejected.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Rejection is already terminal; callback failures must not trigger it twice.
        }
    }

    private enum DispatchState {
        PENDING,
        STARTED,
        REJECTED
    }
}
