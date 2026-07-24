package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandLinkedNpcCaptureServiceTest {
    @Test
    void ownerFallbackAndToolFilterRemainReleasedBehavior() {
        CommandLinkedNpcCaptureService service =
                new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        service.recordCapturedSnapshot(snapshot(
                npcUuid, ownerUuid, new String[]{"tool-alpha"}
        ));

        assertNull(service.getCapturedSnapshotForTool(
                npcUuid, "tool-beta", ownerUuid
        ));
        assertNotNull(service.getCapturedSnapshotForToolOrOwner(
                npcUuid, "tool-beta", ownerUuid
        ));
        assertNull(service.getCapturedSnapshotForToolOrOwner(
                npcUuid, "tool-beta", UUID.randomUUID()
        ));
    }

    @Test
    void recordSanitizesAndDefensivelyCopiesDetail() {
        CommandLinkedNpcCaptureService service =
                new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        String[] toolIds = {"tool-alpha", "", "tool-alpha"};
        Vector3d position = new Vector3d(1.0, 2.0, 3.0);
        service.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        UUID.randomUUID(),
                        toolIds,
                        "role",
                        "Captured",
                        position,
                        null,
                        100L
                )
        );
        toolIds[0] = "mutated";
        position.x = 99.0;

        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot stored =
                service.getCapturedSnapshot(npcUuid);
        assertArrayEquals(new String[]{"tool-alpha"}, stored.toolIds());
        assertNotNull(stored.lastKnownPosition());
        org.junit.jupiter.api.Assertions.assertEquals(
                1.0,
                stored.lastKnownPosition().x,
                0.0001
        );
        stored.lastKnownPosition().x = 77.0;
        assertArrayEquals(
                new String[]{"tool-alpha"},
                service.getCapturedSnapshot(npcUuid).toolIds()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                1.0,
                service.getCapturedSnapshot(npcUuid)
                        .lastKnownPosition().x,
                0.0001
        );
    }

    @Test
    void snapshotWithoutToolEvidenceIsNotCached() {
        CommandLinkedNpcCaptureService service =
                new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();

        service.recordCapturedSnapshot(snapshot(
                npcUuid, UUID.randomUUID(), new String[0]
        ));

        assertNull(service.getCapturedSnapshot(npcUuid));
    }

    private CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot(
            UUID npcUuid,
            UUID ownerUuid,
            String[] toolIds
    ) {
        return new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                npcUuid,
                ownerUuid,
                toolIds,
                "role",
                "Captured",
                null,
                null,
                100L
        );
    }
}
