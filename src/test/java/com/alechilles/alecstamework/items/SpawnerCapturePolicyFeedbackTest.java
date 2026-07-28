package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers player-facing feedback selection for terminal capture denials. */
class SpawnerCapturePolicyFeedbackTest {

    @Test
    void tranquilizerRequirementUsesSpecificFeedback() {
        assertEquals(
                "tamework.ui.notifications.capture.tranquilizedRequired",
                SpawnerCapturePolicyService.missingRequiredEffectMessageKey(
                        "Tw_Status_Tranquilized"
                )
        );
    }

    @Test
    void customEffectRequirementUsesGenericFeedback() {
        assertEquals(
                "tamework.ui.notifications.capture.effectRequired",
                SpawnerCapturePolicyService.missingRequiredEffectMessageKey(
                        "Example_Custom_Effect"
                )
        );
    }
}
