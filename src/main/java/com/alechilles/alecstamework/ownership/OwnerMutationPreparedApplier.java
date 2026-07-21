package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies one durable owner admission against freshly resolved world state. */
final class OwnerMutationPreparedApplier {
    private final OwnerComponentMutationService mutationService;
    private final CompanionPopulationAdmissionCoordinator companionCoordinator;
    private final OwnerMutationTerminality terminality;
    private final OwnerMutationCompensationService compensationService;
    private final OwnerMutationIdentityLifecycle identityLifecycle;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final ClaimLookupMetrics lookupMetrics;

    OwnerMutationPreparedApplier(
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull CompanionPopulationAdmissionCoordinator companionCoordinator,
            @Nonnull OwnerMutationTerminality terminality,
            @Nonnull OwnerMutationIdentityLifecycle identityLifecycle,
            @Nonnull CompanionAdmissionPolicyResolver policyResolver,
            @Nonnull ClaimLookupMetrics lookupMetrics
    ) {
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.companionCoordinator = Objects.requireNonNull(
                companionCoordinator, "companionCoordinator");
        this.terminality = Objects.requireNonNull(terminality, "terminality");
        this.compensationService = new OwnerMutationCompensationService(
                companionCoordinator, mutationService, terminality);
        this.identityLifecycle = Objects.requireNonNull(identityLifecycle, "identityLifecycle");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
    }

    void apply(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
               @Nullable UUID newOwnerId,
               @Nullable String newOwnerName,
               @Nonnull OwnerMutationScheduler.MutationCallbacks callbacks,
               @Nonnull PreparedCompanionPopulationAdmission prepared) {
        World world = snapshot.world();
        UUID npcUuid = snapshot.npcUuid();
        Ref<EntityStore> liveRef = world.getEntityRef(npcUuid);
        Store<EntityStore> liveStore = world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (liveRef == null || !liveRef.isValid() || liveStore == null) {
            cancelPrepared(prepared, "owner-mutation-target-unavailable");
            terminality.denied(callbacks,
                    "owner-mutation-target-unavailable",
                    prepared.ownerAdmission().decision()
            );
            return;
        }
        OwnerPopulationOperation operation = prepared.ownerAdmission().plan().transition().operation();
        boolean claimPolicyRelevant = prepared.claimReservation().topologyCheckRequired()
                || prepared.claimReservation().reservedSlots() > 0L;
        final boolean claimed;
        try {
            CompanionAdmissionPolicyResolver.Policy currentPolicy =
                    policyResolver.resolve(operation, claimPolicyRelevant);
            ClaimLookupSession refreshedSession = new ClaimLookupSession(
                    currentPolicy.claimContext(),
                    currentPolicy.claimLimitPerChunk() > 0,
                    lookupMetrics
            );
            claimed = companionCoordinator.claimForApply(
                    prepared,
                    currentPolicy.settingsRevision(),
                    refreshedSession
            );
        } catch (RuntimeException | LinkageError failure) {
            cancelPrepared(prepared, "owner-mutation-policy-refresh-failed");
            terminality.denied(callbacks, "owner-mutation-policy-refresh-failed",
                    prepared.ownerAdmission().decision());
            return;
        }
        if (!claimed) {
            cancelPrepared(prepared, "companion-population-reservation-invalid");
            terminality.denied(callbacks,
                    "companion-population-reservation-invalid",
                    prepared.ownerAdmission().decision()
            );
            return;
        }
        String profileId = prepared.ownerAdmission().plan().transition().profileId();
        OwnerMutationContext mutationContext = new OwnerMutationContext(liveRef, liveStore, npcUuid);
        boolean continuationPrepared;
        try {
            continuationPrepared = callbacks.beforeApply(profileId, mutationContext);
        } catch (RuntimeException | LinkageError exception) {
            continuationPrepared = false;
        }
        if (!continuationPrepared) {
            cancelPrepared(prepared, "owner-mutation-continuation-rejected");
            terminality.denied(callbacks,
                    "owner-mutation-continuation-rejected",
                    prepared.ownerAdmission().decision()
            );
            return;
        }
        OwnerComponentMutationService.WriteResult result;
        try {
            result = mutationService.writeClaimedImmediate(
                    liveRef,
                    liveStore,
                    prepared.ownerAdmission(),
                    snapshot.expectedLiveOwnerId(),
                    newOwnerId,
                    newOwnerName
            );
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_live_write_ambiguous");
            terminality.durabilityDegraded(callbacks, "owner-mutation-live-write-ambiguous");
            return;
        }
        if (!result.applied()) {
            compensationService.handleFailedWrite(
                    world, npcUuid, profileId, prepared, callbacks, mutationContext, result
            );
            return;
        }
        boolean identityMapped = identityLifecycle.remapLive(
                profileId, snapshot.baselineNpcUuid(), npcUuid
        );
        try {
            callbacks.onApplied(prepared.ownerAdmission().decision(), profileId, mutationContext);
        } catch (RuntimeException | LinkageError failure) {
            terminality.appliedContinuationFailed(failure);
            terminality.durabilityDegraded(
                    callbacks, "owner-mutation-applied-continuation-failed"
            );
            return;
        }
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = companionCoordinator.commitAsync(prepared);
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_commit_start_failed");
            terminality.durabilityDegraded(callbacks, "owner-mutation-finalize-failed");
            return;
        }
        if (completion == null) {
            terminality.degrade("owner_mutation_commit_stage_missing");
            terminality.durabilityDegraded(callbacks, "owner-mutation-finalize-failed");
            return;
        }
        completion.whenComplete((commit, failure) -> {
            boolean identityDurable = identityLifecycle.markLiveDurableIfCommitted(
                    identityMapped, commit, profileId, npcUuid
            );
            LeaseBoundWorldDispatcher.execute(world, () -> {
                    if (failure != null || commit == null || !commit.committed() || !identityDurable) {
                        terminality.durabilityDegraded(callbacks,
                                !identityMapped
                                        ? "owner-mutation-live-identity-remap-failed"
                                        : !identityDurable
                                        ? "owner-mutation-identity-cache-degraded"
                                        : commit == null
                                        ? "owner-mutation-finalize-failed"
                                        : commit.reason()
                        );
                    } else {
                        try {
                            callbacks.onPopulationCommitted(commit);
                        } catch (RuntimeException | LinkageError ignored) {
                            // The legacy completion callback must still be offered.
                        }
                        if (commit.ownerCommit() != null) {
                            try {
                                callbacks.onCommitted(commit.ownerCommit());
                            } catch (RuntimeException | LinkageError ignored) {
                                // Population accounting is already committed.
                            }
                        }
                    }
                }, () -> callbacks.onWorldDispatchRejected(
                        "owner-mutation-world-unavailable", true, commit
                ));
        });
    }

    private void cancelPrepared(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        identityLifecycle.cancelPrepared(prepared, reason);
    }
}
