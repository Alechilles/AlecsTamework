package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcLostServiceTest {
    @Test
    void recordsOnlyProcessLocalRelocationDropDetail() {
        CommandLinkedNpcLostService service =
                new CommandLinkedNpcLostService();
        UUID npcUuid = UUID.randomUUID();

        assertTrue(service.recordLostFromRelocationDrop(
                npcUuid,
                UUID.randomUUID(),
                new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(4.0, 5.0, 6.0),
                null,
                100L,
                200L,
                3
        ));

        CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot =
                service.getLostSnapshot(npcUuid);
        assertNotNull(snapshot);
        assertEquals(100L, snapshot.lastRelocationQueuedAtMs());
        assertEquals(200L, snapshot.lostAtMs());
        assertEquals(3, snapshot.relocationRetryAttempts());
        assertNull(snapshot.replacementNpcUuid());
        assertEquals(1.0, snapshot.lastKnownPosition().x, 0.0001);
        assertEquals(4.0, snapshot.homePosition().x, 0.0001);
        snapshot.lastKnownPosition().x = 99.0;
        assertEquals(
                1.0,
                service.getLostSnapshot(npcUuid).lastKnownPosition().x,
                0.0001
        );

        service.clearLostSnapshot(npcUuid);
        assertFalse(service.isLost(npcUuid));
    }

    @Test
    void repeatedDropKeepsFirstLostTimeAndHighestRetryCount() {
        CommandLinkedNpcLostService service =
                new CommandLinkedNpcLostService();
        UUID npcUuid = UUID.randomUUID();
        service.recordLostFromRelocationDrop(
                npcUuid, null, null, null, null, 10L, 20L, 7
        );

        service.recordLostFromRelocationDrop(
                npcUuid, null, null, null, null, 30L, 40L, 2
        );

        CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot =
                service.getLostSnapshot(npcUuid);
        assertEquals(20L, snapshot.lostAtMs());
        assertEquals(7, snapshot.relocationRetryAttempts());
        assertEquals(30L, snapshot.lastRelocationQueuedAtMs());
    }

    @Test
    void capturedCompanionIsNotDuplicatedAsLost() {
        CommandLinkedNpcCaptureService captures =
                new CommandLinkedNpcCaptureService();
        UUID npcUuid = UUID.randomUUID();
        captures.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        UUID.randomUUID(),
                        new String[]{"tool-a"},
                        "role",
                        "Captured",
                        null,
                        null,
                        100L
                )
        );
        CommandLinkedNpcLostService service =
                new CommandLinkedNpcLostService(null, captures, null);

        assertFalse(service.recordLostFromRelocationDrop(
                npcUuid, null, null, null, null, 10L, 20L, 1
        ));
        assertNull(service.getLostSnapshot(npcUuid));
    }

    @Test
    void coopedCompanionIsNotDuplicatedAsLost() {
        CommandLinkedNpcCoopService coops =
                new CommandLinkedNpcCoopService();
        UUID npcUuid = UUID.randomUUID();
        coops.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        UUID.randomUUID(),
                        new String[]{"tool-a"},
                        "role",
                        "Cooped",
                        "coop/chicken",
                        0,
                        100L
                )
        );
        CommandLinkedNpcLostService service =
                new CommandLinkedNpcLostService(null, null, coops);

        assertFalse(service.recordLostFromRelocationDrop(
                npcUuid, null, null, null, null, 10L, 20L, 1
        ));
        assertNull(service.getLostSnapshot(npcUuid));
    }
}
