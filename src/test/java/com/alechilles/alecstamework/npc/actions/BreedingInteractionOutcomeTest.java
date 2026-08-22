package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for actionable manual-breeding feedback. */
class BreedingInteractionOutcomeTest {

    @Test
    void deniedBreedingReasonsResolveToPlayerSafeFeedback() {
        assertFeedback(
                BreedingInteractionOutcome.cooldown(),
                "tamework.ui.notifications.breeding.cooldown",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.lowHappiness(80.0),
                "tamework.ui.notifications.breeding.happinessTooLow",
                new Object[] {80}
        );
        assertFeedback(
                BreedingInteractionOutcome.waitingForMate(),
                "tamework.ui.notifications.breeding.selectMate",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.capacityReached(),
                "tamework.ui.notifications.breeding.capacityReached",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.claimRequired(),
                "tamework.ui.notifications.breeding.claimRequired",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.progressionRequired(),
                "tamework.ui.notifications.breeding.progressionRequired",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.integrationUnavailable(),
                "tamework.ui.notifications.breeding.integrationUnavailable",
                new Object[0]
        );
        assertFeedback(
                BreedingInteractionOutcome.submitted(),
                "tamework.ui.notifications.breeding.submitted",
                new Object[0]
        );
    }

    @Test
    void finalAdmissionFailureResolvesToActionableFeedback() {
        PopulationAdmissionDecision progressionDenied = new PopulationAdmissionDecision(
                PopulationAdmissionDecision.Status.DENIED,
                "runehusbandry.admission.family_locked",
                null,
                OwnerPopulationCapDecisionViewV2.Readiness.READY,
                10,
                0
        );

        assertEquals(
                BreedingInteractionOutcome.progressionRequired(),
                BreedingLitterCommitService.admissionFailure(progressionDenied, null)
        );
        assertEquals(
                BreedingInteractionOutcome.capacityReached(),
                BreedingLitterCommitService.admissionFailure(
                        PopulationAdmissionDecision.unavailable(
                                "population_domain_owned_capacity_reached"
                        ),
                        null
                )
        );
        assertEquals(
                BreedingInteractionOutcome.integrationUnavailable(),
                BreedingLitterCommitService.admissionFailure(
                        PopulationAdmissionDecision.unavailable("provider-not-ready"),
                        null
                )
        );
        assertEquals(
                BreedingInteractionOutcome.integrationUnavailable(),
                BreedingLitterCommitService.admissionFailure(null, new RuntimeException("offline"))
        );
        assertNull(BreedingLitterCommitService.admissionFailure(
                new PopulationAdmissionDecision(
                        PopulationAdmissionDecision.Status.RESERVED,
                        "reserved",
                        new PopulationAdmissionToken(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Long.MAX_VALUE,
                                1,
                                "test",
                                OwnerPopulationCapDecisionViewV2.Readiness.READY
                        ),
                        OwnerPopulationCapDecisionViewV2.Readiness.READY,
                        1,
                        1
                ),
                null
        ));
    }

    private static void assertFeedback(
            BreedingInteractionOutcome outcome,
            String expectedKey,
            Object[] expectedArguments
    ) {
        assertEquals(expectedKey, outcome.feedback().key());
        assertArrayEquals(expectedArguments, outcome.feedback().arguments());
    }
}
