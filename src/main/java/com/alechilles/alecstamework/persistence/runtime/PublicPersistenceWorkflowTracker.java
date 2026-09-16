package com.alechilles.alecstamework.persistence.runtime;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Counts accepted public workflows so shutdown can drain them before the kernel. */
final class PublicPersistenceWorkflowTracker {
    private int outstanding;
    private java.util.concurrent.CompletableFuture<Void> maintenanceDrain;

    /** Reserves shutdown ownership while asynchronously draining ordinary workflows. */
    synchronized CompletionStage<Void> beginMaintenance() {
        if (maintenanceDrain != null) {
            throw new IllegalStateException("database_maintenance_already_running");
        }
        maintenanceDrain = new java.util.concurrent.CompletableFuture<>();
        if (outstanding == 0) {
            maintenanceDrain.complete(null);
        }
        return maintenanceDrain;
    }

    synchronized void endMaintenance() {
        maintenanceDrain = null;
        notifyAll();
    }

    @Nonnull
    <T> CompletionStage<T> track(@Nonnull CompletionStage<T> completion) {
        if (completion == null) {
            throw new IllegalArgumentException(
                    "Tracked workflow completion is required"
            );
        }
        synchronized (this) {
            outstanding++;
        }
        completion.whenComplete((ignored, failure) -> completeOne());
        return completion;
    }

    @Nonnull
    DrainResult drain(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Workflow drain timeout is required and non-negative"
            );
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (this) {
            while (outstanding() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new DrainResult(false, outstanding());
                }
                try {
                    long millis = Math.max(
                            1,
                            java.util.concurrent.TimeUnit.NANOSECONDS
                                    .toMillis(remaining)
                    );
                    wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new DrainResult(false, outstanding());
                }
            }
            return new DrainResult(true, 0);
        }
    }

    synchronized int outstanding() {
        return outstanding + (maintenanceDrain == null ? 0 : 1);
    }

    private void completeOne() {
        java.util.concurrent.CompletableFuture<Void> drained = null;
        synchronized (this) {
            if (outstanding < 1) {
                throw new IllegalStateException("persistence_workflow_tracker_underflow");
            }
            outstanding--;
            if (outstanding == 0) drained = maintenanceDrain;
            notifyAll();
        }
        // Completion may acquire the operation admission monitor; never hold this monitor there.
        if (drained != null) drained.complete(null);
    }

    record DrainResult(boolean drained, int outstanding) {
        DrainResult {
            if (outstanding < 0 || (drained && outstanding != 0)) {
                throw new IllegalArgumentException(
                        "Consistent workflow drain result is required"
                );
            }
        }
    }
}
