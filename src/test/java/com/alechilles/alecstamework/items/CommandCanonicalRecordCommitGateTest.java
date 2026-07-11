package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for commit-before-relocation ordering and failure gating. */
class CommandCanonicalRecordCommitGateTest {
    private final CommandCanonicalRecordCommitGate gate =
            new CommandCanonicalRecordCommitGate();

    @Test
    void successfulCanonicalWriteOccursBeforeActionIsAllowed() {
        List<String> events = new ArrayList<>();

        boolean committed = gate.commitBeforeAction(true, () -> {
            events.add("commit");
            return true;
        });
        if (committed) {
            events.add("queue");
        }

        assertTrue(committed);
        assertEquals(List.of("commit", "queue"), events);
    }

    @Test
    void failedCanonicalWritePreventsTheFollowingAction() {
        List<String> events = new ArrayList<>();

        boolean committed = gate.commitBeforeAction(true, () -> {
            events.add("commit_failed");
            return false;
        });
        if (committed) {
            events.add("queue");
        }

        assertFalse(committed);
        assertEquals(List.of("commit_failed"), events);
    }

    @Test
    void unchangedIdentityNeedsNoInventoryWrite() {
        boolean committed = gate.commitBeforeAction(false, () -> {
            throw new AssertionError("unchanged identity must not write");
        });

        assertTrue(committed);
    }
}
