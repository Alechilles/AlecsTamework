package com.alechilles.alecstamework.ownership;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Lock-confined counters and snapshot math kept out of the owner-index authority. */
public final class OwnerPopulationMetrics {
    private long reservationsCreated;
    private long reservationsCommitted;
    private long reservationsCanceled;
    private long reservationsExpired;

    void reservationCreated() {
        reservationsCreated++;
    }

    void reservationCommitted() {
        reservationsCommitted++;
    }

    void reservationCanceled() {
        reservationsCanceled++;
    }

    void reservationExpired() {
        reservationsExpired++;
    }

    Snapshot snapshot(OwnerPopulationReadinessState readiness,
                      Map<String, OwnerPopulationEntry> entries,
                      Map<OwnerPopulationScopeKey, Long> committed,
                      Map<OwnerPopulationScopeKey, Long> pending,
                      int pendingReservations,
                      OwnerPopulationLimitScope configuredScope,
                      int configuredLimit) {
        long ownedProfiles = 0L;
        for (OwnerPopulationEntry entry : entries.values()) {
            if (entry.consumesOwnerSlot()) {
                ownedProfiles++;
            }
        }
        return new Snapshot(
                readiness.forScope(OwnerPopulationLimitScope.GLOBAL),
                readiness.forScope(OwnerPopulationLimitScope.PER_WORLD),
                entries.size(),
                ownedProfiles,
                sumForScope(committed, OwnerPopulationLimitScope.GLOBAL),
                sumForScope(pending, OwnerPopulationLimitScope.GLOBAL),
                pendingReservations,
                overCapBuckets(committed, pending, configuredScope, configuredLimit),
                reservationsCreated,
                reservationsCommitted,
                reservationsCanceled,
                reservationsExpired
        );
    }

    private static long overCapBuckets(Map<OwnerPopulationScopeKey, Long> committed,
                                       Map<OwnerPopulationScopeKey, Long> pending,
                                       OwnerPopulationLimitScope scope,
                                       int limit) {
        if (limit <= 0) {
            return 0L;
        }
        Set<OwnerPopulationScopeKey> keys = new HashSet<>(committed.keySet());
        keys.addAll(pending.keySet());
        long total = 0L;
        for (OwnerPopulationScopeKey key : keys) {
            if (key.scope() == scope
                    && committed.getOrDefault(key, 0L) + pending.getOrDefault(key, 0L) > limit) {
                total++;
            }
        }
        return total;
    }

    private static long sumForScope(Map<OwnerPopulationScopeKey, Long> counts,
                                    OwnerPopulationLimitScope scope) {
        long total = 0L;
        for (Map.Entry<OwnerPopulationScopeKey, Long> entry : counts.entrySet()) {
            if (entry.getKey().scope() == scope) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** Aggregate owner-index metrics without exposing mutable index state. */
    public record Snapshot(OwnerPopulationReadiness globalReadiness,
                           OwnerPopulationReadiness perWorldReadiness,
                           int profileCount,
                           long ownedProfileCount,
                           long committedGlobalSlots,
                           long pendingGlobalSlots,
                           int pendingReservations,
                           long overCapBuckets,
                           long reservationsCreated,
                           long reservationsCommitted,
                           long reservationsCanceled,
                           long reservationsExpired) {
    }
}
