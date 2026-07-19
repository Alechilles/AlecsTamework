package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.health.PersistenceStorageState;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/** Runs one coalesced global recovery probe with bounded exponential retry. */
public final class StorageRecoveryCoordinator implements AutoCloseable {
    static final long INITIAL_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 300_000L;

    private final PersistenceStorageHealthService storageHealth;
    private final StorageRecoveryProbe probe;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean scheduledOrRunning = new AtomicBoolean();
    private final AtomicLong attempts = new AtomicLong();
    private volatile CompletableFuture<StorageRecoveryProbe.ProbeResult> activeRequest;
    private volatile ScheduledFuture<?> scheduledFuture;

    public StorageRecoveryCoordinator(@Nonnull PersistenceStorageHealthService storageHealth,
                                      @Nonnull StorageRecoveryProbe probe) {
        this.storageHealth = storageHealth;
        this.probe = probe;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tamework-storage-recovery");
            thread.setDaemon(true);
            return thread;
        });
        storageHealth.addTransitionListener(this::onStorageTransition);
    }

    /** Observes an already-read-only bootstrap result after all probe dependencies are composed. */
    public void start() {
        onStorageTransition(storageHealth.getState());
    }

    @Nonnull
    public synchronized CompletableFuture<StorageRecoveryProbe.ProbeResult> requestNow() {
        if (activeRequest != null && !activeRequest.isDone()) return activeRequest;
        CompletableFuture<StorageRecoveryProbe.ProbeResult> result = new CompletableFuture<>();
        activeRequest = result;
        ScheduledFuture<?> scheduled = scheduledFuture;
        if (scheduled != null && scheduled.cancel(false)) {
            scheduledFuture = null;
            scheduledOrRunning.set(false);
        }
        if (scheduledOrRunning.compareAndSet(false, true)) {
            executor.execute(this::runProbe);
        }
        return result;
    }

    public long attempts() {
        return attempts.get();
    }

    private void onStorageTransition(PersistenceStorageHealthService.State state) {
        if (state.status() != PersistenceStorageState.READ_ONLY || !autoRecoverable(state.reason())) return;
        schedule(delayFor(attempts.get()));
    }

    private void schedule(long delayMs) {
        if (!scheduledOrRunning.compareAndSet(false, true)) return;
        scheduledFuture = executor.schedule(this::runProbe, delayMs, TimeUnit.MILLISECONDS);
    }

    private void runProbe() {
        StorageRecoveryProbe.ProbeResult result;
        try {
            scheduledFuture = null;
            attempts.incrementAndGet();
            result = probe.probe();
        } catch (Throwable failure) {
            result = new StorageRecoveryProbe.ProbeResult(
                    StorageRecoveryProbe.ProbeStatus.RETAINED_READ_ONLY,
                    "storage_recovery_coordinator_failed", 0L, failure);
        } finally {
            scheduledOrRunning.set(false);
        }
        completeActiveRequest(result);
        if (!result.recovered() && autoRecoverable(storageHealth.getState().reason())) {
            schedule(delayFor(attempts.get()));
        }
    }

    private synchronized void completeActiveRequest(StorageRecoveryProbe.ProbeResult result) {
        CompletableFuture<StorageRecoveryProbe.ProbeResult> request = activeRequest;
        activeRequest = null;
        if (request != null) request.complete(result);
    }

    private long delayFor(long completedAttempts) {
        int shift = (int) Math.max(0L, Math.min(18L, completedAttempts));
        return Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS << shift);
    }

    private boolean autoRecoverable(String reason) {
        if (reason == null) return false;
        String normalized = reason.toLowerCase(Locale.ROOT);
        return !normalized.contains("integrity")
                && !normalized.contains("corrupt")
                && !normalized.contains("schema")
                && !normalized.contains("migration")
                && !normalized.contains("invariant");
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
