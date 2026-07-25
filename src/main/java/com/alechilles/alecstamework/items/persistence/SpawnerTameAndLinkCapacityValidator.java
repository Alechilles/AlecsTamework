package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.population.OwnerPopulationAdmissionPlan;
import com.alechilles.alecstamework.companion.population.OwnerPopulationScope;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupBucket;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupCounts;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.OwnerPopulationCountEvidence;
import com.alechilles.alecstamework.items.persistence.SpawnerTameAndLinkIntentEvidence.PopulationGroupCountEvidence;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates complete authoritative capacity snapshots before tame/link submission. */
final class SpawnerTameAndLinkCapacityValidator {

    void requireOwnerCapacity(
            OwnerPopulationAdmissionPlan plan,
            List<OwnerPopulationCountEvidence> values
    ) {
        Map<OwnerPopulationScope, OwnerPopulationCountEvidence> counts =
                uniqueOwnerCounts(values);
        Set<OwnerPopulationScope> required = plan.increases().stream()
                .map(OwnerPopulationAdmissionPlan.LimitIncrease::scope)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!counts.keySet().equals(required)) {
            throw failure("capture_tame_owner_counts_incomplete");
        }
        for (OwnerPopulationAdmissionPlan.LimitIncrease increase
                : plan.increases()) {
            OwnerPopulationCountEvidence count =
                    counts.get(increase.scope());
            requireCapacity(
                    "capture_tame_owner_capacity_reached",
                    increase.snapshottedLimit(),
                    count.committedCount(),
                    count.pendingCount(),
                    increase.capacityDelta()
            );
        }
    }

    void requireGroupCapacity(
            List<PopulationGroupReservation> reservations,
            List<PopulationGroupCountEvidence> values
    ) {
        Map<PopulationGroupBucket, PopulationGroupCounts> counts =
                uniqueGroupCounts(values);
        Set<PopulationGroupBucket> required = reservations.stream()
                .map(PopulationGroupReservation::bucket)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!counts.keySet().equals(required)) {
            throw failure("capture_tame_group_counts_incomplete");
        }
        for (PopulationGroupReservation reservation : reservations) {
            PopulationGroupCounts count = counts.get(reservation.bucket());
            requireCapacity(
                    "capture_tame_group_owned_capacity_reached",
                    reservation.snapshottedMaxOwned(),
                    count.committedOwned(),
                    count.pendingOwned(),
                    reservation.ownedDelta()
            );
            requireCapacity(
                    "capture_tame_group_active_capacity_reached",
                    reservation.snapshottedMaxActive(),
                    count.committedActive(),
                    count.pendingActive(),
                    reservation.activeDelta()
            );
        }
    }

    private Map<OwnerPopulationScope, OwnerPopulationCountEvidence>
    uniqueOwnerCounts(List<OwnerPopulationCountEvidence> values) {
        HashMap<OwnerPopulationScope, OwnerPopulationCountEvidence> result =
                new HashMap<>();
        for (OwnerPopulationCountEvidence value : values) {
            if (result.putIfAbsent(value.scope(), value) != null) {
                throw failure("capture_tame_owner_counts_duplicate");
            }
        }
        return Map.copyOf(result);
    }

    private Map<PopulationGroupBucket, PopulationGroupCounts>
    uniqueGroupCounts(List<PopulationGroupCountEvidence> values) {
        HashMap<PopulationGroupBucket, PopulationGroupCounts> result =
                new HashMap<>();
        for (PopulationGroupCountEvidence value : values) {
            if (result.putIfAbsent(value.bucket(), value.counts()) != null) {
                throw failure("capture_tame_group_counts_duplicate");
            }
        }
        return Map.copyOf(result);
    }

    private void requireCapacity(
            String detail,
            int limit,
            long committed,
            long pending,
            int delta
    ) {
        if (delta == 0 || limit == 0) {
            return;
        }
        final long total;
        try {
            total = Math.addExact(
                    Math.addExact(committed, pending), delta
            );
        } catch (ArithmeticException overflow) {
            throw failure(detail);
        }
        if (total > limit) {
            throw failure(detail);
        }
    }

    private IllegalArgumentException failure(String detail) {
        return new IllegalArgumentException(detail);
    }
}
