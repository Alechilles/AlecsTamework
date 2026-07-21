package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselBindingInvalidatedEvent;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.ownership.reconciliation.BondedVesselInventoryEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPersistedProjectionEvidenceRegistry;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BondedVesselItemProjectionReconcilerTest {
    private static final UUID BINDING =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void unsealedCoverageIsUnknownAndNeverMutatesLifecycleProjection() {
        FakeStore store = new FakeStore(binding(BondedVesselBindingRecord.LifecycleState.ACTIVE));
        BondedVesselItemProjectionReconciler reconciler = reconciler(store, new ArrayList<>());

        BondedVesselItemProjectionReconciler.Report report = reconciler.reconcileSealed(
                snapshot(CompanionPersistedProjectionEvidenceRegistry.State.SCANNING, List.of()))
                .toCompletableFuture().join();

        assertEquals(BondedVesselItemProjectionReconciler.Status.UNKNOWN, report.status());
        assertEquals(0, store.updates.size());
    }

    @Test
    void sealedAbsenceMarksMissingWithoutChangingActiveLifecycleOrGeneration() {
        BondedVesselBindingRecord original =
                binding(BondedVesselBindingRecord.LifecycleState.ACTIVE);
        FakeStore store = new FakeStore(original);
        List<TameworkEvent> events = new ArrayList<>();

        BondedVesselItemProjectionReconciler.Report report = reconciler(store, events)
                .reconcileSealed(snapshot(
                        CompanionPersistedProjectionEvidenceRegistry.State.SEALED, List.of()))
                .toCompletableFuture().join();

        assertEquals(BondedVesselItemProjectionReconciler.Status.RECONCILED, report.status());
        assertEquals(List.of(BondedVesselProjectionStatus.MISSING), store.updates);
        assertEquals(BondedVesselBindingRecord.LifecycleState.ACTIVE, original.lifecycleState());
        assertEquals(7L, original.generation());
        BondedVesselBindingInvalidatedEvent event =
                (BondedVesselBindingInvalidatedEvent) events.getFirst();
        assertEquals(event.oldGeneration(), event.newGeneration());
        assertEquals(BondedVesselState.ACTIVE, event.state());
    }

    @Test
    void storedAndOnlineCopiesOfTheSameLocationDeduplicateToPresent() {
        BondedVesselBindingRecord binding =
                binding(BondedVesselBindingRecord.LifecycleState.STORED);
        String expected = fingerprint(binding);
        String holder = OWNER.toString().toLowerCase();
        List<CompanionPopulationEvidence> evidence = List.of(
                item("player-save/" + holder + "/hotbar/slot-3", expected, 7L),
                item("online-player/" + holder + "/hotbar/slot-3", expected, 7L));
        FakeStore store = new FakeStore(binding);
        List<TameworkEvent> events = new ArrayList<>();

        reconciler(store, events).reconcileSealed(snapshot(
                CompanionPersistedProjectionEvidenceRegistry.State.SEALED, evidence))
                .toCompletableFuture().join();

        assertEquals(List.of(BondedVesselProjectionStatus.PRESENT), store.updates);
        assertTrue(events.isEmpty());
    }

    @Test
    void staleOrDuplicateLocationsAreAmbiguousAndEmitOneInvalidation() {
        BondedVesselBindingRecord binding =
                binding(BondedVesselBindingRecord.LifecycleState.STORED);
        String expected = fingerprint(binding);
        List<CompanionPopulationEvidence> evidence = List.of(
                item("custom/a/slot-0", expected, 7L),
                item("custom/b/slot-1", expected, 7L));
        FakeStore store = new FakeStore(binding);
        List<TameworkEvent> events = new ArrayList<>();

        reconciler(store, events).reconcileSealed(snapshot(
                CompanionPersistedProjectionEvidenceRegistry.State.SEALED, evidence))
                .toCompletableFuture().join();

        assertEquals(List.of(BondedVesselProjectionStatus.AMBIGUOUS), store.updates);
        assertEquals(1, events.size());
    }

    private static BondedVesselItemProjectionReconciler reconciler(
            FakeStore store, List<TameworkEvent> events) {
        return new BondedVesselItemProjectionReconciler(
                store, events::add, Runnable::run, () -> 100L);
    }

    private static CompanionPersistedProjectionEvidenceRegistry.Snapshot snapshot(
            CompanionPersistedProjectionEvidenceRegistry.State state,
            List<CompanionPopulationEvidence> evidence) {
        return new CompanionPersistedProjectionEvidenceRegistry.Snapshot(
                state, "scan-a", new CompanionPopulationEvidenceSet(evidence),
                null, 0L, 12L, null);
    }

    private static CompanionPopulationEvidence item(
            String location, String fingerprint, long generation) {
        return new CompanionPopulationEvidence(
                BondedVesselInventoryEvidence.append(
                        location, BINDING, generation, fingerprint),
                BINDING, null, false,
                CompanionPopulationEvidence.Kind.CAPTURED_ITEM,
                null, null, null, null, "test");
    }

    private static String fingerprint(BondedVesselBindingRecord binding) {
        return new BondedVesselItemFingerprintCodec().fingerprint(
                new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                        binding.lastItemId(), BINDING, binding.profileId(), binding.generation(),
                        binding.configId(), BondedVesselState.valueOf(binding.lifecycleState().name())));
    }

    private static BondedVesselBindingRecord binding(
            BondedVesselBindingRecord.LifecycleState lifecycle) {
        return new BondedVesselBindingRecord(
                BINDING.toString(), "profile-a", 7L, "dragon-stone", 3L,
                lifecycle, BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                OWNER, 5L,
                lifecycle == BondedVesselBindingRecord.LifecycleState.ACTIVE
                        ? UUID.fromString("33333333-3333-3333-3333-333333333333") : null,
                lifecycle == BondedVesselBindingRecord.LifecycleState.ACTIVE
                        ? new BondedVesselBindingRecord.PhysicalLocation("world", 1, 2) : null,
                0L,
                lifecycle == BondedVesselBindingRecord.LifecycleState.ACTIVE
                        ? "active-stone" : "stored-stone",
                "{}", null, null, 2L, 1L, 2L, 0L);
    }

    private static final class FakeStore
            implements BondedVesselItemProjectionReconciler.ProjectionStore {
        private final List<BondedVesselBindingRecord> bindings;
        private final List<BondedVesselProjectionStatus> updates = new ArrayList<>();

        private FakeStore(BondedVesselBindingRecord binding) {
            this.bindings = List.of(binding);
        }

        @Override
        public List<BondedVesselBindingRecord> loadNonReleasedBindings() {
            return bindings;
        }

        @Override
        public java.util.concurrent.CompletionStage<
                BondedVesselItemProjectionReconciler.UpdateResult> update(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionStatus status,
                String reason,
                long nowMs) {
            updates.add(status);
            return CompletableFuture.completedFuture(
                    new BondedVesselItemProjectionReconciler.UpdateResult(
                            BondedVesselItemProjectionReconciler.UpdateStatus.CHANGED));
        }
    }
}
