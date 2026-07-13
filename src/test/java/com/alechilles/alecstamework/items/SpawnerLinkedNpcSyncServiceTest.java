package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SpawnerLinkedNpcSyncServiceTest {

    @Test
    void preparedSnapshotSurvivesLaterLiveLinkInvalidation() {
        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        CommandLinkedNpcCaptureService captureService = new CommandLinkedNpcCaptureService();
        SpawnerLinkedNpcSyncService syncService = new SpawnerLinkedNpcSyncService(captureService);
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                ownerUuid,
                new String[] {"tool-alpha", "tool-beta"},
                new Vector3d(4.0, 5.0, 6.0)
        );

        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot prepared =
                SpawnerLinkedNpcSyncService.buildPreparedSnapshot(
                        npcUuid,
                        null,
                        links,
                        "tamed_skrill",
                        "Skrill",
                        new Vector3d(1.0, 2.0, 3.0),
                        42L
        );
        links.setToolIds(new String[0]);
        links.setOwnerId(null);
        syncService.publishPreparedCapturedLinkedNpcSnapshot(prepared, npcUuid);

        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot published =
                captureService.getCapturedSnapshot(npcUuid);

        assertNotNull(prepared);
        assertNotNull(published);
        assertEquals(npcUuid, published.npcUuid());
        assertEquals(ownerUuid, published.ownerId());
        assertArrayEquals(new String[] {"tool-alpha", "tool-beta"}, published.toolIds());
        assertEquals(new Vector3d(1.0, 2.0, 3.0), published.lastKnownPosition());
        assertEquals(new Vector3d(4.0, 5.0, 6.0), published.homePosition());
        assertEquals(42L, published.capturedAtMs());
    }

    @Test
    void unlinkedNpcDoesNotCreateCapturedPanelSnapshot() {
        TameworkCommandLinksComponent links = new TameworkCommandLinksComponent(
                UUID.randomUUID(),
                new String[0]
        );

        assertNull(SpawnerLinkedNpcSyncService.buildPreparedSnapshot(
                UUID.randomUUID(),
                null,
                links,
                "tamed_skrill",
                "Skrill",
                null,
                42L
        ));
    }
}
