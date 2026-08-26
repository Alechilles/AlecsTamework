package com.alechilles.alecstamework.companion.population.domain;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Player-feedback regressions for managed admission failures. */
class PopulationAdmissionFailureFeedbackTest {
    @Test
    void husbandryDenialsExplainTheFailedAction() {
        assertEquals(
                "Your Husbandry level is too low to revive this companion.",
                PopulationAdmissionFailureFeedback.describe(
                        new CompletionException(
                                new ManagedAdmissionEvidenceAuthor
                                        .AdmissionDeniedException(
                                        "runehusbandry.admission.family_locked"
                                )
                        ),
                        "revive"
                )
        );
        assertEquals(
                "Husbandry requirements are temporarily unavailable. Try again shortly.",
                PopulationAdmissionFailureFeedback.describe(
                        new IllegalStateException(
                                "runehusbandry.admission.provider_unavailable"
                        ),
                        "release"
                )
        );
    }
}
