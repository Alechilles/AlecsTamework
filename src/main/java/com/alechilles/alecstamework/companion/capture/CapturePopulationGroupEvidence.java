package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentPlan;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupReservation;
import java.util.HashSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Frozen source assignment plus exact target classification and positive reservations. */
public record CapturePopulationGroupEvidence(
        @Nullable PopulationGroupAssignment expectedAssignment,
        @Nonnull PopulationGroupAssignmentPlan targetPlan
) {
    public CapturePopulationGroupEvidence {
        if (targetPlan == null) {
            throw new IllegalArgumentException(
                    "Capture population group target plan is required"
            );
        }
        PopulationGroupAssignment target = targetPlan.target();
        long expectedRevision = expectedAssignment == null
                ? 0
                : expectedAssignment.assignmentRevision();
        if (expectedAssignment != null
                && !expectedAssignment.profileId().equals(
                target.profileId()
        )
                || target.assignmentRevision()
                != Math.addExact(expectedRevision, 1)) {
            throw new IllegalArgumentException(
                    "Capture group assignment revision is inconsistent"
            );
        }
        HashSet<Object> buckets = new HashSet<>();
        for (PopulationGroupReservation reservation
                : targetPlan.reservations()) {
            if (!reservation.profileId().equals(target.profileId())
                    || !buckets.add(reservation.bucket())) {
                throw new IllegalArgumentException(
                        "Capture group reservations must be profile-consistent and unique"
                );
            }
        }
    }
}
