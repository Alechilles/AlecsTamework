package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures managed-coop snapshot enrichment cannot become a profile ownership writer. */
class ManagedCoopCaptureProfileRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void profileEnsurePreservesCanonicalOwner() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("capture-profile.sqlite")
        );
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        PersistenceWriteQueue queue = new PersistenceWriteQueue(
                connections, new PersistenceHealthService(), null
        );
        try {
            NpcProfileRepository profiles = new NpcProfileRepository(connections, queue);
            UUID npcUuid = UUID.randomUUID();
            UUID canonicalOwner = UUID.randomUUID();
            assertTrue(profiles.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid, canonicalOwner, "Owner", "mob_chicken", "Chicken",
                    null, null, null, null, null, new String[0]
            )));
            assertTrue(queue.awaitIdle(2_000L));

            ManagedCoopCaptureProfileRepository captureProfiles =
                    new ManagedCoopCaptureProfileRepository(queue, profiles);
            PersistenceWriteQueue.WriteOutcome<ManagedCoopCaptureProfileRepository.ProfileIdentity>
                    outcome = captureProfiles.ensureProfile(
                            new ManagedCoopCaptureProfileRepository.ProfileSeed(
                                    npcUuid,
                                    UUID.randomUUID(),
                                    "mob_chicken",
                                    "Renamed Chicken",
                                    new String[]{"tool-a"}
                            )
                    ).completion().get(3, TimeUnit.SECONDS);

            assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, outcome.status());
            assertEquals(canonicalOwner,
                    profiles.loadProfileById(outcome.value().profileId()).ownerUuid());
        } finally {
            queue.close();
        }
    }
}
