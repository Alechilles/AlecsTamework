package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static void assertFeedback(
            BreedingInteractionOutcome outcome,
            String expectedKey,
            Object[] expectedArguments
    ) {
        assertEquals(expectedKey, outcome.feedback().key());
        assertArrayEquals(expectedArguments, outcome.feedback().arguments());
    }
}
