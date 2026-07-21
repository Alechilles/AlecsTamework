package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.api.BondedVesselMode;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselBindingInvalidatedEvent;
import com.alechilles.alecstamework.api.SpawnerVesselConfigView;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselLifecycleObserver;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for command-link-independent vessel death/lost authority. */
class BondedVesselLifecycleObserverTest {
    @TempDir Path tempDir;

    @Test
    void duplicateOfflineDeathAdvancesOneGenerationAndMarksItemMissing() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("vessel-death.sqlite")) {
            Fixture fixture = activeFixture(harness);
            List<Object> events = new ArrayList<>();
            BondedVesselLifecycleObserver observer = observer(
                    fixture.repository(), new OfflineEvidence(), events);
            var observation = new BondedVesselLifecycleObserver.Observation(
                    fixture.profileId(), fixture.npcUuid(), 9L, BondedVesselState.DEAD,
                    "death-recorded", "population-death-9");

            assertEquals(BondedVesselLifecycleObserver.Status.COMMITTED,
                    observer.observe(observation).toCompletableFuture().join().status());
            assertEquals(BondedVesselLifecycleObserver.Status.IDEMPOTENT,
                    observer.observe(observation).toCompletableFuture().join().status());

            BondedVesselBindingRecord binding = fixture.repository()
                    .findBinding(fixture.bindingId().toString());
            assertEquals(3L, binding.generation());
            assertEquals(BondedVesselBindingRecord.LifecycleState.DEAD,
                    binding.lifecycleState());
            assertEquals(BondedVesselBindingRecord.ItemProjectionStatus.MISSING,
                    binding.itemProjectionStatus());
            assertNull(binding.activeNpcUuid());
            assertEquals(2, events.size());
            assertEquals(1L, events.stream()
                    .filter(BondedVesselBindingInvalidatedEvent.class::isInstance).count());
            long operations = fixture.repository().loadRecoverableOperations().stream()
                    .filter(operation -> operation.action()
                            == BondedVesselOperationRecord.Action.MARK_DEAD).count();
            assertEquals(0L, operations, "committed death must not remain recoverable");
        }
    }

    @Test
    void onlineLostRewritePublishesPresentProjectionOnce() throws Exception {
        try (HydragonPersistenceTestHarness harness = harness("vessel-lost.sqlite")) {
            Fixture fixture = activeFixture(harness);
            List<Object> events = new ArrayList<>();
            PresentEvidence evidence = new PresentEvidence();
            BondedVesselLifecycleObserver observer = observer(
                    fixture.repository(), evidence, events);
            var observation = new BondedVesselLifecycleObserver.Observation(
                    fixture.profileId(), fixture.npcUuid(), 9L, BondedVesselState.LOST,
                    "lost-recorded", "population-lost-9");

            assertEquals(BondedVesselLifecycleObserver.Status.COMMITTED,
                    observer.observe(observation).toCompletableFuture().join().status());
            BondedVesselBindingRecord binding = fixture.repository()
                    .findBinding(fixture.bindingId().toString());
            assertEquals(BondedVesselBindingRecord.LifecycleState.LOST,
                    binding.lifecycleState());
            assertEquals(BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                    binding.itemProjectionStatus());
            assertEquals("{\"generation\":3}", binding.itemEvidenceJson());
            assertEquals(1, evidence.finalizations.get());
            assertEquals(1, events.size());
        }
    }

    private BondedVesselLifecycleObserver observer(
            BondedVesselRepository repository,
            BondedVesselEvidenceAuthority evidence,
            List<Object> events) {
        SpawnerVesselConfigView config = new SpawnerVesselConfigView(
                "dragon-stone", 4L, BondedVesselMode.BONDED,
                "empty-stone", "stored-stone", "active-stone", "dead-stone",
                "lost-stone", null, 10_000L, 12.0D, null, null, true, false);
        return new BondedVesselLifecycleObserver(
                repository, (id, revision) -> id.equals(config.configId())
                && revision == config.configRevision() ? Optional.of(config) : Optional.empty(),
                evidence, events::add, Runnable::run, System::currentTimeMillis);
    }

    private Fixture activeFixture(HydragonPersistenceTestHarness harness) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID npcUuid = UUID.randomUUID();
        String profile = harness.insertProfile(owner, "dragon-role", "ACTIVE", "world", 8L);
        BondedVesselRepository repository = new BondedVesselRepository(
                harness.connections, harness.queue);
        String evidence = new Gson().toJson(new BondedVesselSourceItemEvidence(
                "stored-stone", "player:" + owner.toString().toLowerCase(), "hotbar",
                2, 1L, "stored-fingerprint"));
        UUID initialId = UUID.randomUUID();
        BondedVesselOperationRecord initial = new BondedVesselOperationRecord(
                initialId.toString(), "test", "initial", null, bindingId.toString(), profile,
                BondedVesselOperationRecord.Action.INITIAL_BIND,
                BondedVesselOperationRecord.State.APPLIED, 0L, 1L, 7L,
                "dragon-stone", 4L, BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.STORING,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 0L, "empty-stone", "stored-stone", "empty-fingerprint",
                "stored-fingerprint", "{}", "{}", null, null, "captured", "pending",
                0L, 1L, 1L, 1L, 0L);
        BondedVesselBindingRecord stored = new BondedVesselBindingRecord(
                bindingId.toString(), profile, 1L, "dragon-stone", 4L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT, owner, 7L,
                null, null, 0L, "stored-stone", evidence, initialId.toString(), null,
                0L, 1L, 1L, 0L);
        join(repository.createInitialBindingAsync(stored, initial));
        join(repository.commitAsync(initialId.toString(), 2L));

        UUID summonId = UUID.randomUUID();
        BondedVesselOperationRecord summon = new BondedVesselOperationRecord(
                summonId.toString(), "test", "summon", null, bindingId.toString(), profile,
                BondedVesselOperationRecord.Action.SUMMON,
                BondedVesselOperationRecord.State.PREPARED, 1L, 2L, 7L,
                "dragon-stone", 4L, BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.LifecycleState.SUMMONING,
                BondedVesselBindingRecord.LifecycleState.ACTIVE,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                0L, 10_000L, "stored-stone", "active-stone", "stored-fingerprint",
                "active-fingerprint", "{}", "{}", null, null, null, "prepared",
                0L, 3L, 3L, 0L, 0L);
        join(repository.prepareTransitionAsync(summon));
        join(repository.claimForApplyAsync(summonId.toString(), 4L));
        join(repository.applyAsync(new BondedVesselRepository.AppliedTransition(
                summonId.toString(), 8L, npcUuid,
                new BondedVesselBindingRecord.PhysicalLocation("world", 0, 0),
                evidence, "summoned", 5L)));
        join(repository.commitAsync(summonId.toString(), 6L));
        return new Fixture(repository, bindingId, profile, npcUuid);
    }

    private static BondedVesselRepository.MutationResult join(
            PersistenceWriteQueue.WriteSubmission<BondedVesselRepository.MutationResult> submission) {
        return submission.completion().join().value();
    }

    private HydragonPersistenceTestHarness harness(String file) throws Exception {
        return new HydragonPersistenceTestHarness(tempDir.resolve(file));
    }

    private record Fixture(BondedVesselRepository repository, UUID bindingId,
                           String profileId, UUID npcUuid) { }

    private static class OfflineEvidence implements BondedVesselEvidenceAuthority {
        @Override public java.util.concurrent.CompletionStage<SourceObservation> observe(
                com.alechilles.alecstamework.api.BondedVesselTransitionContext expected) {
            return CompletableFuture.completedFuture(new SourceObservation(
                    Status.UNAVAILABLE, "offline", expected.sourceHolderEvidenceId(),
                    expected.sourceContainerPath(), expected.sourceInventorySlot(),
                    expected.sourceInventoryRevision(), expected.sourceItemId(),
                    expected.sourceItemFingerprint()));
        }
        @Override public java.util.concurrent.CompletionStage<SourceFinalization> finalizeSource(
                BondedVesselOperationRecord operation,
                com.alechilles.alecstamework.api.BondedVesselTransitionContext expected) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.INDETERMINATE, "owner-offline",
                    operation.replacementFingerprint(), "{}"));
        }
        @Override public BondedVesselProjectionValidationView validateProjection(
                BondedVesselBindingRecord binding, BondedVesselProjectionValidationRequest request) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
    }

    private static final class PresentEvidence extends OfflineEvidence {
        private final AtomicInteger finalizations = new AtomicInteger();
        @Override public java.util.concurrent.CompletionStage<SourceFinalization> finalizeSource(
                BondedVesselOperationRecord operation,
                com.alechilles.alecstamework.api.BondedVesselTransitionContext expected) {
            finalizations.incrementAndGet();
            return CompletableFuture.completedFuture(new SourceFinalization(
                    FinalizationStatus.FINALIZED, "rewritten",
                    operation.replacementFingerprint(), "{\"generation\":3}"));
        }
    }
}
