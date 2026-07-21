package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.HydragonPersistenceTestHarness.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers first binding, generation fencing, source-finalization commit, and replay behavior. */
class BondedVesselRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void summonAdvancesOneGenerationAndCopiedOldProjectionCannotPrepareAgain() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("summon.sqlite")) {
            UUID owner = UUID.randomUUID();
            String profileId = harness.insertProfile(owner, "dragon-role", "CAPTURED", "default", 5L);
            BondedVesselRepository repository = new BondedVesselRepository(
                    harness.connections, harness.queue);
            String bindingId = UUID.randomUUID().toString();
            BondedVesselBindingRecord initialBinding = initialBinding(bindingId, profileId, owner);
            BondedVesselOperationRecord initialOperation = initialOperation(bindingId, profileId);

            assertEquals(BondedVesselRepository.Status.APPLIED,
                    await(repository.createInitialBindingAsync(initialBinding, initialOperation)).status());
            assertEquals(BondedVesselRepository.Status.COMMITTED,
                    await(repository.commitAsync("bind-op", 20L)).status());
            assertNull(repository.findBinding(bindingId).activeOperationId());

            BondedVesselOperationRecord summon = summonOperation(bindingId, profileId);
            assertEquals(BondedVesselRepository.Status.PREPARED,
                    await(repository.prepareTransitionAsync(summon)).status());
            assertEquals(BondedVesselRepository.Status.APPLYING,
                    await(repository.claimForApplyAsync("summon-op", -1_000L)).status());
            assertEquals(BondedVesselRepository.Status.IDEMPOTENT,
                    await(repository.claimForApplyAsync("summon-op", -999L)).status());

            UUID activeNpc = UUID.randomUUID();
            BondedVesselRepository.AppliedTransition applied = new BondedVesselRepository.AppliedTransition(
                    "summon-op", 6L, activeNpc,
                    new BondedVesselBindingRecord.PhysicalLocation("default", 4, -2),
                    "{\"holder\":\"owner\",\"slot\":3}", "summoned", -900L);
            assertEquals(BondedVesselRepository.Status.APPLIED,
                    await(repository.applyAsync(applied)).status());
            assertEquals(BondedVesselRepository.Status.IDEMPOTENT,
                    await(repository.applyAsync(applied)).status());
            assertEquals(BondedVesselRepository.Status.COMMITTED,
                    await(repository.commitAsync("summon-op", -800L)).status());

            BondedVesselBindingRecord committed = repository.findBinding(bindingId);
            assertEquals(2L, committed.generation());
            assertEquals(BondedVesselBindingRecord.LifecycleState.ACTIVE, committed.lifecycleState());
            assertEquals(activeNpc, committed.activeNpcUuid());
            assertEquals(-200L, committed.cooldownUntilMs());
            assertNull(committed.activeOperationId());

            BondedVesselOperationRecord copiedGeneration = new BondedVesselOperationRecord(
                    "stale-store", "hydragon", "store-stale", null,
                    bindingId, profileId, BondedVesselOperationRecord.Action.STORE,
                    BondedVesselOperationRecord.State.PREPARED, 1L, 2L, 6L,
                    "dragon-stone", 2L,
                    BondedVesselBindingRecord.LifecycleState.ACTIVE,
                    BondedVesselBindingRecord.LifecycleState.STORING,
                    BondedVesselBindingRecord.LifecycleState.STORED,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                    BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                    -200L, -100L, "active-stone", "stored-stone",
                    "old-copy", "replacement", "{}", "{}", null,
                    null, null, "NONE", 10_000L, 30L, 30L, 0L, 0L);
            BondedVesselRepository.MutationResult stale = await(
                    repository.prepareTransitionAsync(copiedGeneration));
            assertEquals(BondedVesselRepository.Status.DENIED, stale.status());
            assertEquals("stale_generation", stale.reason());
        }
    }

    private BondedVesselBindingRecord initialBinding(String bindingId, String profileId, UUID owner) {
        return new BondedVesselBindingRecord(
                bindingId, profileId, 1L, "dragon-stone", 2L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                owner, 5L, null, null, 0L, "stored-stone", "{}", "bind-op",
                null, 0L, 1L, 1L, 0L);
    }

    private BondedVesselOperationRecord initialOperation(String bindingId, String profileId) {
        return new BondedVesselOperationRecord(
                "bind-op", "tamework", "capture-attempt", null,
                bindingId, profileId, BondedVesselOperationRecord.Action.INITIAL_BIND,
                BondedVesselOperationRecord.State.APPLIED, 0L, 1L, 5L,
                "dragon-stone", 2L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 0L, "empty-stone", "stored-stone", "source", "replacement",
                "{}", "{}", "population-bind", null, "captured", "NONE",
                5_000L, 1L, 1L, 1L, 0L);
    }

    private BondedVesselOperationRecord summonOperation(String bindingId, String profileId) {
        return new BondedVesselOperationRecord(
                "summon-op", "hydragon", "summon-1", "encounter-a",
                bindingId, profileId, BondedVesselOperationRecord.Action.SUMMON,
                BondedVesselOperationRecord.State.PREPARED, 1L, 2L, 5L,
                "dragon-stone", 2L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.SUMMONING,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, -200L, "stored-stone", "active-stone", "source", "replacement",
                "{}", "{}", "population-summon", null, null, "NONE",
                5_000L, 10L, 10L, 0L, 0L);
    }

    private HydragonPersistenceTestHarness harness(String filename) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(filename));
    }
}
