package com.alechilles.alecstamework.companion.bonded;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** Bounded signed-world-time expiry sweep for bonded leases. */
public final class BondedCompanionExpirySystem {
    private static final long MAX_PENDING_SCAN_AGE_MS = 10_000L;
    private final BondedCompanionWorldLifecycleObserver observer;
    private final ExpiredLeaseSource leases;
    private final AsyncScanSource scans;
    private final int limit;
    private final int maximumObservations;
    private PendingScan pending;

    public BondedCompanionExpirySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ExpiredLeaseSource leases,
            int limit
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.scans = null;
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.maximumObservations = limit;
    }

    /** Creates an expiry sweep that never joins another world's executor from maintenance. */
    public BondedCompanionExpirySystem(
            @Nonnull BondedCompanionWorldLifecycleObserver observer,
            @Nonnull ExpiredLeaseSource leases,
            @Nonnull AsyncScanSource scans,
            int limit,
            int maximumObservations
    ) {
        this.observer = Objects.requireNonNull(observer, "observer");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.scans = Objects.requireNonNull(scans, "scans");
        if (limit <= 0 || maximumObservations <= 0) {
            throw new IllegalArgumentException("expiry limits must be positive");
        }
        this.limit = limit;
        this.maximumObservations = maximumObservations;
    }

    /** Reconciles at most the configured number of finite expired leases. */
    public int tick(long nowMs) {
        if (scans != null) {
            return tickAsynchronously(nowMs);
        }
        return tickSynchronously(nowMs);
    }

    private int tickSynchronously(long nowMs) {
        List<BondedCompanionProjectionValidator.LeaseExpectation> candidates =
                Objects.requireNonNull(leases.findExpired(nowMs, limit),
                        "expired leases");
        int expired = 0;
        for (var lease : candidates) {
            if (lease != null && isExpired(lease.expiresAtMs(), nowMs)) {
                observer.onLeaseExpired(lease, nowMs);
                expired++;
                if (expired == limit) {
                    break;
                }
            }
        }
        return expired;
    }

    private int tickAsynchronously(long nowMs) {
        if (pending != null) {
            return consumePending(nowMs);
        }
        List<BondedCompanionProjectionValidator.LeaseExpectation> candidates =
                expiredLeases(nowMs);
        if (candidates.isEmpty()) {
            return 0;
        }
        CompletionStage<BondedCompanionProjectionRecoverySystem.ScanResult> stage;
        try {
            stage = scans.scan(candidates, maximumObservations);
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
        if (stage == null) {
            return 0;
        }
        try {
            pending = new PendingScan(candidates, stage.toCompletableFuture(), nowMs);
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
        return pending.scan().isDone() ? consumePending(nowMs) : 0;
    }

    private int consumePending(long nowMs) {
        PendingScan current = pending;
        if (current == null) {
            return 0;
        }
        if (!current.scan().isDone()) {
            if (nowMs - current.submittedAtMs() >= MAX_PENDING_SCAN_AGE_MS) {
                pending = null;
            }
            return 0;
        }
        pending = null;
        BondedCompanionProjectionRecoverySystem.ScanResult result;
        try {
            result = current.scan().getNow(null);
        } catch (CompletionException | java.util.concurrent.CancellationException ignored) {
            return 0;
        }
        if (result == null) {
            return 0;
        }
        int expired = 0;
        for (var lease : current.leases()) {
            if (result.conclusivelyScanned().contains(lease)) {
                observer.onLeaseExpired(lease, result.observed(), nowMs);
                expired++;
            }
        }
        return expired;
    }

    private List<BondedCompanionProjectionValidator.LeaseExpectation> expiredLeases(
            long nowMs
    ) {
        return Objects.requireNonNull(leases.findExpired(nowMs, limit),
                "expired leases").stream().filter(lease -> lease != null
                && isExpired(lease.expiresAtMs(), nowMs)).limit(limit).toList();
    }

    /** Zero alone means unlimited; negative finite timestamps remain valid. */
    public static boolean isExpired(long expiresAtMs, long nowMs) {
        return expiresAtMs != 0L && expiresAtMs <= nowMs;
    }

    @FunctionalInterface
    public interface ExpiredLeaseSource {
        @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation>
                findExpired(long nowMs, int limit);
    }

    /** Supplies a bounded immutable world observation that is safe to consume on a later tick. */
    @FunctionalInterface
    public interface AsyncScanSource {
        @Nonnull
        CompletionStage<BondedCompanionProjectionRecoverySystem.ScanResult> scan(
                @Nonnull List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
                int maximumObservations
        );
    }

    private record PendingScan(
            List<BondedCompanionProjectionValidator.LeaseExpectation> leases,
            java.util.concurrent.CompletableFuture<
                    BondedCompanionProjectionRecoverySystem.ScanResult> scan,
            long submittedAtMs
    ) { }
}
