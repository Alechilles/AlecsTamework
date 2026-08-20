package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandActiveNpcHighlightPlanServiceTest {
    private final CommandActiveNpcHighlightPlanService service =
            new CommandActiveNpcHighlightPlanService();

    @Test
    void activeRecordsUseTheirGroupColorAndInactiveRecordsDoNotEmit() {
        UUID blueNpc = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID inactiveNpc = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID ungroupedNpc = UUID.fromString("00000000-0000-0000-0000-000000000003");
        List<LinkedNpcRecord> records = List.of(
                record(blueNpc, "blue", true),
                record(inactiveNpc, "red", false),
                record(ungroupedNpc, null, true)
        );
        List<CommandGroupService.GroupRecord> groups = List.of(
                new CommandGroupService.GroupRecord("blue", "Blue", "#112233", 0),
                new CommandGroupService.GroupRecord("red", "Red", "#AA5500", 1)
        );

        List<CommandActiveNpcHighlightPlanService.HighlightTarget> targets =
                service.build(records, groups);

        assertEquals(List.of(
                new CommandActiveNpcHighlightPlanService.HighlightTarget(
                        blueNpc, "profile-" + blueNpc, "#112233"),
                new CommandActiveNpcHighlightPlanService.HighlightTarget(
                        ungroupedNpc, "profile-" + ungroupedNpc, "#C9A653")
        ), targets);
    }

    private LinkedNpcRecord record(UUID npcUuid, String groupId, boolean active) {
        return new LinkedNpcRecord(
                npcUuid, "profile-" + npcUuid, null, null, null,
                npcUuid.toString(), null, "test_role", null,
                active, false, groupId
        );
    }
}
