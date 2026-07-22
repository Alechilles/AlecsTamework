package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureRepositoryProfileSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void captureWithoutCommandLinksStillPersistsCanonicalSummonRole() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("capture-profile.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, null, "CAPTURED", "default", 1L);
            UUID npcUuid = UUID.randomUUID();
            try (Connection connection = harness.connections.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE npc_profiles SET current_npc_uuid = ? WHERE profile_id = ?")) {
                statement.setString(1, npcUuid.toString());
                statement.setString(2, profileId);
                statement.executeUpdate();
            }
            NpcProfileRepository profiles = new NpcProfileRepository(
                    harness.connections, harness.queue);
            CaptureRepository captures = new CaptureRepository(
                    harness.connections, harness.queue, profiles);
            assertTrue(captures.upsertAsync(
                    new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                            npcUuid, owner, new String[0],
                            "Tamed_NordicDrake", "Nordic Drake", null, null, 5L)));
            assertTrue(harness.queue.awaitIdle(2_000L));

            assertEquals("Tamed_NordicDrake",
                    profiles.loadProfileById(profileId).roleId());
        }
    }
}
