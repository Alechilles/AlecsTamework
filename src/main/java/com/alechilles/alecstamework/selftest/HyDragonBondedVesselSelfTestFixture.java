package com.alechilles.alecstamework.selftest;

import com.alechilles.alecstamework.api.BondedVesselProjectionValidationRequest;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationStatus;
import com.alechilles.alecstamework.api.BondedVesselProjectionValidationView;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselRepository;
import com.alechilles.alecstamework.vessels.BondedVesselCoordinator;
import com.alechilles.alecstamework.vessels.BondedVesselEvidenceAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselJournal;
import com.alechilles.alecstamework.vessels.BondedVesselMutationAuthority;
import com.alechilles.alecstamework.vessels.BondedVesselTransitionPlanner;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** In-memory generation-fencing fixture; it never inspects a player inventory. */
final class HyDragonBondedVesselSelfTestFixture {
    private static final UUID BINDING_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID OWNER_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final long GENERATION = 7L;

    private HyDragonBondedVesselSelfTestFixture() {
    }

    static ApiSelfTestAssertion run() {
        try {
            BondedVesselBindingRecord binding = binding();
            BondedVesselCoordinator coordinator = new BondedVesselCoordinator(
                    new ReadOnlyJournal(binding),
                    unusedPlanner(),
                    new ProjectionEvidence(),
                    unusedMutation(),
                    null,
                    Runnable::run);
            BondedVesselProjectionValidationView current = coordinator.validateProjection(request(GENERATION));
            BondedVesselProjectionValidationView stale = coordinator.validateProjection(request(GENERATION - 1L));
            boolean passed = current.status() == BondedVesselProjectionValidationStatus.CONSISTENT
                    && current.authoritative()
                    && stale.status() == BondedVesselProjectionValidationStatus.STALE_GENERATION
                    && stale.authoritative()
                    && stale.canonicalGeneration() == GENERATION;
            return new ApiSelfTestAssertion(
                    "isolated bonded vessel rejects stale generation",
                    passed,
                    "current=" + current.status() + " stale=" + stale.status()
                            + " canonicalGeneration=" + stale.canonicalGeneration());
        } catch (RuntimeException failure) {
            return new ApiSelfTestAssertion(
                    "isolated bonded vessel rejects stale generation",
                    false,
                    failure.getClass().getSimpleName() + ": isolated-fixture-failed");
        }
    }

    private static BondedVesselProjectionValidationRequest request(long generation) {
        return new BondedVesselProjectionValidationRequest(
                BINDING_ID,
                generation,
                BondedVesselProjectionValidationRequest.ProjectionKind.ITEM,
                "self-test-fingerprint");
    }

    private static BondedVesselBindingRecord binding() {
        return new BondedVesselBindingRecord(
                BINDING_ID.toString(),
                "self-test-dragon-profile",
                GENERATION,
                "HyDragon_SelfTest_Vessel",
                1L,
                BondedVesselBindingRecord.LifecycleState.STORED,
                BondedVesselBindingRecord.ItemProjectionStatus.PRESENT,
                OWNER_ID,
                2L,
                null,
                null,
                0L,
                "HyDragon_SelfTest_Stone_Filled",
                "{\"fixture\":true}",
                null,
                null,
                1L,
                1L,
                1L,
                0L);
    }

    private static BondedVesselTransitionPlanner unusedPlanner() {
        return (binding, request, nowMs) -> {
            throw new IllegalStateException("The projection fixture must not plan a transition.");
        };
    }

    private static BondedVesselMutationAuthority unusedMutation() {
        return (operation, binding, recovery) -> CompletableFuture.failedFuture(
                new IllegalStateException("The projection fixture must not mutate authority."));
    }

    private static final class ProjectionEvidence implements BondedVesselEvidenceAuthority {
        @Override
        public CompletionStage<SourceObservation> observe(
                com.alechilles.alecstamework.api.BondedVesselTransitionContext expected) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The projection fixture must not read inventory evidence."));
        }

        @Override
        public CompletionStage<SourceFinalization> finalizeSource(
                BondedVesselOperationRecord operation,
                com.alechilles.alecstamework.api.BondedVesselTransitionContext expected) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The projection fixture must not finalize an item."));
        }

        @Override
        public BondedVesselProjectionValidationView validateProjection(
                BondedVesselBindingRecord binding,
                BondedVesselProjectionValidationRequest request) {
            return new BondedVesselProjectionValidationView(
                    request.bindingId(),
                    BondedVesselProjectionValidationStatus.CONSISTENT,
                    "self-test-projection-consistent",
                    binding.generation(),
                    true);
        }
    }

    private static final class ReadOnlyJournal implements BondedVesselJournal {
        private final BondedVesselBindingRecord binding;

        private ReadOnlyJournal(BondedVesselBindingRecord binding) {
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
            return null;
        }

        @Override
        public BondedVesselOperationRecord findOperationByOrigin(String callerNamespace, String idempotencyKey) {
            return null;
        }

        @Override
        public List<BondedVesselOperationRecord> loadRecoverable(int limit) {
            return List.of();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> prepare(BondedVesselOperationRecord operation) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> claim(String operationId, long nowMs) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> apply(
                BondedVesselRepository.AppliedTransition transition) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> finalizeItemProjection(
                String operationId,
                BondedVesselBindingRecord.ItemProjectionStatus projectionStatus,
                String itemEvidenceJson,
                String reason,
                long nowMs) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> commit(String operationId, long nowMs) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> cancel(
                String operationId, String reason, long nowMs) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> denyBeforeApply(
                String operationId,
                String reason,
                BondedVesselRepository.ApplyAbsenceProof proof,
                long nowMs) {
            return unsupported();
        }

        @Override
        public CompletionStage<BondedVesselRepository.MutationResult> quarantine(
                String operationId, String reason, long nowMs) {
            return unsupported();
        }

        private CompletionStage<BondedVesselRepository.MutationResult> unsupported() {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The projection fixture is read-only."));
        }
    }
}
