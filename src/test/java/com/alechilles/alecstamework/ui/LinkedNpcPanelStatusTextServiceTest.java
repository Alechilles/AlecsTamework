package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LinkedNpcPanelStatusTextServiceTest {

    @Test
    void includesDeathCauseInDeadTooltipWhileKeepingRespawnStatePrimary() {
        LinkedNpcEntry deadEntry = new LinkedNpcEntry(
                UUID.randomUUID(),
                "Dead Companion",
                0,
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
                false,
                false,
                15_000L,
                "Died from starvation",
                null,
                null,
                LinkedNpcTraitIndicator.EMPTY,
                false,
                false,
                false,
                false,
                true,
                true,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                0L,
                0.0,
                false
        );

        assertEquals(
                LocalizedText.format(
                        (String) null,
                        "tamework.ui.linkedPanel.health.deadRespawnIn",
                        LocalizedText.format((String) null, "tamework.ui.shared.duration.seconds", 15)
                ),
                LinkedNpcPanelStatusTextService.resolveDeadHealthText(deadEntry)
        );
        assertEquals(
                LinkedNpcPanelStatusTextService.resolveDeadHealthText(deadEntry) + "\nDied from starvation",
                LinkedNpcPanelStatusTextService.resolveDeadHealthTooltip(deadEntry)
        );
    }

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
                false,
                true,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );

        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.status.lost"),
                LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(lostEntry)
        );
        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.health.unavailable.lost"),
                LinkedNpcPanelStatusTextService.resolveUnavailableHealthText(lostEntry)
        );
        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.unavailable.lost"),
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
                false,
                true,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );

        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.status.dead"),
                LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(deadAndLostEntry)
        );
    }

    @Test
    void exposesInCoopStatusAndHints() {
        LinkedNpcEntry inCoopEntry = new LinkedNpcEntry(
                UUID.randomUUID(),
                "Cooped Companion",
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
                false,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );

        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.status.inCoop"),
                LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(inCoopEntry)
        );
        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.health.unavailable.inCoop"),
                LinkedNpcPanelStatusTextService.resolveUnavailableHealthText(inCoopEntry)
        );
        assertEquals(
                LocalizedText.resolve((String) null, "tamework.ui.linkedPanel.happiness.unavailable.inCoop"),
                LinkedNpcPanelStatusTextService.resolveUnavailableHappinessText(inCoopEntry)
        );
    }

    @Test
    void saturatedCooldownFormattingNeverWrapsNegative() {
        String clock = LinkedNpcPanelStatusTextService.formatRemainingClock(Long.MAX_VALUE);
        String duration = LinkedNpcPanelStatusTextService.formatRemainingTime(Long.MAX_VALUE, null);

        assertFalse(clock.startsWith("-"));
        assertFalse(duration.contains("-"));
    }
}
