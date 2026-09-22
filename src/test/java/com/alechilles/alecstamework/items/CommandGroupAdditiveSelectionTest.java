package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandGroupAdditiveSelectionTest {
    /** Reusing menu entries must preserve None, All, named and partial selections. */
    @Test void resolvesGroupSelectionFromSelectableSnapshotRows() {
        var selected = entry(true, true, "barn", "travel");
        var unselected = entry(false, true, "travel");
        var unsupported = entry(true, false, "barn");
        var groups = List.of(new CommandGroupService.GroupRecord("barn", "Barn", "#445566", 0),
                new CommandGroupService.GroupRecord("travel", "Travel", "#445566", 1));
        assertEquals("barn", CommandGroupAssignPageService.resolveGroupActivationValue(
                List.of(selected, unselected, unsupported), groups));
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                CommandGroupAssignPageService.resolveGroupActivationValue(List.of(selected, unsupported), groups));
        assertEquals(CommandGroupActivationService.NONE_VALUE,
                CommandGroupAssignPageService.resolveGroupActivationValue(List.of(unselected, unsupported), groups));
        assertEquals(CommandGroupActivationService.CUSTOM_VALUE,
                CommandGroupAssignPageService.resolveGroupActivationValue(
                        List.of(selected, unselected, entry(false, true, "barn")), groups));
    }

    @Test void addingGroupPreservesIndividualSelectionsAndPrioritizesThemWithoutDuplicates() {
        var individual = entry(true, true);
        var overlap = entry(true, true, "barn", "travel");
        var addition = entry(false, true, "travel");
        var others = entry(false, true, "barn");
        var entries = List.of(addition, others, individual, overlap);
        var added = CommandGroupAssignPageService.selectionCandidates(entries, "travel", true, this::record);
        assertEquals(List.of(individual.npcUuid(), overlap.npcUuid(), addition.npcUuid()),
                added.stream().map(r -> r.npcUuid).toList());
        var replaced = CommandGroupAssignPageService.selectionCandidates(entries, "travel", false, this::record);
        assertEquals(List.of(addition.npcUuid(), overlap.npcUuid()), replaced.stream().map(r -> r.npcUuid).toList());
        assertTrue(CommandGroupAssignPageService.selectionCandidates(entries, "__none__", true, this::record).isEmpty());
    }

    @Test void addingFromEmptySelectionRejectsUnsupportedAndNoLongerOwnedAnimals() {
        var eligible = entry(false, true, "barn");
        var unsupported = entry(false, false, "barn");
        var stale = entry(false, true, "barn");
        var result = CommandGroupAssignPageService.selectionCandidates(List.of(eligible, unsupported, stale),
                "barn", true, id -> id.equals(stale.npcUuid()) ? null : record(id));
        assertEquals(List.of(eligible.npcUuid()), result.stream().map(r -> r.npcUuid).toList());
    }

    @Test void matchingSelectionIncludesEveryPageAndRevalidatesBeforeApplyingCapacity() {
        var hidden = entry(true, true, "travel");
        var stale = entry(false, true, "barn");
        var unsupported = entry(false, false, "barn");
        var first = entry(false, true, "barn");
        var second = entry(false, true, "barn");
        var last = entry(false, true, "barn");
        var entries = List.of(hidden, stale, unsupported, first, second, last);
        var settings = new com.alechilles.alecstamework.ui.CompanionViewSettings(
                "All", false, "", "Default", false, List.of("Sheep"), List.of("barn"));
        var previous = List.of(record(hidden.npcUuid()).withActive(true));
        java.util.function.Function<UUID, LinkedNpcRecord> authority =
                id -> id.equals(stale.npcUuid()) ? null : record(id);
        var result = CommandGroupAssignPageService.matchingSelection(previous, entries, settings, 0, authority);
        assertEquals(List.of(first.npcUuid(), second.npcUuid(), last.npcUuid()),
                result.stream().filter(r -> r.active).map(r -> r.npcUuid).toList());
        assertFalse(result.stream().filter(r -> r.npcUuid.equals(hidden.npcUuid())).findFirst().orElseThrow().active);
        assertEquals(previous, CommandGroupAssignPageService.matchingSelection(previous,
                List.of(stale, unsupported), settings, 2, authority),
                "Unavailable matches must not clear existing recipients.");
        var limited = CommandGroupAssignPageService.matchingSelection(previous, entries, settings, 2, authority);
        assertEquals(List.of(first.npcUuid(), second.npcUuid()),
                limited.stream().filter(r -> r.active).map(r -> r.npcUuid).toList());
    }

    @Test void duplicateAliasesConsumeOnlyOneMatchingSelectionSlot() {
        var oldAlias = entry(false, true);
        var newAlias = entry(false, true);
        var other = entry(false, true);
        var result = CommandGroupAssignPageService.matchingSelection(List.of(),
                List.of(oldAlias, newAlias, other),
                com.alechilles.alecstamework.ui.CompanionViewSettings.defaults().withState("All"), 2,
                id -> new LinkedNpcRecord(id, id.equals(other.npcUuid()) ? "other" : "shared",
                        null, null, null, "Sheep", null, "Sheep", null, false, false, null));
        assertEquals(List.of(newAlias.npcUuid(), other.npcUuid()),
                result.stream().filter(r -> r.active).map(r -> r.npcUuid).toList());
    }

    private LinkedNpcRecord record(UUID id) {
        return new LinkedNpcRecord(id, null, null, "Sheep", null, "Sheep");
    }

    private LinkedNpcEntry entry(boolean active, boolean supported, String... groups) {
        UUID id = UUID.randomUUID();
        return new LinkedNpcEntry(id, "Sheep", 10, 10, 0, 0, 0, null,
                0, 0, 0, 0, true, false, false, false, false, false, 0L, null, null, null,
                new LinkedNpcTraitIndicator[0], false, false, false, false, true, active,
                "Sheep", "Sheep", null, null, null, false, false, 0L, 0.0, false)
                .withCompanionGroups(id.toString(), java.util.Arrays.stream(groups)
                        .map(g -> new LinkedNpcEntry.GroupMembership(g, g, "#445566")).toList(), supported);
    }
}
