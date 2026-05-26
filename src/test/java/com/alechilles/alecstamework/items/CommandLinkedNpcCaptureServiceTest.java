package com.alechilles.alecstamework.items;

import org.joml.Vector3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandLinkedNpcCaptureServiceTest {

    @Test
    void fallsBackToOwnerMatchWhenToolIdDoesNotMatch() {
        CommandLinkedNpcCaptureService service = new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Test Companion",
                        new Vector3d(1.0, 2.0, 3.0),
                        new Vector3d(4.0, 5.0, 6.0),
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCapturedSnapshotForTool(npcUuid, "tool-beta", ownerUuid));
        assertNotNull(service.getCapturedSnapshotForToolOrOwner(npcUuid, "tool-beta", ownerUuid));
    }

    @Test
    void ownerFallbackStillRejectsMismatchedOwner() {
        CommandLinkedNpcCaptureService service = new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Test Companion",
                        null,
                        null,
                        System.currentTimeMillis()
                )
        );

        assertNull(service.getCapturedSnapshotForToolOrOwner(npcUuid, "tool-beta", UUID.randomUUID()));
    }

    @Test
    void toolMatchStillWorks() {
        CommandLinkedNpcCaptureService service = new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        service.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        null,
                        "Test Companion",
                        null,
                        null,
                        System.currentTimeMillis()
                )
        );

        assertNotNull(service.getCapturedSnapshotForToolOrOwner(npcUuid, "tool-alpha", ownerUuid));
    }
}
