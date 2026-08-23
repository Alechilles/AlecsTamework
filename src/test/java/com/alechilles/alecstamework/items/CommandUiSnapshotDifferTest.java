package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiChangeSet;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiCompanionRow;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiSection;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation;
import com.alechilles.alecstamework.ui.BondedCompanionStatusPresentation;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import java.util.Map;
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
    void hotswapAndGroupChangesOnlyMarkTheirOwnSections() {
        UUID sessionId = UUID.randomUUID();
        CommandUiSnapshot previous = richSnapshot(sessionId,
                Map.of("Q", "alpha"), Map.of("Q", List.of(
                        new CommandUiCommandOption("alpha", "Alpha"))),
                Map.of("group-a", "Alpha"));
        CommandUiSnapshot hotswap = richSnapshot(sessionId,
                Map.of("Q", "beta"), Map.of("Q", List.of(
                        new CommandUiCommandOption("beta", "Beta"))),
                Map.of("group-a", "Alpha"));
        CommandUiChangeSet hotswapChanges = CommandUiSnapshotDiffer.diff(
                previous, hotswap);
        assertEquals(java.util.Set.of(CommandUiSection.HOTSWAPS),
                hotswapChanges.changedSections());

        CommandUiSnapshot groups = richSnapshot(sessionId,
                Map.of("Q", "alpha"), Map.of("Q", List.of(
                        new CommandUiCommandOption("alpha", "Alpha"))),
                Map.of("group-a", "Beta"));
        CommandUiChangeSet groupChanges = CommandUiSnapshotDiffer.diff(
                previous, groups);
        assertEquals(java.util.Set.of(CommandUiSection.GROUPS),
                groupChanges.changedSections());
    }

    @Test
    void bondedProfileKeepsPresentationRowIdWhenNpcUuidChanges() {
        CommandPanelFeaturePresentation feature = CommandPanelFeaturePresentation.bonded(
                new BondedCompanionPanelPresentation(
                        "profile-1", "roster-1", "role-1", 3L, "Nimbus",
                        "Miniwyvern", "Male", "Storm", Map.of(), Map.of(),
                        new BondedCompanionStatusPresentation(
                                BondedCompanionStateView.STORED,
                                BondedCompanionStatusPresentation.Action.SUMMON,
                                true, null, null, 0L), null));
        UUID firstNpc = UUID.randomUUID();
        UUID secondNpc = UUID.randomUUID();
        CommandUiCompanionRow first = CommandUiSnapshotAssembler.toRow(
                entry(firstNpc), feature, Map.of());
        CommandUiCompanionRow second = CommandUiSnapshotAssembler.toRow(
                entry(secondNpc), feature, Map.of());

        assertEquals(first.rowId(), second.rowId());
        assertEquals(firstNpc, first.companionUuid());
        assertEquals(secondNpc, second.companionUuid());
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

    private static CommandUiSnapshot richSnapshot(
            UUID sessionId,
            Map<String, String> assignments,
            Map<String, List<CommandUiCommandOption>> choices,
            Map<String, String> groups
    ) {
        return new CommandUiSnapshot(
                sessionId, 1L, 1L, (CommandUiProviderId) null,
                "tool", "item", "config", "generic", java.util.Set.of(),
                null, List.of(), List.of(), new CommandUiPanelState("linked"),
                Map.of(), Map.of(), assignments, choices, groups,
                0L, Map.of(), null, null);
    }

    private static LinkedNpcEntry entry(UUID npcUuid) {
        return new LinkedNpcEntry(
                npcUuid, "Nimbus", 10, 10, 5, 10, null,
                5, 10, 5, 10, false, false, false, false,
                false, false, 0L, null);
    }

    private static final class CommandUiSectionSet {
        private static int size() {
            return com.alechilles.alecstamework.api.commandui.CommandUiSection
                    .all().size();
        }
    }
}
