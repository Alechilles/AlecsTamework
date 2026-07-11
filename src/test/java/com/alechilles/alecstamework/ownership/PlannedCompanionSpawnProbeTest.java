package com.alechilles.alecstamework.ownership;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the pure decision boundary used after NPCPlugin throws during deterministic spawn. */
class PlannedCompanionSpawnProbeTest {
    @Test
    void missingOrInvalidReferenceIsTheOnlyProvenAbsentOutcome() {
        UUID planned = UUID.randomUUID();

        assertEquals(
                PlannedCompanionSpawnProbe.Outcome.ABSENT,
                PlannedCompanionSpawnProbe.classify(false, null, planned, false)
        );
    }

    @Test
    void exactPlannedIdentityWithNpcComponentIsRecoveredAsLive() {
        UUID planned = UUID.randomUUID();

        assertEquals(
                PlannedCompanionSpawnProbe.Outcome.PRESENT,
                PlannedCompanionSpawnProbe.classify(true, planned, planned, true)
        );
    }

    /** Regression: incomplete post-add evidence must never be interpreted as safe cancellation. */
    @Test
    void incompleteOrConflictingLiveEvidenceRemainsAmbiguous() {
        UUID planned = UUID.randomUUID();

        assertEquals(
                PlannedCompanionSpawnProbe.Outcome.AMBIGUOUS,
                PlannedCompanionSpawnProbe.classify(true, null, planned, false)
        );
        assertEquals(
                PlannedCompanionSpawnProbe.Outcome.AMBIGUOUS,
                PlannedCompanionSpawnProbe.classify(
                        true, UUID.randomUUID(), planned, true
                )
        );
        assertEquals(
                PlannedCompanionSpawnProbe.Outcome.AMBIGUOUS,
                PlannedCompanionSpawnProbe.classify(true, planned, planned, false)
        );
    }
}
