package com.alechilles.alecstamework.companion.population.group;

import java.util.List;
import javax.annotation.Nonnull;

/** Exact assignment replacement plus all positive headroom reservations. */
public record PopulationGroupAssignmentPlan(
        @Nonnull PopulationGroupAssignment target,
        @Nonnull List<PopulationGroupReservation> reservations
) {
    public PopulationGroupAssignmentPlan {
        if (target == null || reservations == null
                || reservations.stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Complete population group assignment plan is required"
            );
        }
        reservations = reservations.stream()
                .sorted(java.util.Comparator.comparing(
                        reservation -> reservation.bucket()
                ))
                .toList();
    }
}
