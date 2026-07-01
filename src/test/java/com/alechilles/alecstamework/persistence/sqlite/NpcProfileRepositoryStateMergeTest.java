package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcProfileRepositoryStateMergeTest {
    @TempDir
    Path tempDir;

    @Test
    void coopUpdatePreservesExistingNameAndTameState() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            NpcProfileRepository repository = runtime.getNpcProfileRepository();
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();

            assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    ownerUuid,
                    "Alec",
                    "tamed_chicken",
                    "Clucky",
                    "Clucky",
                    true,
                    null,
                    null,
                    null,
                    null
            )));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));

            assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "coop_chicken",
                    2,
                    null,
                    null
            )));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));

            NpcProfileRepository.ProfileRecord profile = repository.loadProfileByNpcUuid(npcUuid);
            assertEquals(ownerUuid, profile.ownerUuid());
            assertEquals("Clucky", profile.displayName());
            assertEquals("tamed_chicken", profile.roleId());
            assertEquals("Alec", profile.ownerName());
            assertEquals("Clucky", profile.customName());
            assertEquals(Boolean.TRUE, profile.tamed());
            assertEquals("coop_chicken", profile.coopId());
            assertEquals(2, profile.coopSlot());
        }
    }

    @Test
    void nameUpdatePreservesExistingCoopState() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            NpcProfileRepository repository = runtime.getNpcProfileRepository();
            UUID npcUuid = UUID.randomUUID();

            assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    null,
                    null,
                    "tamed_chicken",
                    null,
                    null,
                    null,
                    "coop_chicken",
                    1,
                    null,
                    null
            )));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));

            assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    null,
                    "Alec",
                    null,
                    "Henrietta",
                    "Henrietta",
                    true,
                    null,
                    null,
                    null,
                    null
            )));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));

            NpcProfileRepository.ProfileRecord profile = repository.loadProfileByNpcUuid(npcUuid);
            assertEquals("Henrietta", profile.displayName());
            assertEquals("tamed_chicken", profile.roleId());
            assertEquals("Alec", profile.ownerName());
            assertEquals("Henrietta", profile.customName());
            assertEquals(Boolean.TRUE, profile.tamed());
            assertEquals("coop_chicken", profile.coopId());
            assertEquals(1, profile.coopSlot());
        }
    }
}
