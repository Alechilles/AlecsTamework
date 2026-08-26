package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandTargetHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandTargetHudSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies focused update hints for detached target snapshots. */
class CommandTargetHudSnapshotDifferTest {
    private static final UUID TARGET = UUID.fromString(
            "b8b6a5d2-bab4-4a87-a7d3-88b9cfed2e6d");

    @Test
    void reportsOnlySectionsChangedByAValueUpdate() {
        CommandTargetHudSnapshot previous = snapshot(40, "Owner A");
        CommandTargetHudSnapshot current = snapshot(41, "Owner B");

        CommandTargetHudChangeSet changes = CommandTargetHudSnapshotDiffer.diff(previous, current);

        assertFalse(changes.fullRefresh());
        assertEquals(Set.of(
                CommandTargetHudChangeSet.Section.VITALS,
                CommandTargetHudChangeSet.Section.OWNER), changes.changedSections());
    }

    @Test
    void reportsHappinessModifierDetailAsAVitalsChange() {
        CommandTargetHudSnapshot previous = snapshot(40, "Owner A", "old modifiers");
        CommandTargetHudSnapshot current = snapshot(40, "Owner A", "new modifiers");

        CommandTargetHudChangeSet changes = CommandTargetHudSnapshotDiffer.diff(previous, current);

        assertEquals(Set.of(CommandTargetHudChangeSet.Section.VITALS),
                changes.changedSections());
    }

    @Test
    void reportsNoSectionsWhenSnapshotsAreEqual() {
        CommandTargetHudSnapshot snapshot = snapshot(40, "Owner A");

        CommandTargetHudChangeSet changes = CommandTargetHudSnapshotDiffer.diff(snapshot, snapshot);

        assertTrue(changes.changedSections().isEmpty());
        assertFalse(changes.fullRefresh());
    }

    @Test
    void requestsFullRefreshWhenThereIsNoPreviousTarget() {
        CommandTargetHudChangeSet changes = CommandTargetHudSnapshotDiffer.diff(
                null, snapshot(40, "Owner A"));

        assertTrue(changes.fullRefresh());
        assertTrue(changes.changed(CommandTargetHudChangeSet.Section.IDENTITY));
    }

    private static CommandTargetHudSnapshot snapshot(int health, String owner) {
        return snapshot(health, owner, null);
    }

    private static CommandTargetHudSnapshot snapshot(
            int health, String owner, String happinessModifierBreakdown
    ) {
        return new CommandTargetHudSnapshot(
                TARGET,
                "target-key",
                "Moss",
                "runeteria:stag",
                "Stag",
                "Female",
                "loaded",
                new CommandTargetHudSnapshot.Vitals(
                        health, 100, 60, 100, 60, 40, 50, 30, 50),
                happinessModifierBreakdown,
                new CommandTargetHudSnapshot.Cooldowns(
                        new CommandTargetHudSnapshot.Cooldown(true, 100L, 0.2, true),
                        new CommandTargetHudSnapshot.Cooldown(false, 0L, 0.0, true)),
                null,
                List.of(),
                List.of(),
                null,
                new CommandTargetHudSnapshot.Progression(7, 120L, 300L, 3),
                List.of(),
                owner
        );
    }
}
