package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcLostServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsLostSnapshotAndPersistsIt() {
        Path persistencePath = tempDir.resolve("CommandLinkedNpcLost.dat");
        CommandLinkedNpcLostService service = new CommandLinkedNpcLostService(persistencePath);
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        Vector3d lastKnown = new Vector3d(10.0, 20.0, 30.0);
        Vector3d home = new Vector3d(40.0, 50.0, 60.0);
        Vector3d destination = new Vector3d(70.0, 80.0, 90.0);

        service.recordLostFromRelocationDrop(
                npcUuid,
                ownerUuid,
                lastKnown,
                home,
                destination,
                1234L,
                5678L,
                9
        );

        assertTrue(service.isLost(npcUuid));
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot = service.getLostSnapshot(npcUuid);
        assertNotNull(snapshot);
        assertEquals(npcUuid, snapshot.npcUuid());
        assertEquals(1234L, snapshot.lastRelocationQueuedAtMs());
        assertEquals(5678L, snapshot.lostAtMs());
        assertEquals(9, snapshot.relocationRetryAttempts());
        assertNull(snapshot.replacementNpcUuid());
        assertVector(snapshot.lastKnownPosition(), 10.0, 20.0, 30.0);
        assertVector(snapshot.homePosition(), 40.0, 50.0, 60.0);

        CommandLinkedNpcLostService reloaded = new CommandLinkedNpcLostService(persistencePath);
        assertTrue(reloaded.isLost(npcUuid));
        CommandLinkedNpcLostService.LostLinkedNpcSnapshot reloadedSnapshot = reloaded.getLostSnapshot(npcUuid);
        assertNotNull(reloadedSnapshot);
        assertEquals(1234L, reloadedSnapshot.lastRelocationQueuedAtMs());
        assertEquals(5678L, reloadedSnapshot.lostAtMs());
        assertEquals(9, reloadedSnapshot.relocationRetryAttempts());
        assertVector(reloadedSnapshot.lastKnownPosition(), 10.0, 20.0, 30.0);
        assertVector(reloadedSnapshot.homePosition(), 40.0, 50.0, 60.0);
    }

    @Test
    void keepsStrictReplacementMappingAfterRecovery() {
        Path persistencePath = tempDir.resolve("CommandLinkedNpcLost.dat");
        CommandLinkedNpcLostService service = new CommandLinkedNpcLostService(persistencePath);
        UUID originalNpcUuid = UUID.randomUUID();
        UUID replacementNpcUuid = UUID.randomUUID();

        service.recordLostFromRelocationDrop(
                originalNpcUuid,
                UUID.randomUUID(),
                new Vector3d(1.0, 2.0, 3.0),
                new Vector3d(4.0, 5.0, 6.0),
                null,
                100L,
                200L,
                2
        );
        service.markRecovered(
                originalNpcUuid,
                replacementNpcUuid,
                new Vector3d(11.0, 12.0, 13.0),
                new Vector3d(14.0, 15.0, 16.0)
        );

        assertFalse(service.isLost(originalNpcUuid));
        assertNull(service.getLostSnapshot(originalNpcUuid));
        assertEquals(replacementNpcUuid, service.getReplacementUuid(originalNpcUuid));

        service.clearLostSnapshot(originalNpcUuid);
        assertEquals(replacementNpcUuid, service.getReplacementUuid(originalNpcUuid));

        CommandLinkedNpcLostService reloaded = new CommandLinkedNpcLostService(persistencePath);
        assertFalse(reloaded.isLost(originalNpcUuid));
        assertNull(reloaded.getLostSnapshot(originalNpcUuid));
        assertEquals(replacementNpcUuid, reloaded.getReplacementUuid(originalNpcUuid));
    }

    @Test
    void doesNotMarkCapturedCompanionAsLost() {
        Path persistencePath = tempDir.resolve("CommandLinkedNpcLost.dat");
        CommandLinkedNpcCaptureService captureService = new CommandLinkedNpcCaptureService();
        CommandLinkedNpcLostService service = new CommandLinkedNpcLostService(
                persistencePath,
                null,
                null,
                captureService
        );
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        captureService.recordCapturedSnapshot(
                new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Captured Companion",
                        new Vector3d(2.0, 3.0, 4.0),
                        new Vector3d(5.0, 6.0, 7.0),
                        System.currentTimeMillis()
                )
        );

        service.recordLostFromRelocationDrop(
                npcUuid,
                ownerUuid,
                new Vector3d(10.0, 20.0, 30.0),
                new Vector3d(40.0, 50.0, 60.0),
                null,
                100L,
                200L,
                2
        );

        assertFalse(service.isLost(npcUuid));
        assertNull(service.getLostSnapshot(npcUuid));
    }

    @Test
    void doesNotMarkCoopedCompanionAsLost() {
        Path persistencePath = tempDir.resolve("CommandLinkedNpcLost.dat");
        CommandLinkedNpcCoopService coopService = new CommandLinkedNpcCoopService();
        CommandLinkedNpcLostService service = new CommandLinkedNpcLostService(
                persistencePath,
                null,
                null,
                null,
                coopService
        );
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();

        coopService.recordCoopSnapshot(
                new CommandLinkedNpcCoopService.CoopLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        new String[] {"tool-alpha"},
                        "server.npcRole.test",
                        "Cooped Companion",
                        "coop/chicken_oak",
                        -1,
                        System.currentTimeMillis()
                )
        );

        service.recordLostFromRelocationDrop(
                npcUuid,
                ownerUuid,
                new Vector3d(10.0, 20.0, 30.0),
                new Vector3d(40.0, 50.0, 60.0),
                null,
                100L,
                200L,
                2
        );

        assertFalse(service.isLost(npcUuid));
        assertNull(service.getLostSnapshot(npcUuid));
    }

    private void assertVector(Vector3d value, double x, double y, double z) {
        assertNotNull(value);
        assertEquals(x, value.x, 0.0001);
        assertEquals(y, value.y, 0.0001);
        assertEquals(z, value.z, 0.0001);
    }
}
