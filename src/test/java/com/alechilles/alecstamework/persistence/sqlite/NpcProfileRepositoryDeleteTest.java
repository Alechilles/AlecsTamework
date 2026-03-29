package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcProfileRepositoryDeleteTest {
    @TempDir
    Path tempDir;

    @Test
    void deleteProfileTreeRemovesResolvedProfileAndAliases() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            UUID npcUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            NpcProfileRepository repository = runtime.getNpcProfileRepository();

            assertTrue(repository.upsertAsync(new NpcProfileRepository.ProfileUpdate(
                    npcUuid,
                    ownerUuid,
                    "Owner A",
                    "Mob_Test",
                    "Display A",
                    "Custom A",
                    true,
                    null,
                    null,
                    null,
                    new String[] {"tool-a"}
            )));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));
            assertNotNull(repository.resolveProfileId(npcUuid));

            assertTrue(repository.deleteProfileTreeAsync(npcUuid));
            assertTrue(runtime.awaitWriteQueueIdle(3_000L));
            assertNull(repository.resolveProfileId(npcUuid));
            assertNull(repository.loadProfileByNpcUuid(npcUuid));
        }
    }
}
