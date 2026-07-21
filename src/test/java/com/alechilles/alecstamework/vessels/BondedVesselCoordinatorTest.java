package com.alechilles.alecstamework.vessels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedVesselOperationResult;
import com.alechilles.alecstamework.api.BondedVesselProjectionStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.BondedVesselTransition;
import com.alechilles.alecstamework.api.BondedVesselTransitionContext;
import com.alechilles.alecstamework.api.BondedVesselTransitionRequest;
import com.alechilles.alecstamework.api.BondedVesselTransitionToken;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BondedVesselCoordinatorTest {
    private static final UUID BINDING_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final long GENERATION = 4L;
    private static final long PROFILE_REVISION = 7L;

    private final AtomicLong wallClock = new AtomicLong(10_000L);
    private final AtomicLong monotonic = new AtomicLong(1_000L);
    private InMemoryJournal journal;
    private FakeEvidence evidence;
    private FakeMutation mutation;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal(binding());
        evidence = new FakeEvidence(context());
        mutation = new FakeMutation();
    }

    @Test
    void preparedRepairResumesAfterRestartWithoutRetainingOldToken() {
        BondedVesselCoordinator firstRuntime = coordinator();
        BondedVesselOperationResult prepared = join(firstRuntime.prepareTransition(request()));
        assertEquals(BondedVesselOperationResult.Status.RESERVED, prepared.status());
        BondedVesselTransitionToken oldToken = prepared.token();
        assertNotNull(oldToken);

        BondedVesselCoordinator restartedRuntime = coordinator();
        assertEquals(BondedVesselOperationResult.Status.DENIED,
                restartedRuntime.claimForApply(oldToken).status(),
                "A token secret from the old process must not survive restart.");

        BondedVesselOperationResult resumed = join(restartedRuntime.resumeTransition(request()));
        assertEquals(BondedVesselOperationResult.Status.RESERVED, resumed.status());
        assertNotEquals(oldToken.reservationId(), resumed.token().reservationId());
        assertEquals(oldToken.operationId(), resumed.token().operationId(),
                "Resume must not prepare a second durable operation.");

        assertEquals(BondedVesselOperationResult.Status.APPLYING,
                restartedRuntime.claimForApply(resumed.token()).status());
        BondedVesselOperationResult committed = join(restartedRuntime.commit(resumed.token()));
        assertEquals(BondedVesselOperationResult.Status.COMMITTED, committed.status());
        assertEquals(1, mutation.applyCalls.get());
        assertEquals(GENERATION + 1L, journal.binding.generation());
        assertEquals(BondedVesselBindingRecord.LifecycleState.STORED,
                journal.binding.lifecycleState());
        assertEquals(BondedVesselOperationRecord.State.COMMITTED,
                journal.onlyOperation().state());
    }

    @Test
    void fabricatedOrStructurallyModifiedTokenCannotClaim() {
        BondedVesselCoordinator coordinator = coordinator();
        BondedVesselTransitionToken valid = join(coordinator.prepareTransition(request())).token();
        BondedVesselTransitionToken fabricated = new BondedVesselTransitionToken(
                valid.operationId(), UUID.randomUUID(), valid.bindingId(), valid.transition(),
                valid.sourceState(), valid.candidateState(), valid.sourceItemFingerprint(),
                valid.candidateItemId(), valid.candidateItemFingerprint(), valid.destination(),
                valid.expectedGeneration(), valid.candidateGeneration(),
                valid.expectedProfileRevision(), valid.expiresAtMonotonicNanos());

        assertEquals(BondedVesselOperationResult.Status.DENIED,
                coordinator.claimForApply(fabricated).status());
        assertEquals(BondedVesselOperationResult.Status.APPLYING,
                coordinator.claimForApply(valid).status());
    }

    @Test
    void everyExactSourceEvidenceFieldIsRequiredAtPreparation() {
        List<BondedVesselEvidenceAuthority.SourceObservation> mismatches = List.of(
                observation("different-holder", "inventory/main", 3, 17L,
                        "Draconic_Stone_Damaged", "source-fingerprint"),
                observation("player:owner", "inventory/offhand", 3, 17L,
                        "Draconic_Stone_Damaged", "source-fingerprint"),
                observation("player:owner", "inventory/main", 4, 17L,
                        "Draconic_Stone_Damaged", "source-fingerprint"),
                observation("player:owner", "inventory/main", 3, 18L,
                        "Draconic_Stone_Damaged", "source-fingerprint"),
                observation("player:owner", "inventory/main", 3, 17L,
                        "Different_Item", "source-fingerprint"),
                observation("player:owner", "inventory/main", 3, 17L,
                        "Draconic_Stone_Damaged", "different-fingerprint")
        );

        for (BondedVesselEvidenceAuthority.SourceObservation mismatch : mismatches) {
            InMemoryJournal isolatedJournal = new InMemoryJournal(binding());
            FakeEvidence isolatedEvidence = new FakeEvidence(context());
            isolatedEvidence.observation = mismatch;
            BondedVesselCoordinator isolated = coordinator(isolatedJournal, isolatedEvidence,
                    new FakeMutation());
            BondedVesselOperationResult result = join(isolated.prepareTransition(request()));
            assertEquals(BondedVesselOperationResult.Status.DENIED, result.status());
            assertTrue(isolatedJournal.operations.isEmpty(),
                    "Mismatched evidence must be rejected before a journal row opens.");
        }
    }

    @Test
    void sourceChangeBeforeApplyCreatesTerminalDenialAndNeverMutatesAuthority() {
        BondedVesselCoordinator coordinator = coordinator();
        BondedVesselTransitionToken token = join(coordinator.prepareTransition(request())).token();
        coordinator.claimForApply(token);
        evidence.observation = observation("player:owner", "inventory/main", 3, 18L,
                "Draconic_Stone_Damaged", "changed");

        BondedVesselOperationResult result = join(coordinator.commit(token));

        assertEquals(BondedVesselOperationResult.Status.DENIED, result.status());
        assertEquals(BondedVesselOperationRecord.State.TERMINAL_DENIED,
                journal.onlyOperation().state());
        assertEquals(BondedVesselRepository.ApplyAbsenceProof.PREPARED_NOT_CLAIMED,
                journal.lastDenialProof);
        assertEquals(0, mutation.applyCalls.get());
        assertEquals(GENERATION, journal.binding.generation());
    }

    @Test
    void sourceChangeAfterDurableClaimUsesApplyingProofAndNeverMutatesAuthority() {
        BondedVesselCoordinator coordinator = coordinator();
        BondedVesselTransitionToken token = join(coordinator.prepareTransition(request())).token();
        coordinator.claimForApply(token);
        join(journal.claim(token.operationId().toString(), wallClock.get()));
        evidence.observation = observation("player:owner", "inventory/main", 3, 18L,
                "Draconic_Stone_Damaged", "changed");

        BondedVesselOperationResult result = join(coordinator.commit(token));

        assertEquals(BondedVesselOperationResult.Status.DENIED, result.status());
        assertEquals(BondedVesselRepository.ApplyAbsenceProof
                        .APPLYING_SOURCE_REVALIDATION_FAILED_BEFORE_MUTATION,
                journal.lastDenialProof);
        assertEquals(0, mutation.applyCalls.get());
    }

    @Test
    void appliedOperationRemainsNonterminalUntilExactSourceClosureRecovers() {
        BondedVesselCoordinator coordinator = coordinator();
        BondedVesselTransitionToken token = join(coordinator.prepareTransition(request())).token();
        coordinator.claimForApply(token);
        evidence.finalizationStatus = BondedVesselEvidenceAuthority.FinalizationStatus.INDETERMINATE;

        BondedVesselOperationResult applied = join(coordinator.commit(token));
        assertEquals(BondedVesselOperationResult.Status.APPLIED, applied.status());
        assertEquals(BondedVesselOperationRecord.State.APPLIED, journal.onlyOperation().state());

        BondedVesselCoordinator restarted = coordinator();
        evidence.finalizationStatus = BondedVesselEvidenceAuthority.FinalizationStatus.ALREADY_FINALIZED;
        BondedVesselCoordinator.RecoveryReport report = join(restarted.recoverPending());

        assertEquals(1, report.scanned());
        assertEquals(1, report.committed());
        assertEquals(BondedVesselOperationRecord.State.COMMITTED,
                journal.onlyOperation().state());
        assertEquals(1, mutation.applyCalls.get(),
                "Recovery of APPLIED must not repeat the profile/world mutation.");
    }

    @Test
    void unavailableEvidenceIsIndeterminateAndCannotAuthorizeTerminalDenial() {
        BondedVesselCoordinator coordinator = coordinator();
        BondedVesselTransitionToken token = join(coordinator.prepareTransition(request())).token();
        coordinator.claimForApply(token);
        evidence.observation = new BondedVesselEvidenceAuthority.SourceObservation(
                BondedVesselEvidenceAuthority.Status.UNAVAILABLE,
                "offline-inventory-not-loaded", "player:owner", "inventory/main", 3, 17L,
                "Draconic_Stone_Damaged", "source-fingerprint");

        BondedVesselOperationResult result = join(coordinator.commit(token));

        assertEquals(BondedVesselOperationResult.Status.UNAVAILABLE, result.status());
        assertEquals(BondedVesselOperationRecord.State.PREPARED, journal.onlyOperation().state());
        assertEquals(0, mutation.applyCalls.get());
    }

    private BondedVesselCoordinator coordinator() {
        return coordinator(journal, evidence, mutation);
    }

    private BondedVesselCoordinator coordinator(InMemoryJournal selectedJournal,
                                                FakeEvidence selectedEvidence,
                                                FakeMutation selectedMutation) {
        return new BondedVesselCoordinator(
                selectedJournal,
                (binding, request, nowMs) -> new BondedVesselTransitionPlanner.Plan(
                        BondedVesselState.STORED, BondedVesselProjectionStatus.PRESENT,
                        "*Draconic_Stone_State_Filled", "replacement-fingerprint",
                        0L, "{\"configRevision\":3}"),
                selectedEvidence,
                selectedMutation,
                null,
                Runnable::run,
                wallClock::get,
                monotonic::get,
                30_000L,
                16);
    }

    private BondedVesselBindingRecord binding() {
        return new BondedVesselBindingRecord(
                BINDING_ID.toString(), "profile-dragon-1", GENERATION,
                "Draconic_Stone", 3L, BondedVesselBindingRecord.LifecycleState.DEAD,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT, OWNER_ID,
                PROFILE_REVISION, null, null, 0L, "Draconic_Stone_Damaged",
                "{\"holder\":\"player:owner\"}", null, null,
                1L, 1_000L, 1_000L, 0L);
    }

    private BondedVesselTransitionRequest request() {
        return new BondedVesselTransitionRequest(
                "hydragon", "repair:profile-dragon-1:4", OWNER_ID, BINDING_ID,
                GENERATION, PROFILE_REVISION,
                BondedVesselTransition.REPAIR_DEAD_TO_STORED, context());
    }

    private BondedVesselTransitionContext context() {
        return new BondedVesselTransitionContext(
                "Draconic_Stone_Damaged", "player:owner", "inventory/main",
                3, 17L, "source-fingerprint", null, null);
    }

    private BondedVesselEvidenceAuthority.SourceObservation observation(
            String holder,
            String container,
            int slot,
            long revision,
            String itemId,
            String fingerprint
    ) {
        return new BondedVesselEvidenceAuthority.SourceObservation(
                BondedVesselEvidenceAuthority.Status.CHANGED, "source-changed",
                holder, container, slot, revision, itemId, fingerprint);
    }

    private static <T> T join(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class FakeEvidence implements BondedVesselEvidenceAuthority {
        private SourceObservation observation;
        private FinalizationStatus finalizationStatus = FinalizationStatus.FINALIZED;

        private FakeEvidence(BondedVesselTransitionContext context) {
            this.observation = new SourceObservation(
                    Status.EXACT, "source-exact", context.sourceHolderEvidenceId(),
                    context.sourceContainerPath(), context.sourceInventorySlot(),
                    context.sourceInventoryRevision(), context.sourceItemId(),
                    context.sourceItemFingerprint());
        }

        @Override
        public CompletionStage<SourceObservation> observe(BondedVesselTransitionContext expected) {
            return CompletableFuture.completedFuture(observation);
        }

        @Override
        public CompletionStage<SourceFinalization> finalizeSource(
                BondedVesselOperationRecord operation,
                BondedVesselTransitionContext expected
        ) {
            return CompletableFuture.completedFuture(new SourceFinalization(
                    finalizationStatus, "source-finalization-" + finalizationStatus.name().toLowerCase(),
                    "replacement-fingerprint", "{\"finalized\":true}"));
        }

        @Override
        public BondedVesselProjectionValidationView validateProjection(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionValidationRequest request
        ) {
            return new BondedVesselProjectionValidationView(
                    UUID.fromString(binding.bindingId()),
                    BondedVesselProjectionValidationStatus.CONSISTENT,
                    "projection-consistent", binding.generation(), true);
        }
    }

    private static final class FakeMutation implements BondedVesselMutationAuthority {
        private final AtomicInteger applyCalls = new AtomicInteger();

        @Override
        public CompletionStage<ApplyOutcome> apply(
                BondedVesselOperationRecord operation,
                BondedVesselBindingRecord binding,
                boolean recovery
        ) {
            applyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new ApplyOutcome(
                    Status.APPLIED, "repair-applied", PROFILE_REVISION + 1L,
                    null, null, "{\"profileApplied\":true}"));
        }
    }

    private static final class InMemoryJournal implements BondedVesselJournal {
        private BondedVesselBindingRecord binding;
        private final Map<String, BondedVesselOperationRecord> operations = new HashMap<>();
        private final Map<String, String> origins = new HashMap<>();
        private BondedVesselRepository.ApplyAbsenceProof lastDenialProof;

        private InMemoryJournal(BondedVesselBindingRecord binding) {
            this.binding = binding;
        }

        @Override
        public BondedVesselBindingRecord findBinding(String bindingId) {
            return binding.bindingId().equals(bindingId) ? binding : null;
        }

        @Override
        public BondedVesselBindingRecord findBindingByProfile(String profileId) {
            return binding.profileId().equals(profileId) ? binding : null;
        }

        @Override
        public BondedVesselOperationRecord findOperation(String operationId) {
            return operations.get(operationId);
        }

        @Override
        public BondedVesselOperationRecord findOperationByOrigin(String namespace, String key) {
            return operations.get(origins.get(namespace + "\u0000" + key));
        }

        @Override
        public List<BondedVesselOperationRecord> loadRecoverable(int limit) {
            List<BondedVesselOperationRecord> recoverable = new ArrayList<>();
            for (BondedVesselOperationRecord operation : operations.values()) {
                if (operation.state() == BondedVesselOperationRecord.State.PREPARED
                        || operation.state() == BondedVesselOperationRecord.State.APPLYING
                        || operation.state() == BondedVesselOperationRecord.State.APPLIED
                        || operation.state() == BondedVesselOperationRecord.State.QUARANTINED) {
                    recoverable.add(operation);
                }
            }
            return List.copyOf(recoverable.subList(0, Math.min(limit, recoverable.size())));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> prepare(
                BondedVesselOperationRecord operation
        ) {
            String origin = operation.callerNamespace() + "\u0000" + operation.idempotencyKey();
            String existingId = origins.get(origin);
            if (existingId != null) {
                return completed(result(BondedVesselRepository.Status.IDEMPOTENT,
                        operations.get(existingId), "operation-exists"));
            }
            operations.put(operation.operationId(), operation);
            origins.put(origin, operation.operationId());
            binding = copyBinding(binding, binding.generation(), binding.lifecycleState(),
                    binding.expectedProfileRevision(), operation.operationId());
            return completed(result(BondedVesselRepository.Status.PREPARED, operation, null));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> claim(
                String operationId,
                long nowMs
        ) {
            BondedVesselOperationRecord operation = operations.get(operationId);
            if (operation.state() == BondedVesselOperationRecord.State.APPLYING) {
                return completed(result(BondedVesselRepository.Status.IDEMPOTENT, operation,
                        "already-claimed"));
            }
            BondedVesselOperationRecord applying = copyOperation(
                    operation, BondedVesselOperationRecord.State.APPLYING, null, nowMs);
            operations.put(operationId, applying);
            binding = copyBinding(binding, binding.generation(), operation.applyingLifecycleState(),
                    binding.expectedProfileRevision(), operationId);
            return completed(result(BondedVesselRepository.Status.APPLYING, applying, null));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> apply(
                BondedVesselRepository.AppliedTransition transition
        ) {
            BondedVesselOperationRecord operation = operations.get(transition.operationId());
            if (operation.state() == BondedVesselOperationRecord.State.APPLIED
                    || operation.state() == BondedVesselOperationRecord.State.COMMITTED) {
                return completed(result(BondedVesselRepository.Status.IDEMPOTENT, operation,
                        "already-applied"));
            }
            BondedVesselOperationRecord applied = copyOperation(
                    operation, BondedVesselOperationRecord.State.APPLIED,
                    transition.reasonCode(), transition.appliedAtMs());
            operations.put(operation.operationId(), applied);
            binding = copyBinding(binding, operation.candidateGeneration(),
                    operation.targetLifecycleState(), transition.committedProfileRevision(),
                    operation.operationId());
            return completed(result(BondedVesselRepository.Status.APPLIED, applied, null));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> commit(
                String operationId,
                long nowMs
        ) {
            BondedVesselOperationRecord operation = operations.get(operationId);
            if (operation.state() == BondedVesselOperationRecord.State.COMMITTED) {
                return completed(result(BondedVesselRepository.Status.IDEMPOTENT, operation,
                        "already-committed"));
            }
            BondedVesselOperationRecord committed = copyOperation(
                    operation, BondedVesselOperationRecord.State.COMMITTED,
                    operation.reasonCode(), nowMs);
            operations.put(operationId, committed);
            binding = copyBinding(binding, binding.generation(), binding.lifecycleState(),
                    binding.expectedProfileRevision(), null);
            return completed(result(BondedVesselRepository.Status.COMMITTED, committed, null));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> cancel(
                String operationId,
                String reason,
                long nowMs
        ) {
            BondedVesselOperationRecord operation = operations.get(operationId);
            BondedVesselOperationRecord canceled = copyOperation(
                    operation, BondedVesselOperationRecord.State.CANCELED, reason, nowMs);
            operations.put(operationId, canceled);
            binding = copyBinding(binding, binding.generation(), operation.priorLifecycleState(),
                    binding.expectedProfileRevision(), null);
            return completed(result(BondedVesselRepository.Status.CANCELED, canceled, reason));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> denyBeforeApply(
                String operationId,
                String reason,
                BondedVesselRepository.ApplyAbsenceProof proof,
                long nowMs
        ) {
            lastDenialProof = proof;
            BondedVesselOperationRecord operation = operations.get(operationId);
            BondedVesselOperationRecord denied = copyOperation(
                    operation, BondedVesselOperationRecord.State.TERMINAL_DENIED, reason, nowMs);
            operations.put(operationId, denied);
            binding = copyBinding(binding, binding.generation(), operation.priorLifecycleState(),
                    binding.expectedProfileRevision(), null);
            return completed(result(BondedVesselRepository.Status.TERMINAL_DENIED, denied, reason));
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> quarantine(
                String operationId,
                String reason,
                long nowMs
        ) {
            BondedVesselOperationRecord operation = operations.get(operationId);
            BondedVesselOperationRecord quarantined = copyOperation(
                    operation, BondedVesselOperationRecord.State.QUARANTINED, reason, nowMs);
            operations.put(operationId, quarantined);
            return completed(result(BondedVesselRepository.Status.QUARANTINED,
                    quarantined, reason));
        }

        private BondedVesselOperationRecord onlyOperation() {
            assertEquals(1, operations.size());
            return operations.values().iterator().next();
        }

        private BondedVesselRepository.MutationResult result(
                BondedVesselRepository.Status status,
                BondedVesselOperationRecord operation,
                String reason
        ) {
            return new BondedVesselRepository.MutationResult(status, binding, operation, reason);
        }

        private static <T> CompletionStage<T> completed(T value) {
            return CompletableFuture.completedFuture(value);
        }

        private static BondedVesselBindingRecord copyBinding(
                BondedVesselBindingRecord source,
                long generation,
                BondedVesselBindingRecord.LifecycleState lifecycle,
                long profileRevision,
                String activeOperationId
        ) {
            return new BondedVesselBindingRecord(
                    source.bindingId(), source.profileId(), generation, source.configId(),
                    source.configRevision(), lifecycle, source.itemProjectionStatus(),
                    source.ownerUuid(), profileRevision, source.activeNpcUuid(),
                    source.activeLocation(), source.cooldownUntilMs(), source.lastItemId(),
                    source.itemEvidenceJson(), activeOperationId, source.diagnosticReason(),
                    source.rowRevision() + 1L, source.createdAtMs(), source.updatedAtMs() + 1L,
                    lifecycle == BondedVesselBindingRecord.LifecycleState.RELEASED
                            ? source.updatedAtMs() + 1L : source.releasedAtMs());
        }

        private static BondedVesselOperationRecord copyOperation(
                BondedVesselOperationRecord source,
                BondedVesselOperationRecord.State state,
                String reason,
                long nowMs
        ) {
            return new BondedVesselOperationRecord(
                    source.operationId(), source.callerNamespace(), source.idempotencyKey(),
                    source.correlationId(), source.bindingId(), source.profileId(), source.action(),
                    state, source.priorGeneration(), source.candidateGeneration(),
                    source.expectedProfileRevision(), source.configId(), source.configRevision(),
                    source.priorLifecycleState(), source.applyingLifecycleState(),
                    source.targetLifecycleState(), source.priorProjectionStatus(),
                    source.targetProjectionStatus(), source.priorCooldownUntilMs(),
                    source.targetCooldownUntilMs(), source.sourceItemId(), source.targetItemId(),
                    source.sourceFingerprint(), source.replacementFingerprint(),
                    source.sourceContextJson(), source.policySnapshotJson(),
                    source.populationOperationId(), source.actualNpcUuid(), reason,
                    state.name(), source.leaseExpiresAtMs(), source.createdAtMs(), nowMs,
                    state == BondedVesselOperationRecord.State.APPLIED
                            || state == BondedVesselOperationRecord.State.COMMITTED ? nowMs : 0L,
                    state.isTerminal() ? nowMs : 0L);
        }
    }
}
