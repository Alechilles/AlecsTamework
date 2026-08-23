package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSection;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for detached snapshots and row-level change hints. */
class CommandUiSnapshotDifferTest {
    @Test
    void oneChangedRowDoesNotMarkAnUnchangedRow() {
        UUID sessionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CommandUiSnapshot previous = snapshot(sessionId,
                new CommandUiCompanionRow(first, "one"),
                new CommandUiCompanionRow(second, "two"));
        CommandUiSnapshot current = snapshot(sessionId,
                new CommandUiCompanionRow(first, "one changed"),
                new CommandUiCompanionRow(second, "two"));

        CommandUiChangeSet changes = CommandUiSnapshotDiffer.diff(previous, current);

        assertEquals(java.util.Set.of(first), changes.changedCompanionIds());
        assertTrue(changes.changedSections().contains(CommandUiSection.COMPANIONS));
        assertTrue(changes.removedCompanionIds().isEmpty());
        assertTrue(!changes.changedCompanionIds().contains(second));
    }

    @Test
    void fullHintMarksEverySectionAndSnapshotCollectionsAreDetached() {
        UUID sessionId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        CommandUiSnapshot snapshot = snapshot(sessionId,
                new CommandUiCompanionRow(rowId, "companion"));

        assertEquals(CommandUiSectionSet.size(),
                CommandUiChangeSet.full().changedSections().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.companionRows().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.enabledCapabilities().clear());
    }

    private static CommandUiSnapshot snapshot(
            UUID sessionId,
            CommandUiCompanionRow... rows
    ) {
        return new CommandUiSnapshot(sessionId, 1L, 1L, null, List.of(),
                List.of(rows), new CommandUiPanelState("linked"));
    }

    private static final class CommandUiSectionSet {
        private static int size() {
            return com.alechilles.alecstamework.api.commandui.CommandUiSection
                    .all().size();
        }
    }
}
