package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandGroupCycleServiceTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red", "#AA5500", 1)
    );

    private final CommandGroupCycleService cycleService = new CommandGroupCycleService();

    @Test
    void cycleMovesFromAllThroughDisplayOrderedNamedGroupsAndBackToAll() {
        assertEquals("blue", cycleService.nextSelectorValue(entries(true, true, true), GROUPS));
        assertEquals("red", cycleService.nextSelectorValue(entries(true, false, false), GROUPS));
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(entries(false, true, false), GROUPS));
    }

    @Test
    void customAndNoneSelectionsCycleToAllCompanions() {
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(entries(true, true, false), GROUPS));
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(entries(false, false, false), GROUPS));
    }

    private List<LinkedNpcEntry> entries(boolean blueActive,
                                         boolean redActive,
                                         boolean ungroupedActive) {
        return List.of(
                entry("blue", blueActive),
                entry("red", redActive),
                entry(null, ungroupedActive)
        );
    }

    private LinkedNpcEntry entry(String groupId, boolean active) {
        UUID uuid = UUID.randomUUID();
        LinkedNpcEntry entry = new LinkedNpcEntry(uuid, "Companion", 1, 1, 0, 0, 0,
                "", 0, 0, 0, 0, true, false, false, false, false, false,
                0L, null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, active,
                "test_role", "Test", null, null, null, false, false, 0L, 0.0, false);
        return entry.withCompanionGroups("e" + uuid,
                groupId == null ? List.of() : List.of(new LinkedNpcEntry.GroupMembership(groupId, groupId, "#112233")),
                true);
    }
}
