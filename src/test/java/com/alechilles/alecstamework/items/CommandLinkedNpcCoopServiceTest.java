package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandLinkedNpcCoopServiceTest {
    @Test
    void filtersProcessLocalDetailByOwnerAndTool() {
        CommandLinkedNpcCoopService service =
                new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        service.recordCoopSnapshot(snapshot(
                npcUuid, ownerUuid, new String[]{"tool-alpha"}
        ));

        assertNotNull(service.getCoopSnapshotForTool(
                npcUuid, "tool-alpha", ownerUuid
        ));
        assertNull(service.getCoopSnapshotForTool(
                npcUuid, "tool-beta", ownerUuid
        ));
        assertNull(service.getCoopSnapshotForOwner(
                npcUuid, UUID.randomUUID()
        ));
    }

    @Test
    void recordAndAccessorDefensivelyCopyToolIds() {
        CommandLinkedNpcCoopService service =
                new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        String[] toolIds = {"tool-alpha"};
        service.recordCoopSnapshot(snapshot(
                npcUuid, UUID.randomUUID(), toolIds
        ));
        toolIds[0] = "mutated-input";

        CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot stored =
                service.getCoopSnapshot(npcUuid);
        assertNotNull(stored);
        String[] returned = stored.toolIds();
        returned[0] = "mutated-output";

        assertArrayEquals(
                new String[]{"tool-alpha"},
                service.getCoopSnapshot(npcUuid).toolIds()
        );
    }

    @Test
    void clearOperationsOnlyAffectProcessLocalDetail() {
        CommandLinkedNpcCoopService service =
                new CommandLinkedNpcCoopService();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.recordCoopSnapshot(snapshot(
                first, UUID.randomUUID(), new String[]{"tool-a"}
        ));
        service.recordCoopSnapshot(snapshot(
                second, UUID.randomUUID(), new String[]{"tool-b"}
        ));

        service.clearCoopSnapshot(first);
        assertNull(service.getCoopSnapshot(first));
        assertNotNull(service.getCoopSnapshot(second));

        service.clearAll();
        assertNull(service.getCoopSnapshot(second));
    }

    private CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot snapshot(
            UUID npcUuid,
            UUID ownerUuid,
            String[] toolIds
    ) {
        return new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                npcUuid,
                ownerUuid,
                toolIds,
                "tamed_chicken",
                "Chicken",
                "coop/chicken",
                0,
                123L
        );
    }
}
