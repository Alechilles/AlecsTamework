package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.vessels.runtime.NpcProfileBondedVesselProfilePort;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for pre-fix captured profiles whose role column was left empty. */
class NpcProfileBondedVesselRoleRecoveryIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void durableClassificationRestoresMissingCanonicalCaptureRole() throws Exception {
        try (HydragonPersistenceTestHarness harness = new HydragonPersistenceTestHarness(
                tempDir.resolve("captured-role-recovery.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(
                    owner, null, "CAPTURED", "default", 2L);
            PopulationGroupRepository groups = new PopulationGroupRepository(
                    harness.connections, harness.queue);
            assertEquals(PopulationGroupRepository.Status.APPLIED,
                    await(groups.replaceClassificationAsync(
                            new PopulationGroupRepository.ClassificationMutation(null,
                                    new PopulationGroupClassificationRecord(
                                            profileId, "NordicDrake", List.of(), 1L,
                                            PopulationGroupClassificationRecord.Status.RESOLVED,
                                            "owner_population_admission", 1L, 1L)))).status());
            OwnerPopulationIndex populations = new OwnerPopulationIndex();
            populations.replaceCommittedEntries(List.of(new OwnerPopulationEntry(
                            profileId, owner, "default",
                            CompanionLifecycleState.CAPTURED, 2L)),
                    OwnerPopulationReadiness.READY);
            var port = new NpcProfileBondedVesselProfilePort(
                    new NpcProfileRepository(harness.connections, harness.queue),
                    populations, Runnable::run, groups, null, null);

            var profile = port.load(profileId).toCompletableFuture().join();

            assertEquals("NordicDrake", profile.roleId());
            assertEquals(2L, profile.revision());
        }
    }
}
