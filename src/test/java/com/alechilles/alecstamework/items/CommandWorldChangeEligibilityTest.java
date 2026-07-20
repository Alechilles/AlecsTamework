package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression coverage for automatic companion travel admission at login and world changes. */
class CommandWorldChangeEligibilityTest {
    @Test
    void legacyRecordWithoutCommandStateDoesNotBecomeAutomaticRecall() {
        assertFalse(CommandWorldChangeEligibility.isEligible(
                record(null),
                TwCompanionConfig.resolveEffectiveForRole("Tamed_Deer_Stag")
        ));
    }

    @Test
    void recordedFollowStateDoesNotOverrideDisabledAutomaticTravel() {
        assertFalse(CommandWorldChangeEligibility.isEligible(
                record("Follow"),
                TwCompanionConfig.resolveEffectiveForRole("Tamed_Deer_Stag")
        ));
    }

    @Test
    void recordedHoldStateDoesNotTravel() {
        assertFalse(CommandWorldChangeEligibility.isEligible(
                record("Hold"),
                TwCompanionConfig.resolveEffectiveForRole("Tamed_Deer_Stag")
        ));
    }

    private LinkedNpcRecord record(String commandState) {
        return new LinkedNpcRecord(
                UUID.randomUUID(),
                null,
                null,
                "Stag",
                null,
                "Tamed_Deer_Stag",
                commandState,
                true,
                false,
                null
        );
    }
}
