package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.List;
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
    void scopedRecoveryHoldOverridesLifecycleStatusAndUsesOnlyShortReference() {
        LinkedNpcEntry entry = new LinkedNpcEntry(
                UUID.randomUUID(), "Held Companion", 0, 0, 0, 0, null,
                0, 0, 0, 0, false, false, true, false, false, true,
                0L, LinkedNpcTraitIndicator.EMPTY
        ).withRecoveryHold("12345678-raw-tail-must-not-render");

        assertEquals("12345678", entry.recoveryIncidentId());
        assertEquals(
                LocalizedText.format((String) null,
                        "tamework.ui.linkedPanel.status.recoveryHeldReference", "12345678"),
                LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry)
        );

        LinkedNpcEntry[] snapshots = LinkedNpcEntrySnapshotMapper.build(java.util.List.of(entry));
        assertEquals(1, snapshots.length);
        assertEquals("12345678", snapshots[0].recoveryIncidentId());
    }

    @Test
    void snapshotMapperPreservesDisabledRespawnSentinel() {
        LinkedNpcEntry entry = new LinkedNpcEntry(
                UUID.randomUUID(), "Dead Dragon", 0, 0, 0, 0, null,
                0, 0, 0, 0, false, false, true, false, false, false,
                -1L, LinkedNpcTraitIndicator.EMPTY
        );

        LinkedNpcEntry[] snapshots = LinkedNpcEntrySnapshotMapper.build(List.of(entry));

        assertEquals(1, snapshots.length);
        assertEquals(-1L, snapshots[0].deadRespawnRemainingMs());
        assertEquals(
                LocalizedText.resolve((String) null,
                        "tamework.ui.linkedPanel.health.deadRespawnDisabled"),
                LinkedNpcPanelStatusTextService.resolveDeadHealthText(snapshots[0])
        );
    }

    @Test
    void snapshotMapperPreservesNamedCompanionRoleSubtitleAcrossRefresh() {
        LinkedNpcEntry entry = new LinkedNpcEntry(
                UUID.randomUUID(), "Daffodil", 25, 25, 0, 0, null,
                0, 0, 0, 0, true, false, false, false, false, false,
                0L, LinkedNpcTraitIndicator.EMPTY
        ).withRoleSubtitle("Duck");

        LinkedNpcEntry[] snapshots = LinkedNpcEntrySnapshotMapper.build(List.of(entry));

        assertEquals(1, snapshots.length);
        assertEquals("Duck", snapshots[0].roleSubtitle());
    }

    @Test
    void saturatedCooldownFormattingNeverWrapsNegative() {
        String clock = LinkedNpcPanelStatusTextService.formatRemainingClock(Long.MAX_VALUE);
        String duration = LinkedNpcPanelStatusTextService.formatRemainingTime(Long.MAX_VALUE, null);

        assertFalse(clock.startsWith("-"));
        assertFalse(duration.contains("-"));
    }

    @Test
    void countdownUsesRoundedUpMinutesUntilUnderOneMinute() {
        assertEquals("2m", LinkedNpcPanelStatusTextService.formatRemainingTime(60_001L, "en-US"));
        assertEquals("1m", LinkedNpcPanelStatusTextService.formatRemainingTime(60_000L, "en-US"));
        assertEquals("60s", LinkedNpcPanelStatusTextService.formatRemainingTime(59_999L, "en-US"));
        assertEquals("45s", LinkedNpcPanelStatusTextService.formatRemainingTime(45_000L, "en-US"));
        assertEquals("0s", LinkedNpcPanelStatusTextService.formatRemainingTime(0L, "en-US"));
    }
}
