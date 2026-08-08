package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandGroupCycleServiceTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red", "#AA5500", 1)
    );

    private final CommandGroupCycleService cycleService =
            new CommandGroupCycleService(null, null, null);

    @Test
    void cycleMovesFromAllThroughDisplayOrderedNamedGroupsAndBackToAll() {
        assertEquals("blue", cycleService.nextSelectorValue(records(true, true, true), GROUPS));
        assertEquals("red", cycleService.nextSelectorValue(records(true, false, false), GROUPS));
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(records(false, true, false), GROUPS));
    }

    @Test
    void customAndNoneSelectionsCycleToAllCompanions() {
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(records(true, true, false), GROUPS));
        assertEquals(CommandGroupActivationService.ALL_VALUE,
                cycleService.nextSelectorValue(records(false, false, false), GROUPS));
    }

    private List<LinkedNpcRecord> records(boolean blueActive,
                                           boolean redActive,
                                           boolean ungroupedActive) {
        return List.of(
                record("blue", blueActive),
                record("red", redActive),
                record(null, ungroupedActive)
        );
    }

    private LinkedNpcRecord record(String groupId, boolean active) {
        UUID uuid = UUID.randomUUID();
        return new LinkedNpcRecord(
                uuid, null, null, uuid.toString(), null, "test_role", null,
                active, false, groupId
        );
    }
}
