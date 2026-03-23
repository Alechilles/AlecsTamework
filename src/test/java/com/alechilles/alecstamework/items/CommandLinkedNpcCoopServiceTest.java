package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandLinkedNpcCoopServiceTest {

    @Test
    void fallsBackToOwnerMatchWhenToolIdDoesNotMatch() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCoopSnapshotForTool(npcUuid, "tool-beta", ownerUuid));
        assertNotNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-beta", ownerUuid));
    }

    @Test
    void ownerFallbackStillRejectsMismatchedOwner() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-beta", UUID.randomUUID()));
    }

    @Test
    void toolMatchStillWorks() {
        CommandLinkedNpcCoopService service = new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        null,
                        "Cooped Companion",
                        "coop/chicken_oak",
                        System.currentTimeMillis()
                )
        );

        assertNotNull(service.getCoopSnapshotForToolOrOwner(npcUuid, "tool-alpha", ownerUuid));
    }
}
