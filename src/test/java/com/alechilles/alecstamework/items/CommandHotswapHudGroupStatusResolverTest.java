package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHotswapHudGroupStatusResolverTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue Squad", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red Squad", "#AA5500", 1)
    );

    private final CommandHotswapHudGroupStatusResolver resolver =
            new CommandHotswapHudGroupStatusResolver(null, null, null);

    @Test
    void namedGroupUsesItsNameAndConfiguredColor() {
        var status = resolver.resolve(records(true, false, false), GROUPS);

        assertEquals("Blue Squad", status.label());
        assertEquals("#112233", status.colorHex());
    }

    @Test
    void customAndNoActiveUseDedicatedLabelsAndColors() {
        var custom = resolver.resolve(records(true, true, false), GROUPS);
        var none = resolver.resolve(records(false, false, false), GROUPS);

        assertEquals("Custom Selection", custom.label());
        assertEquals("#c9a653", custom.colorHex());
        assertEquals("No Active Companions", none.label());
        assertEquals("#6e7c8b", none.colorHex());
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
