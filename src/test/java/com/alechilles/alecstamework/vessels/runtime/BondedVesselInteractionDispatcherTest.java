package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BondedVesselInteractionDispatcherTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID NPC = UUID.randomUUID();

    @Test
    void storedClickDispatchesOneGenerationFencedSummon() {
        FakeApi api = new FakeApi(BondedVesselState.STORED);
        BondedVesselInteractionDispatcher dispatcher = new BondedVesselInteractionDispatcher(api);

        var result = dispatcher.toggle(new BondedVesselInteractionDispatcher.Request(
                ACTOR, 2, "stored-stone",
                new PopulationAdmissionLocation("world", 4, -2)))
                .toCompletableFuture().join();

        assertEquals(BondedVesselInteractionDispatcher.Status.COMMITTED, result.status());
        assertEquals(BondedVesselTransition.SUMMON, result.transition());
        assertEquals(BondedVesselTransition.SUMMON, api.prepared.transition());
        assertEquals("toggle:" + BINDING + ":3", api.prepared.idempotencyKey());
        assertEquals(new PopulationAdmissionLocation("world", 4, -2),
                api.prepared.context().destination());
        assertNull(api.prepared.context().expectedNpcUuid());
        assertEquals(1, api.commitCalls);
    }

    @Test
    void activeClickDispatchesStoreForOnlyTheCanonicalLiveProjection() {
        FakeApi api = new FakeApi(BondedVesselState.ACTIVE);
        BondedVesselInteractionDispatcher dispatcher = new BondedVesselInteractionDispatcher(api);

        var result = dispatcher.toggle(new BondedVesselInteractionDispatcher.Request(
                ACTOR, 2, "active-stone", null)).toCompletableFuture().join();

        assertEquals(BondedVesselInteractionDispatcher.Status.COMMITTED, result.status());
        assertEquals(BondedVesselTransition.STORE, result.transition());
        assertEquals(NPC, api.prepared.context().expectedNpcUuid());
        assertNull(api.prepared.context().destination());
        assertEquals(2, api.locatorCalls);
    }

    @Test
    void unavailableExactEvidenceNeverRunsLegacyOrPreparesTransition() {
        FakeApi api = new FakeApi(null);
        BondedVesselInteractionDispatcher dispatcher = new BondedVesselInteractionDispatcher(api);

        var result = dispatcher.toggle(new BondedVesselInteractionDispatcher.Request(
                ACTOR, 2, "stored-stone",
                new PopulationAdmissionLocation("world", 0, 0)))
                .toCompletableFuture().join();

        assertEquals(BondedVesselInteractionDispatcher.Status.UNAVAILABLE, result.status());
        assertNull(api.prepared);
        assertEquals(0, api.commitCalls);
    }

    private static final class FakeApi implements BondedVesselsApi {
        private final BondedVesselState authoritativeState;
        private BondedVesselTransitionRequest prepared;
        private int locatorCalls;
        private int commitCalls;

        private FakeApi(BondedVesselState authoritativeState) {
            this.authoritativeState = authoritativeState;
        }

        @Override
        public CompletionStage<BondedVesselHeldItemLocatorResult> resolveHeldItemLocator(
                BondedVesselHeldItemLocatorRequest request) {
            locatorCalls++;
            if (authoritativeState == null) {
                return CompletableFuture.completedFuture(
                        BondedVesselHeldItemLocatorResult.unavailable(request));
            }
            if (request.requiredState() != authoritativeState) {
                return CompletableFuture.completedFuture(new BondedVesselHeldItemLocatorResult(
                        BondedVesselHeldItemProjectionStatus.SOURCE_CHANGED,
                        "held-slot-vessel-state-mismatch", request, null, null, false));
            }
            BondedVesselSourceItemEvidence evidence = new BondedVesselSourceItemEvidence(
                    request.expectedItemId(), request.holderEvidenceId(), request.containerPath(),
                    request.inventorySlot(), 3L, "source-fingerprint");
            BondedVesselView vessel = new BondedVesselView(
                    BINDING, "profile-1", ACTOR, "dragon-stone", authoritativeState,
                    3L, 5L, null, BondedVesselProjectionStatus.PRESENT,
                    authoritativeState == BondedVesselState.ACTIVE ? NPC : null, 10L);
            return CompletableFuture.completedFuture(new BondedVesselHeldItemLocatorResult(
                    BondedVesselHeldItemProjectionStatus.VALID, "held-slot-exact", request,
                    evidence, vessel, true));
        }

        @Override
        public CompletionStage<BondedVesselOperationResult> prepareTransition(
                BondedVesselTransitionRequest request) {
            prepared = request;
            return CompletableFuture.completedFuture(open(
                    BondedVesselOperationResult.Status.RESERVED, token(request)));
        }

        @Override
        public BondedVesselOperationResult claimForApply(BondedVesselTransitionToken token) {
            return open(BondedVesselOperationResult.Status.APPLYING, token);
        }

        @Override
        public CompletionStage<BondedVesselOperationResult> commit(BondedVesselTransitionToken token) {
            commitCalls++;
            return CompletableFuture.completedFuture(new BondedVesselOperationResult(
                    BondedVesselOperationResult.Status.COMMITTED, "committed",
                    token.operationId(), null, token.bindingId(), "profile-1",
                    token.candidateGeneration(), 6L, null, token.candidateState(),
                    token.candidateItemId(), token.candidateItemFingerprint()));
        }

        private BondedVesselTransitionToken token(BondedVesselTransitionRequest request) {
            BondedVesselState candidate = request.transition() == BondedVesselTransition.SUMMON
                    ? BondedVesselState.ACTIVE : BondedVesselState.STORED;
            return new BondedVesselTransitionToken(
                    UUID.randomUUID(), UUID.randomUUID(), request.bindingId(),
                    request.transition(), authoritativeState, candidate,
                    request.context().sourceItemFingerprint(),
                    candidate == BondedVesselState.ACTIVE ? "active-stone" : "stored-stone",
                    "candidate-fingerprint", request.context().destination(),
                    request.expectedGeneration(), request.expectedGeneration() + 1L,
                    request.expectedProfileRevision(), Long.MAX_VALUE);
        }

        private BondedVesselOperationResult open(
                BondedVesselOperationResult.Status status,
                BondedVesselTransitionToken token) {
            return new BondedVesselOperationResult(
                    status, "open", token.operationId(), token, token.bindingId(), "profile-1",
                    token.expectedGeneration(), token.expectedProfileRevision(), null,
                    token.candidateState(), token.candidateItemId(),
                    token.candidateItemFingerprint());
        }

        @Override public Optional<BondedVesselView> getByBindingId(UUID id) { return Optional.empty(); }
        @Override public Optional<BondedVesselView> getByProfileId(String id) { return Optional.empty(); }
        @Override public BondedVesselReadinessView readiness() { return BondedVesselReadinessView.unavailable(); }
        @Override public BondedVesselProjectionValidationView validateProjection(
                BondedVesselProjectionValidationRequest request) {
            return BondedVesselProjectionValidationView.unavailable(request.bindingId());
        }
        @Override public CompletionStage<BondedVesselOperationResult> resumeTransition(
                BondedVesselTransitionRequest request) {
            return CompletableFuture.completedFuture(BondedVesselOperationResult.unavailable("unused"));
        }
        @Override public CompletionStage<BondedVesselOperationResult> cancel(
                BondedVesselTransitionToken token) {
            return CompletableFuture.completedFuture(BondedVesselOperationResult.unavailable("unused"));
        }
        @Override public CompletionStage<Optional<BondedVesselOperationView>> findOperation(
                String callerNamespace, String idempotencyKey) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
