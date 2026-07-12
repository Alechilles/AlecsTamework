package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression: a marker found under another UUID must block a replacement child spawn. */
class BreedingChildProjectionProbeTest {
    @Test
    void exactUuidAndExactMarkerAreTheOnlyPresentOutcome() {
        assertEquals(
                BreedingChildProjectionProbe.Outcome.PRESENT,
                BreedingChildProjectionProbe.classify(
                        BreedingChildProjectionProbe.ExactOutcome.PRESENT,
                        true,
                        0
                )
        );
    }

    @Test
    void matchingMarkerUnderANonCanonicalUuidIsAmbiguous() {
        assertEquals(
                BreedingChildProjectionProbe.Outcome.AMBIGUOUS,
                BreedingChildProjectionProbe.classify(
                        BreedingChildProjectionProbe.ExactOutcome.ABSENT,
                        false,
                        1
                )
        );
    }

    @Test
    void exactChildPlusAnotherMatchingMarkerIsAmbiguous() {
        assertEquals(
                BreedingChildProjectionProbe.Outcome.AMBIGUOUS,
                BreedingChildProjectionProbe.classify(
                        BreedingChildProjectionProbe.ExactOutcome.PRESENT,
                        true,
                        1
                )
        );
    }

    @Test
    void absenceRequiresNoExactUuidAndNoMatchingMarker() {
        assertEquals(
                BreedingChildProjectionProbe.Outcome.ABSENT,
                BreedingChildProjectionProbe.classify(
                        BreedingChildProjectionProbe.ExactOutcome.ABSENT,
                        false,
                        0
                )
        );
    }
}
