package com.alechilles.alecstamework.integration.claims;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

/** Reservation counters and snapshot-duration metrics kept outside claim admission policy code. */
public final class ClaimAdmissionMetrics {
    private final AtomicLong snapshotCount = new AtomicLong();
    private final AtomicLong totalSnapshotNanos = new AtomicLong();
    private final AtomicLong lastSnapshotNanos = new AtomicLong();
    private long reservationsCreated;
    private long reservationsCommitted;
    private long reservationsCanceled;
    private long reservationsExpired;
    private long reservationsInvalidated;
    private final Set<ClaimPopulationKey> observedOverCapClaims = new HashSet<>();

    void reservationCreated() {
        reservationsCreated++;
    }

    void reservationCommitted() {
        reservationsCommitted++;
    }

    void reservationClosed(ClaimAdmissionReservation.State state) {
        switch (state) {
            case CANCELED -> reservationsCanceled++;
            case EXPIRED -> reservationsExpired++;
            case INVALIDATED -> reservationsInvalidated++;
            default -> {
            }
        }
    }

    void recordSnapshotDuration(long elapsedNanos) {
        long safeElapsed = Math.max(0L, elapsedNanos);
        snapshotCount.incrementAndGet();
        totalSnapshotNanos.updateAndGet(current -> saturatedAdd(current, safeElapsed));
        lastSnapshotNanos.set(safeElapsed);
    }

    void claimCapacityObserved(ClaimPopulationKey key, boolean overCap) {
        if (key == null) {
            return;
        }
        if (overCap) {
            observedOverCapClaims.add(key);
        } else {
            observedOverCapClaims.remove(key);
        }
    }

    @Nonnull
    Snapshot snapshot(@Nonnull ClaimOccupancyIndex occupancyIndex,
                      int pendingReservations,
                      long pendingSlots) {
        ClaimOccupancySnapshot occupancy = occupancyIndex.snapshot();
        return new Snapshot(
                occupancyIndex.readiness(),
                occupancy.entriesByProfile().size(),
                occupancy.occupiedProfileCount(),
                pendingSlots,
                pendingReservations,
                reservationsCreated,
                reservationsCommitted,
                reservationsCanceled,
                reservationsExpired,
                reservationsInvalidated,
                observedOverCapClaims.size(),
                snapshotCount.get(),
                totalSnapshotNanos.get(),
                lastSnapshotNanos.get()
        );
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** Aggregate claim-admission metrics used by low-noise runtime diagnostics. */
    public record Snapshot(@Nonnull ClaimOccupancyReadiness readiness,
                           int trackedProfiles,
                           int committedOccupiedProfiles,
                           long pendingSlots,
                           int pendingReservations,
                           long reservationsCreated,
                           long reservationsCommitted,
                           long reservationsCanceled,
                           long reservationsExpired,
                           long reservationsInvalidated,
                           long observedOverCapClaimBuckets,
                           long snapshotCount,
                           long totalSnapshotNanos,
                           long lastSnapshotNanos) {
    }
}
