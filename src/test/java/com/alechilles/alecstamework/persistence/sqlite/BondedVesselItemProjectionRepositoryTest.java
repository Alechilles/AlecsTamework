package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BondedVesselItemProjectionRepositoryTest {
    @TempDir Path tempDir;

    @Test
    void sealedProjectionCasChangesOnlyProjectionAndIsIdempotent() throws Exception {
        try (HydragonPersistenceTestHarness harness =
                     new HydragonPersistenceTestHarness(tempDir.resolve("projection.sqlite"))) {
            UUID owner = UUID.randomUUID();
            String profile = harness.insertProfile(owner, "dragon", "CAPTURED", "world", 4L);
            String bindingId = UUID.randomUUID().toString();
            BondedVesselRepository repository = new BondedVesselRepository(
                    harness.connections, harness.queue);
            BondedVesselOperationRecord operation = initialOperation(bindingId, profile);
            BondedVesselBindingRecord initial = new BondedVesselBindingRecord(
                    bindingId, profile, 1L, "dragon-stone", 2L,
                    BondedVesselBindingRecord.LifecycleState.STORED,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                    owner, 4L, null, null, 0L, "stored-stone", "{}",
                    operation.operationId(), null, 0L, 1L, 1L, 0L);
            assertEquals(BondedVesselRepository.Status.APPLIED,
                    await(repository.createInitialBindingAsync(initial, operation)).status());
            assertEquals(BondedVesselRepository.Status.COMMITTED,
                    await(repository.commitAsync(operation.operationId(), 2L)).status());

            assertEquals(BondedVesselRepository.Status.APPLIED,
                    await(repository.reconcileItemProjectionAsync(
                            bindingId, 1L,
                            BondedVesselBindingRecord.LifecycleState.STORED,
                            BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                            "sealed-absence", 3L)).status());
            BondedVesselBindingRecord reconciled = repository.findBinding(bindingId);
            assertEquals(1L, reconciled.generation());
            assertEquals(BondedVesselBindingRecord.LifecycleState.STORED,
                    reconciled.lifecycleState());
            assertEquals(BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                    reconciled.itemProjectionStatus());
            assertEquals(BondedVesselRepository.Status.IDEMPOTENT,
                    await(repository.reconcileItemProjectionAsync(
                            bindingId, 1L,
                            BondedVesselBindingRecord.LifecycleState.STORED,
                            BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                            "sealed-absence", 4L)).status());
            assertEquals(BondedVesselRepository.Status.CONFLICT,
                    await(repository.reconcileItemProjectionAsync(
                            bindingId, 2L,
                            BondedVesselBindingRecord.LifecycleState.STORED,
                            BondedVesselBindingRecord.ItemProjectionStatus.AMBIGUOUS,
                            "stale-generation", 5L)).status());
        }
    }

    private static BondedVesselOperationRecord initialOperation(
            String bindingId, String profileId) {
        return new BondedVesselOperationRecord(
                UUID.randomUUID().toString(), "test", "capture", null,
                bindingId, profileId, BondedVesselOperationRecord.Action.INITIAL_BIND,
                BondedVesselOperationRecord.State.APPLIED, 0L, 1L, 4L,
                "dragon-stone", 2L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 0L, "empty-stone", "stored-stone", "source", "replacement",
                "{}", "{}", null, null, "captured", "NONE",
                5_000L, 1L, 1L, 1L, 0L);
    }
}
