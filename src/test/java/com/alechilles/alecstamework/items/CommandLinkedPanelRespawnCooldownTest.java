package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies linked-panel cooldown projection without wall-clock timing. */
class CommandLinkedPanelRespawnCooldownTest {
    @Test
    void reportsRemainingCooldownFromCanonicalSnapshotDeadline() {
        assertEquals(
                600_000L,
                CommandLinkedPanelEntryService.remainingUntil(
                        1_600_000L, 1_000_000L
                )
        );
    }

    @Test
    void treatsUnsetAndElapsedDeadlinesAsReady() {
        assertEquals(
                0L,
                CommandLinkedPanelEntryService.remainingUntil(0L, -100L)
        );
        assertEquals(
                0L,
                CommandLinkedPanelEntryService.remainingUntil(-100L, -50L)
        );
    }

    @Test
    void preservesSignedWorldTimeAndSaturatesOverflow() {
        assertEquals(
                50L,
                CommandLinkedPanelEntryService.remainingUntil(-50L, -100L)
        );
        assertEquals(
                Long.MAX_VALUE,
                CommandLinkedPanelEntryService.remainingUntil(
                        Long.MAX_VALUE, Long.MIN_VALUE
                )
        );
    }
}
