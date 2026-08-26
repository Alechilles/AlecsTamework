package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudChangeSet;
import com.alechilles.alecstamework.api.commandhud.CommandHotswapHudSnapshot;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies focused refresh hints for the five hotswap controls. */
class CommandHotswapHudSnapshotDifferTest {
    @Test
    void reportsOnlyTheChangedControl() {
        CommandHotswapHudSnapshot before = snapshot("old", "Blue");
        CommandHotswapHudSnapshot after = snapshot("new", "Blue");

        CommandHotswapHudChangeSet changes = CommandHotswapHudSnapshotDiffer.diff(before, after);

        assertEquals(Set.of(CommandHotswapHudChangeSet.Slot.Q), changes.changedSlots());
        assertFalse(changes.groupStatusChanged());
        assertFalse(changes.fullRefresh());
    }

    @Test
    void reportsGroupStatusIndependentlyFromControlChanges() {
        CommandHotswapHudChangeSet changes = CommandHotswapHudSnapshotDiffer.diff(
                snapshot("same", "Blue"), snapshot("same", "Red"));

        assertTrue(changes.changedSlots().isEmpty());
        assertTrue(changes.groupStatusChanged());
    }

    @Test
    void missingPreviousSnapshotRequestsEveryControl() {
        CommandHotswapHudChangeSet changes = CommandHotswapHudSnapshotDiffer.diff(
                null, snapshot("new", "Blue"));

        assertTrue(changes.fullRefresh());
        assertEquals(Set.of(
                CommandHotswapHudChangeSet.Slot.PRIMARY,
                CommandHotswapHudChangeSet.Slot.SECONDARY,
                CommandHotswapHudChangeSet.Slot.Q,
                CommandHotswapHudChangeSet.Slot.E,
                CommandHotswapHudChangeSet.Slot.R), changes.changedSlots());
        assertTrue(changes.groupStatusChanged());
    }

    private static CommandHotswapHudSnapshot snapshot(String qGlyph, String group) {
        return new CommandHotswapHudSnapshot(
                slot("LMB", "P"), slot("RMB", "S"), slot("Q", qGlyph),
                slot("E", "E"), slot("R", "R"),
                new CommandHotswapHudSnapshot.GroupStatus(true, group, "#112233"));
    }

    private static CommandHotswapHudSnapshot.Slot slot(String binding, String glyph) {
        return new CommandHotswapHudSnapshot.Slot(true, binding, "", glyph);
    }
}
