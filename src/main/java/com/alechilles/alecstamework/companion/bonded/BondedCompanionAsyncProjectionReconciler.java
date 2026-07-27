package com.alechilles.alecstamework.companion.bonded;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Defers lifecycle reconciliation until a bounded fan-out scan has completed on the owning world
 * threads. Queue access is synchronized because lifecycle notifications can arrive independently
 * of maintenance ticks. Incomplete evidence is retried and never changes companion state.
 */
public final class BondedCompanionAsyncProjectionReconciler {
    private static final int DEFAULT_MAXIMUM_PENDING = 256;
    private static final long DEFAULT_SCAN_TIMEOUT_NANOS = 10_000_000_000L;

    private final BondedCompanionWorldLifecycleObserver observer;
    private final ScanSource scans;
    private final int maximumObservations;
    private final int maximumPending;
    private final long scanTimeoutNanos;
    private final LongSupplier monotonicNanos;
    private final Map<RecoveryKey, PendingReconciliation> pending = new HashMap<>();
    private final Object tickLock = new Object();

    public BondedCompanionAsyncProjectionReconciler(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ScanSource scans,
            int maximumObservations
    ) {
        this(observer, scans, maximumObservations, DEFAULT_MAXIMUM_PENDING,
                DEFAULT_SCAN_TIMEOUT_NANOS, System::nanoTime);
    }

    BondedCompanionAsyncProjectionReconciler(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ScanSource scans,
            int maximumObservations,
            int maximumPending,
            long scanTimeoutNanos,
            @Nonnull LongSupplier monotonicNanos
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.scans = Objects.requireNonNull(scans, "scans");
        if (maximumObservations < 1 || maximumPending < 1 || scanTimeoutNanos < 1) {
            throw new IllegalArgumentException("reconciliation limits must be positive");
        }
        this.maximumObservations = maximumObservations;
        this.maximumPending = maximumPending;
        this.scanTimeoutNanos = scanTimeoutNanos;
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /**
     * Queues exact leases for a fan-out scan. Returns false when the bounded queue is full; the
     * caller must treat that result as deferred rather than assuming any reconciliation occurred.
     */
    public boolean reconcileAsync(
            @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            @Nonnull BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs
    ) {
        Map<RecoveryKey, BondedCompanionProjectionValidator.LeaseExpectation> incoming =
                new HashMap<>();
        for (BondedCompanionProjectionValidator.LeaseExpectation lease : leases) {
            if (lease != null) incoming.put(RecoveryKey.from(lease), lease);
        }
        if (incoming.isEmpty()) return true;
        BondedCompanionProjectionService.RecoveryCause exactCause =
                Objects.requireNonNull(cause, "cause");
        long startedAtNanos = monotonicNanos.getAsLong();
        synchronized (pending) {
            long newEntries = incoming.keySet().stream().filter(key -> !pending.containsKey(key))
                    .count();
            if (pending.size() + newEntries > maximumPending) return false;
            for (Map.Entry<RecoveryKey,
                    BondedCompanionProjectionValidator.LeaseExpectation> entry : incoming.entrySet()) {
                if (!pending.containsKey(entry.getKey())) {
                    BondedCompanionProjectionValidator.LeaseExpectation lease = entry.getValue();
                    pending.put(entry.getKey(), new PendingReconciliation(lease, exactCause,
                            observedAtMs, startedAtNanos, beginScan(lease)));
                }
            }
            return true;
        }
    }

    /** Consumes completed scans, cancelling and retrying stalled scans without blocking a thread. */
    public int tick() {
        synchronized (tickLock) {
            return tickSerially();
        }
    }

    private int tickSerially() {
        List<PendingReconciliation> current;
        synchronized (pending) {
            current = new ArrayList<>(pending.values());
        }
        int completed = 0;
        long nowNanos = monotonicNanos.getAsLong();
        for (PendingReconciliation reconciliation : current) {
            if (!owns(reconciliation)) continue;
            if (!reconciliation.scan().isDone()) {
                if (nowNanos - reconciliation.startedAtNanos() >= scanTimeoutNanos) {
                    reconciliation.scan().cancel(true);
                    retry(reconciliation, nowNanos);
                }
                continue;
            }
            BondedCompanionProjectionRecoverySystem.ScanResult result =
                    completed(reconciliation.scan());
            if (result == null) {
                retry(reconciliation, nowNanos);
                continue;
            }
            if (!result.conclusivelyScanned().contains(reconciliation.lease())) {
                retry(reconciliation, nowNanos);
                continue;
            }
            observer.onScanned(List.of(reconciliation.lease()), result.observed(),
                    reconciliation.cause(), reconciliation.observedAtMs());
            remove(reconciliation);
            completed++;
        }
        return completed;
    }

    int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    private boolean owns(PendingReconciliation reconciliation) {
        synchronized (pending) {
            return pending.get(RecoveryKey.from(reconciliation.lease())) == reconciliation;
        }
    }

    private BondedCompanionProjectionRecoverySystem.ScanResult completed(
            CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> scan
    ) {
        try {
            return scan.getNow(null);
        } catch (CompletionException | CancellationException failure) {
            return null;
        }
    }

    private void retry(PendingReconciliation current, long startedAtNanos) {
        replace(current, new PendingReconciliation(current.lease(), current.cause(),
                current.observedAtMs(), startedAtNanos, beginScan(current.lease())));
    }

    private void remove(PendingReconciliation current) {
        synchronized (pending) {
            pending.remove(RecoveryKey.from(current.lease()), current);
        }
    }

    private void replace(PendingReconciliation oldValue, PendingReconciliation newValue) {
        synchronized (pending) {
            pending.replace(RecoveryKey.from(oldValue.lease()), oldValue, newValue);
        }
    }

    private CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> beginScan(
            BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        try {
            CompletionStage<BondedCompanionProjectionRecoverySystem.ScanResult> stage =
                    scans.scan(List.of(lease), maximumObservations);
            return stage == null ? CompletableFuture.completedFuture(null) : stage.toCompletableFuture();
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Stable identity across repeated lifecycle notifications for the same active lease. */
    private record RecoveryKey(java.util.UUID ownerUuid, String rosterId, String profileId,
                               String leaseId) {
        private static RecoveryKey from(
                BondedCompanionProjectionValidator.LeaseExpectation lease
        ) {
            return new RecoveryKey(lease.ownerUuid(), lease.rosterId(), lease.profileId(),
                    lease.leaseToken());
        }
    }

    /** Schedules bounded marker reads only; implementations must fan out to owning worlds. */
    @FunctionalInterface
    public interface ScanSource {
        @Nonnull CompletionStage<BondedCompanionProjectionRecoverySystem.ScanResult> scan(
                @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
                int maximumObservations
        );
    }

    private record PendingReconciliation(
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionService.RecoveryCause cause,
            long observedAtMs,
            long startedAtNanos,
            CompletableFuture<BondedCompanionProjectionRecoverySystem.ScanResult> scan
    ) {
    }
}
