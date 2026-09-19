package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import com.alechilles.alecstamework.ui.LinkedNpcTraitIndicator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHotswapHudGroupStatusResolverTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue Squad", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red Squad", "#AA5500", 1)
    );

    private final CommandHotswapHudGroupStatusResolver resolver =
            new CommandHotswapHudGroupStatusResolver();

    @Test
    void namedGroupUsesItsNameAndConfiguredColor() {
        var status = resolver.resolve(entries(true, false, false), GROUPS);

        assertEquals("Blue Squad", status.label());
        assertEquals("#112233", status.colorHex());
    }

    @Test
    void customAndNoActiveUseDedicatedLabelsAndColors() {
        var custom = resolver.resolve(entries(true, true, false), GROUPS);
        var none = resolver.resolve(entries(false, false, false), GROUPS);

        assertEquals("Custom Selection", custom.label());
        assertEquals("#c9a653", custom.colorHex());
        assertEquals("No Active Companions", none.label());
        assertEquals("#6e7c8b", none.colorHex());
    }

    @Test
    void partialMultiMemberGroupRemainsCustom() {
        var status = resolver.resolve(List.of(entry("blue", true), entry("blue", false)), GROUPS);
        assertEquals("Custom Selection", status.label());
    }

    @Test
    void selectedKeysUseSharedMembershipWithoutConstructingPanelEntries() {
        var status = resolver.resolveSelectedKeys(
                Set.of("pblue-1", "pblue-2"),
                GROUPS,
                groupId -> "blue".equals(groupId)
                        ? Set.of("pblue-1", "pblue-2") : Set.of());

        assertEquals("Blue Squad", status.label());
        assertEquals("#112233", status.colorHex());
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
