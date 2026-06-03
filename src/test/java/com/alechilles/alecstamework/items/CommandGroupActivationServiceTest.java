package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGroupActivationServiceTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red", "#AA5500", 1)
    );

    private final CommandGroupActivationService activationService =
            new CommandGroupActivationService(null, null);

    @Test
    void groupSelectionActivatesMembersAndDeactivatesOthers() {
        UUID blueNpc = UUID.randomUUID();
        UUID redNpc = UUID.randomUUID();
        UUID ungroupedNpc = UUID.randomUUID();
        List<LinkedNpcRecord> records = records(blueNpc, redNpc, ungroupedNpc);

        List<LinkedNpcRecord> updated = activationService.applySelection(records, GROUPS, "blue");

        assertTrue(find(updated, blueNpc).active);
        assertFalse(find(updated, redNpc).active);
        assertFalse(find(updated, ungroupedNpc).active);
        assertEquals("blue", activationService.resolveSelectionValue(updated, GROUPS));
    }

    @Test
    void allAndNoneSelectionsApplyToEveryRecord() {
        UUID blueNpc = UUID.randomUUID();
        UUID redNpc = UUID.randomUUID();
        UUID ungroupedNpc = UUID.randomUUID();
        List<LinkedNpcRecord> records = records(blueNpc, redNpc, ungroupedNpc);

        List<LinkedNpcRecord> none = activationService.applySelection(
                records,
                GROUPS,
                CommandGroupActivationService.NONE_VALUE
        );
        assertFalse(find(none, blueNpc).active);
        assertFalse(find(none, redNpc).active);
        assertFalse(find(none, ungroupedNpc).active);
        assertEquals(CommandGroupActivationService.NONE_VALUE, activationService.resolveSelectionValue(none, GROUPS));

        List<LinkedNpcRecord> all = activationService.applySelection(
                none,
                GROUPS,
                CommandGroupActivationService.ALL_VALUE
        );
        assertTrue(find(all, blueNpc).active);
        assertTrue(find(all, redNpc).active);
        assertTrue(find(all, ungroupedNpc).active);
        assertEquals(CommandGroupActivationService.ALL_VALUE, activationService.resolveSelectionValue(all, GROUPS));
    }

    private List<LinkedNpcRecord> records(UUID blueNpc, UUID redNpc, UUID ungroupedNpc) {
        return List.of(
                record(blueNpc, "blue", false),
                record(redNpc, "red", true),
                record(ungroupedNpc, null, true)
        );
    }

    private LinkedNpcRecord record(UUID npcUuid, String groupId, boolean active) {
        return new LinkedNpcRecord(
                npcUuid,
                null,
                null,
                npcUuid.toString(),
                null,
                "test_role",
                null,
                active,
                false,
                groupId
        );
    }

    private LinkedNpcRecord find(List<LinkedNpcRecord> records, UUID npcUuid) {
        for (LinkedNpcRecord record : records) {
            if (record != null && npcUuid.equals(record.npcUuid)) {
                return record;
            }
        }
        throw new AssertionError("Missing record " + npcUuid);
    }
}
