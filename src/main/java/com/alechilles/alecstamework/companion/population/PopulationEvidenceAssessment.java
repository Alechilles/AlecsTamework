package com.alechilles.alecstamework.companion.population;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Typed population reconciliation evidence result.
 *
 * <p>Only {@link Status#PRESENT_MATCH}, {@link Status#PRESENT_CONTRADICTION}, and
 * {@link Status#ABSENT_PROVEN} authorize reconciliation work.</p>
 */
public record PopulationEvidenceAssessment(
        @Nonnull Status status,
        @Nonnull String reasonCode,
        @Nullable PopulationEvidenceObservation observation
) {
    public PopulationEvidenceAssessment {
        if (status == null || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Evidence assessment status and reason are required"
            );
        }
        reasonCode = reasonCode.trim();
        boolean present = status == Status.PRESENT_MATCH
                || status == Status.PRESENT_CONTRADICTION
                || status == Status.PRESENT_INCOMPLETE;
        if (present != (observation != null)) {
            throw new IllegalArgumentException(
                    "Only present assessments carry an observation"
            );
        }
    }

    /** Exact outcome vocabulary; incomplete evidence can never be interpreted as absence. */
    public enum Status {
        PRESENT_MATCH,
        PRESENT_CONTRADICTION,
        PRESENT_INCOMPLETE,
        ABSENT_PROVEN,
        INCOMPLETE
    }

    /** Returns whether this result authorizes a canonical or containment operation. */
    public boolean actionable() {
        return status == Status.PRESENT_MATCH
                || status == Status.PRESENT_CONTRADICTION
                || status == Status.ABSENT_PROVEN;
    }
}
