package com.alechilles.alecstamework.ui;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkedNpcPanelStatusTextServiceTest {

    @Test
    void exposesLostStatusAndRecoveryHints() {
        LinkedNpcEntry lostEntry = new LinkedNpcEntry(
                UUID.randomUUID(),
                "Lost Companion",
                0,
                0,
                0,
                0,
                null,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                true,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );

        assertEquals("LOST", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(lostEntry));
        assertEquals(
                "Lost companion. Use Respawn to recover.",
                LinkedNpcPanelStatusTextService.resolveUnavailableHealthText(lostEntry)
        );
        assertEquals(
                "Happiness: unavailable (lost).",
                LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(lostEntry)
        );
    }

    @Test
    void prioritizesDeadOverLostAvailabilityStatus() {
        LinkedNpcEntry deadAndLostEntry = new LinkedNpcEntry(
                UUID.randomUUID(),
                "Dead Lost Companion",
                0,
                0,
                0,
                0,
                null,
                0,
                0,
                0,
                0,
                false,
                false,
                true,
                false,
                true,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );

        assertEquals("DEAD", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(deadAndLostEntry));
    }
}
