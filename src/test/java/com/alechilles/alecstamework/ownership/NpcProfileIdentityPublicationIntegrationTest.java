package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers live identity publication for profiles created after the startup alias snapshot. */
class NpcProfileIdentityPublicationIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotUpsertReservesTheDurableProfileBeforeAsyncCommit() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null);
             OwnerPopulationRuntime population = OwnerPopulationRuntime.initialize(persistence)) {
            UUID npcUuid = UUID.randomUUID();
            NpcProfileRepository profiles = persistence.getNpcProfileRepository();

            assertTrue(profiles.upsertSnapshotAsync(profile(npcUuid)));
            String observedProfileId = population.identityResolver()
                    .resolveProfileId(npcUuid)
                    .orElseThrow();

            assertTrue(persistence.awaitWriteQueueIdle(3_000L));
            assertEquals(observedProfileId, profiles.resolveProfileId(npcUuid));
            assertFalse(population.identityResolver()
                    .resolveOrAllocate(npcUuid, "later-runtime-observation")
                    .provisional());
        }
    }

    @Test
    void rejectedProfileWriteReleasesItsProvisionalIdentity() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null);
             OwnerPopulationRuntime population = OwnerPopulationRuntime.initialize(persistence)) {
            UUID npcUuid = UUID.randomUUID();
            persistence.getHealthService().markDegraded("test-rejection");

            assertFalse(persistence.getNpcProfileRepository().upsertSnapshotAsync(profile(npcUuid)));
            assertTrue(population.identityResolver().resolveProfileId(npcUuid).isEmpty());
        }
    }

    @Test
    void snapshotUpsertReusesAnOverlappingSpawnReservation() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null);
             OwnerPopulationRuntime population = OwnerPopulationRuntime.initialize(persistence)) {
            UUID npcUuid = UUID.randomUUID();
            CompanionIdentityResolver.Resolution spawn = population.identityResolver()
                    .resolveOrAllocate(npcUuid, "overlapping-spawn");

            assertTrue(persistence.getNpcProfileRepository().upsertSnapshotAsync(profile(npcUuid)));
            assertTrue(persistence.awaitWriteQueueIdle(3_000L));

            assertEquals(
                    spawn.profileId(),
                    persistence.getNpcProfileRepository().resolveProfileId(npcUuid)
            );
            assertFalse(population.identityResolver()
                    .resolveOrAllocate(npcUuid, "post-commit-observation")
                    .provisional());
        }
    }

    @Test
    void historicalAliasPublicationPreservesTheDurableCurrentUuid() throws Exception {
        try (TameworkPersistenceRuntime persistence =
                     TameworkPersistenceRuntime.initialize(tempDir, null);
             OwnerPopulationRuntime population = OwnerPopulationRuntime.initialize(persistence)) {
            UUID currentNpcUuid = UUID.randomUUID();
            UUID historicalNpcUuid = UUID.randomUUID();
            NpcProfileRepository profiles = persistence.getNpcProfileRepository();
            assertTrue(profiles.upsertSnapshotAsync(profile(currentNpcUuid)));
            assertTrue(persistence.awaitWriteQueueIdle(3_000L));
            String profileId = profiles.resolveProfileId(currentNpcUuid);

            assertTrue(population.identityResolver()
                    .retainPreparedAlias(profileId, historicalNpcUuid));
            assertTrue(profiles.upsertSnapshotAsync(profile(historicalNpcUuid)));
            assertTrue(persistence.awaitWriteQueueIdle(3_000L));

            assertEquals(profileId, profiles.resolveProfileId(historicalNpcUuid));
            assertEquals(currentNpcUuid, profiles.loadProfileById(profileId).currentNpcUuid());
            assertEquals(
                    currentNpcUuid,
                    population.identityResolver().currentNpcUuid(profileId).orElseThrow()
            );
        }
    }

    private static NpcProfileRepository.ProfileUpdate profile(UUID npcUuid) {
        return new NpcProfileRepository.ProfileUpdate(
                npcUuid,
                null,
                null,
                "Tamed_Cow",
                "Cow",
                null,
                true,
                null,
                null,
                null,
                new String[]{UUID.randomUUID().toString()}
        );
    }
}
