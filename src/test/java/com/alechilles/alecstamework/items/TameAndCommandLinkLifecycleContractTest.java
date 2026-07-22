package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the live identity needed to turn a Horn companion death into a durable roster death. */
class TameAndCommandLinkLifecycleContractTest {
    @Test
    void successfulCaptureInstallsRosterLifecycleAndDoesNotPublishCapturedItemState()
            throws Exception {
        String liveApply = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerTameAndCommandLinkService.java"));
        String handler = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java"));

        assertTrue(liveApply.contains("new TameworkCommandLinksComponent("));
        assertTrue(liveApply.contains("TameworkProjectionIdentityComponent.KIND_COMMAND_ROSTER"));
        assertTrue(liveApply.contains("\"roster:\" + lifecycle.ownerUuid()"));
        assertTrue(handler.contains("linkedNpcSyncService.clearCapturedSnapshotIfPresent("));
        assertTrue(handler.contains("boolean profileQueued = tameAndCommandLink || profilePersistence.persist("),
                "roster captures already own a canonical profile and must not create capture snapshots");
    }
}
