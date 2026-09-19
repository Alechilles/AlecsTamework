package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandGroupAdditiveSelectionTest {
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
