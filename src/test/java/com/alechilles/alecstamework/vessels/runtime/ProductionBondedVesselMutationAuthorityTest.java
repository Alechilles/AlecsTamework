package com.alechilles.alecstamework.vessels.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.vessels.BondedVesselMutationAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ProductionBondedVesselMutationAuthorityTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BINDING = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID NPC = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String OPERATION = "40000000-0000-0000-0000-000000000004";

    @Test
    void storeClaimsUnifiedAdmissionBeforeWorldMutationAndCanonicalCommit() {
        List<String> sequence = new ArrayList<>();
        FakeProfiles profiles = new FakeProfiles(sequence);
        FakePopulations populations = new FakePopulations(sequence);
        FakeWorld world = new FakeWorld(sequence);
        ProductionBondedVesselMutationAuthority authority = new ProductionBondedVesselMutationAuthority(
                profiles, populations, world, Runnable::run);

        BondedVesselMutationAuthority.ApplyOutcome result = authority.apply(
                operation(4L), binding(4L), false).toCompletableFuture().join();

        assertEquals(BondedVesselMutationAuthority.Status.APPLIED, result.status());
        assertEquals(12L, result.committedProfileRevision());
        assertEquals(List.of("profile", "prepare", "claim", "world", "commit"), sequence);
        assertFalse(populations.canceled);
        assertEquals(CompanionLifecycleState.CAPTURED,
                populations.lastRequest.targetLifecycle());
    }

    @Test
    void staleCopiedGenerationFailsBeforeProfileOrPopulationMutation() {
        List<String> sequence = new ArrayList<>();
        FakeProfiles profiles = new FakeProfiles(sequence);
        FakePopulations populations = new FakePopulations(sequence);
        FakeWorld world = new FakeWorld(sequence);
        ProductionBondedVesselMutationAuthority authority = new ProductionBondedVesselMutationAuthority(
                profiles, populations, world, Runnable::run);

        BondedVesselMutationAuthority.ApplyOutcome result = authority.apply(
                operation(4L), binding(5L), false).toCompletableFuture().join();

        assertEquals(BondedVesselMutationAuthority.Status.TERMINAL_DENIED, result.status());
        assertEquals("stale-binding-generation", result.reason());
        assertTrue(sequence.isEmpty());
    }

    @Test
    void worldDenialCancelsEveryAdmissionCapabilityBeforeReturningTerminalDenial() {
        List<String> sequence = new ArrayList<>();
        FakeProfiles profiles = new FakeProfiles(sequence);
        FakePopulations populations = new FakePopulations(sequence);
        FakeWorld world = new FakeWorld(sequence);
        world.receipt = new ProductionBondedVesselMutationAuthority.WorldMutationReceipt(
                ProductionBondedVesselMutationAuthority.WorldMutationStatus.TERMINAL_DENIED,
                "spawn-role-unavailable", null, null, "{}");
        ProductionBondedVesselMutationAuthority authority = new ProductionBondedVesselMutationAuthority(
                profiles, populations, world, Runnable::run);

        BondedVesselMutationAuthority.ApplyOutcome result = authority.apply(
                operation(4L), binding(4L), false).toCompletableFuture().join();

        assertEquals(BondedVesselMutationAuthority.Status.TERMINAL_DENIED, result.status());
        assertTrue(populations.canceled);
        assertEquals(List.of("profile", "prepare", "claim", "world", "cancel"), sequence);
    }

    @Test
    void missingGroupAuthorityKeepsCapabilityAndApplyFailClosed() {
        List<String> sequence = new ArrayList<>();
        FakeProfiles profiles = new FakeProfiles(sequence);
        FakePopulations populations = new FakePopulations(sequence);
        populations.readiness = new ProductionBondedVesselMutationAuthority.PopulationReadiness(
                true, true, false, true, true, "population-group-authority-unavailable");
        ProductionBondedVesselMutationAuthority authority = new ProductionBondedVesselMutationAuthority(
                profiles, populations, new FakeWorld(sequence), Runnable::run);

        BondedVesselMutationAuthority.ApplyOutcome result = authority.apply(
                operation(4L), binding(4L), false).toCompletableFuture().join();

        assertFalse(authority.isCapabilityReady());
        assertEquals(BondedVesselMutationAuthority.Status.INDETERMINATE, result.status());
        assertEquals("population-group-authority-unavailable", result.reason());
        assertTrue(sequence.isEmpty());
    }

    @Test
    void canonicalAndPopulationPortsAreInvokedOnCoordinationExecutor() throws Exception {
        List<String> sequence = new ArrayList<>();
        FakeProfiles profiles = new FakeProfiles(sequence);
        FakePopulations populations = new FakePopulations(sequence);
        FakeWorld world = new FakeWorld(sequence);
        String callerThread = Thread.currentThread().getName();
        try (ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "vessel-coordination-test"))) {
            ProductionBondedVesselMutationAuthority authority =
                    new ProductionBondedVesselMutationAuthority(
                            profiles, populations, world, executor);

            authority.apply(operation(4L), binding(4L), false).toCompletableFuture().join();
        }

        assertNotEquals(callerThread, profiles.invocationThread);
        assertEquals("vessel-coordination-test", profiles.invocationThread);
        assertEquals("vessel-coordination-test", populations.prepareThread);
        assertEquals("vessel-coordination-test", populations.commitThread);
    }

    private static BondedVesselBindingRecord binding(long generation) {
        return new BondedVesselBindingRecord(
                BINDING.toString(), "profile-1", generation, "dragon-vessel", 3L,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                OWNER, 11L, NPC,
                new BondedVesselBindingRecord.PhysicalLocation("world", 1, 2),
                0L, "dragon-stone-active", "{}", OPERATION, null,
                2L, 100L, 110L, 0L);
    }

    private static BondedVesselOperationRecord operation(long generation) {
        return new BondedVesselOperationRecord(
                OPERATION, "hydragon", "store-1", null,
                BINDING.toString(), "profile-1", BondedVesselOperationRecord.Action.STORE,
                BondedVesselOperationRecord.State.APPLYING,
                generation, generation + 1L, 11L, "dragon-vessel", 3L,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 100L, "dragon-stone-active", "dragon-stone-stored",
                "active-g4", "stored-g5", "{}", "{}", "population-1",
                NPC, null, "APPLYING", 0L, 100L, 110L, 0L, 0L);
    }

    private static ProductionBondedVesselMutationAuthority.PopulationHandle handle() {
        return new ProductionBondedVesselMutationAuthority.PopulationHandle(
                OPERATION, BINDING.toString(), "profile-1", 4L, 5L, "capability-1");
    }

    private static final class FakeProfiles
            implements ProductionBondedVesselMutationAuthority.CanonicalProfilePort {
        private final List<String> sequence;
        private String invocationThread;

        private FakeProfiles(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public CompletableFuture<ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot> load(
                String profileId) {
            sequence.add("profile");
            invocationThread = Thread.currentThread().getName();
            return CompletableFuture.completedFuture(
                    new ProductionBondedVesselMutationAuthority.CanonicalProfileSnapshot(
                            "profile-1", OWNER, "dragon-role", 11L,
                            CompanionLifecycleState.ACTIVE, NPC));
        }

        @Override
        public ProductionBondedVesselMutationAuthority.ProfileReadiness readiness() {
            return new ProductionBondedVesselMutationAuthority.ProfileReadiness(true, "ready");
        }
    }

    private static final class FakePopulations
            implements ProductionBondedVesselMutationAuthority.UnifiedPopulationPort {
        private final List<String> sequence;
        private ProductionBondedVesselMutationAuthority.PopulationReadiness readiness =
                new ProductionBondedVesselMutationAuthority.PopulationReadiness(
                        true, true, true, true, true, "ready");
        private boolean canceled;
        private String prepareThread;
        private String commitThread;
        private ProductionBondedVesselMutationAuthority.PopulationMutationRequest lastRequest;

        private FakePopulations(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public CompletableFuture<ProductionBondedVesselMutationAuthority.PopulationPreparation> prepare(
                ProductionBondedVesselMutationAuthority.PopulationMutationRequest request) {
            sequence.add("prepare");
            prepareThread = Thread.currentThread().getName();
            lastRequest = request;
            return CompletableFuture.completedFuture(
                    new ProductionBondedVesselMutationAuthority.PopulationPreparation(
                            ProductionBondedVesselMutationAuthority.PopulationPreparationStatus.PREPARED,
                            "prepared", handle()));
        }

        @Override
        public CompletableFuture<ProductionBondedVesselMutationAuthority.PopulationClaim> claim(
                ProductionBondedVesselMutationAuthority.PopulationHandle handle) {
            sequence.add("claim");
            return CompletableFuture.completedFuture(
                    new ProductionBondedVesselMutationAuthority.PopulationClaim(
                            ProductionBondedVesselMutationAuthority.PopulationClaimStatus.CLAIMED,
                            "claimed", handle));
        }

        @Override
        public CompletableFuture<ProductionBondedVesselMutationAuthority.PopulationCommit> commit(
                ProductionBondedVesselMutationAuthority.PopulationHandle handle,
                ProductionBondedVesselMutationAuthority.PopulationMutationRequest request,
                ProductionBondedVesselMutationAuthority.WorldMutationReceipt worldReceipt) {
            sequence.add("commit");
            commitThread = Thread.currentThread().getName();
            return CompletableFuture.completedFuture(
                    new ProductionBondedVesselMutationAuthority.PopulationCommit(
                            ProductionBondedVesselMutationAuthority.PopulationCommitStatus.APPLIED,
                            "canonical-population-committed", 12L, null, null,
                            "{\"generation\":5}"));
        }

        @Override
        public CompletableFuture<Boolean> cancel(
                ProductionBondedVesselMutationAuthority.PopulationHandle handle,
                String reason) {
            sequence.add("cancel");
            canceled = true;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public ProductionBondedVesselMutationAuthority.PopulationReadiness readiness() {
            return readiness;
        }
    }

    private static final class FakeWorld
            implements ProductionBondedVesselMutationAuthority.WorldProjectionPort {
        private final List<String> sequence;
        private ProductionBondedVesselMutationAuthority.WorldMutationReceipt receipt =
                new ProductionBondedVesselMutationAuthority.WorldMutationReceipt(
                        ProductionBondedVesselMutationAuthority.WorldMutationStatus.APPLIED,
                        "projection-removed", null, null, "{}");

        private FakeWorld(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public CompletableFuture<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> apply(
                ProductionBondedVesselMutationAuthority.WorldMutationRequest request) {
            sequence.add("world");
            return CompletableFuture.completedFuture(receipt);
        }

        @Override
        public ProductionBondedVesselMutationAuthority.WorldReadiness readiness() {
            return new ProductionBondedVesselMutationAuthority.WorldReadiness(
                    true, true, true, "ready");
        }
    }
}
