package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/** Defers owner mutations whose caller cannot wait for SQLite without blocking a world thread. */
public final class OwnerMutationScheduler {
    private final OwnerPopulationIndex index;
    private final OwnerComponentMutationService mutationService;
    private final OwnerMutationSnapshotResolver snapshotResolver;
    private final OwnerMutationIdentityLifecycle identityLifecycle;
    private final CompanionPopulationAdmissionCoordinator companionCoordinator;
    private final OwnerMutationTerminality terminality;
    private final OwnerMutationCompensationService compensationService;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final ClaimLookupMetrics lookupMetrics;
    public OwnerMutationScheduler(@Nonnull OwnerPopulationIndex index,
                                  @Nonnull CompanionIdentityResolver identityResolver,
                                  @Nonnull OwnerPopulationAdmissionCoordinator coordinator,
                                  @Nonnull OwnerComponentMutationService mutationService,
                                  @Nonnull CompanionPopulationAdmissionCoordinator companionCoordinator,
                                  @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
                                  @Nonnull ClaimProviderRegistry claimProviderRegistry) {
        this(index, identityResolver, coordinator, mutationService, companionCoordinator,
                claimOccupancyIndex, claimProviderRegistry, new ClaimLookupMetrics());
    }
    OwnerMutationScheduler(@Nonnull OwnerPopulationIndex index,
                           @Nonnull CompanionIdentityResolver identityResolver,
                           @Nonnull OwnerPopulationAdmissionCoordinator coordinator,
                           @Nonnull OwnerComponentMutationService mutationService,
                           @Nonnull CompanionPopulationAdmissionCoordinator companionCoordinator,
                           @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
                           @Nonnull ClaimProviderRegistry claimProviderRegistry,
                           @Nonnull ClaimLookupMetrics lookupMetrics) {
        this.index = Objects.requireNonNull(index, "index");
        Objects.requireNonNull(identityResolver, "identityResolver");
        Objects.requireNonNull(coordinator, "coordinator");
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.snapshotResolver = new OwnerMutationSnapshotResolver(identityResolver);
        this.companionCoordinator = Objects.requireNonNull(companionCoordinator, "companionCoordinator");
        this.terminality = new OwnerMutationTerminality(this.companionCoordinator);
        this.compensationService = new OwnerMutationCompensationService(
                this.companionCoordinator, this.mutationService, this.terminality
        );
        this.identityLifecycle = new OwnerMutationIdentityLifecycle(
                identityResolver, snapshotResolver, terminality
        );
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                Objects.requireNonNull(claimOccupancyIndex, "claimOccupancyIndex"),
                Objects.requireNonNull(claimProviderRegistry, "claimProviderRegistry")
        );
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
    }
    /** Snapshots live state and starts durable preparation without blocking the current world. */
    public boolean schedule(@Nonnull Ref<EntityStore> npcRef,
                            @Nonnull Store<EntityStore> store,
                            @Nullable UUID newOwnerId,
                            @Nullable String newOwnerName,
                            @Nonnull CompanionLifecycleState lifecycleState,
                            @Nonnull OwnerPopulationOperation operation,
                            boolean force,
                            @Nonnull String idempotencyKey,
                            @Nullable MutationCallbacks callbacks) {
        return scheduleInternal(
                npcRef,
                store,
                null,
                null,
                false,
                null,
                newOwnerId,
                newOwnerName,
                lifecycleState,
                operation,
                force,
                idempotencyKey,
                callbacks,
                null,
                false
        );
    }

    /** Records permanent-release intent durably before destructive caller effects may run. */
    public boolean schedulePermanentRelease(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store,
                                            boolean force,
                                            @Nonnull String idempotencyKey,
                                            @Nullable MutationCallbacks callbacks) {
        return scheduleInternal(
                npcRef, store, null, null, false, null, null, null,
                CompanionLifecycleState.RELEASED, OwnerPopulationOperation.OWNER_CLEAR,
                force, idempotencyKey, callbacks, null, true
        );
    }
    /** Records a permanent release with trusted recovery context in the same operation journal. */
    public boolean schedulePermanentRelease(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store,
                                            boolean force,
                                            @Nonnull String idempotencyKey,
                                            @Nonnull String durableContextJson,
                                            @Nullable MutationCallbacks callbacks) {
        return scheduleInternal(
                npcRef, store, null, null, false, null, null, null,
                CompanionLifecycleState.RELEASED, OwnerPopulationOperation.OWNER_CLEAR,
                force, idempotencyKey, callbacks,
                Objects.requireNonNull(durableContextJson, "durableContextJson"), true
        );
    }
    /**
     * Restores one known dormant profile into a replacement live UUID without allocating a second
     * profile or requiring the replacement entity to already carry the durable owner component.
     */
    public boolean scheduleRestore(@Nonnull Ref<EntityStore> npcRef,
                                   @Nonnull Store<EntityStore> store,
                                   @Nonnull String canonicalProfileId,
                                   @Nullable UUID previousNpcUuid,
                                   @Nullable UUID expectedLiveOwnerId,
                                   @Nullable UUID restoredOwnerId,
                                   @Nullable String restoredOwnerName,
                                   @Nonnull CompanionLifecycleState lifecycleState,
                                   @Nonnull OwnerPopulationOperation operation,
                                   boolean force,
                                   @Nonnull String idempotencyKey,
                                   @Nullable MutationCallbacks callbacks) {
        return scheduleInternal(
                npcRef,
                store,
                OwnerPopulationEntry.normalizeProfileId(canonicalProfileId),
                previousNpcUuid,
                true,
                expectedLiveOwnerId,
                restoredOwnerId,
                restoredOwnerName,
                lifecycleState,
                operation,
                force,
                idempotencyKey,
                callbacks,
                null,
                false
        );
    }

    /** Schedules capture/restore with a journal payload committed in the same SQLite transaction. */
    public boolean scheduleWithDurableContext(
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store,
            @Nullable String canonicalProfileId,
            @Nullable UUID previousNpcUuid,
            @Nullable UUID expectedLiveOwnerId,
            @Nullable UUID newOwnerId,
            @Nullable String newOwnerName,
            @Nonnull CompanionLifecycleState lifecycleState,
            @Nonnull OwnerPopulationOperation operation,
            boolean force,
            @Nonnull String idempotencyKey,
            @Nonnull String durableContextJson,
            @Nullable MutationCallbacks callbacks
    ) {
        return scheduleInternal(
                npcRef, store, canonicalProfileId, previousNpcUuid,
                canonicalProfileId != null, expectedLiveOwnerId, newOwnerId, newOwnerName,
                lifecycleState, operation, force, idempotencyKey, callbacks,
                Objects.requireNonNull(durableContextJson, "durableContextJson"), false
        );
    }

    private boolean scheduleInternal(@Nonnull Ref<EntityStore> npcRef,
                                     @Nonnull Store<EntityStore> store,
                                     @Nullable String canonicalProfileId,
                                     @Nullable UUID previousNpcUuid,
                                     boolean explicitLiveOwnerExpectation,
                                     @Nullable UUID expectedLiveOwnerId,
                                     @Nullable UUID newOwnerId,
                                     @Nullable String newOwnerName,
                                     @Nonnull CompanionLifecycleState lifecycleState,
                                     @Nonnull OwnerPopulationOperation operation,
                                     boolean force,
                                     @Nonnull String idempotencyKey,
                                     @Nullable MutationCallbacks callbacks,
                                     @Nullable String durableContextJson,
        boolean permanentRelease) {
        MutationCallbacks safeCallbacks = callbacks == null ? MutationCallbacks.NOOP : callbacks;
        final OwnerMutationSnapshotResolver.Snapshot snapshot;
        try {
            snapshot = snapshotResolver.resolve(
                    npcRef, store, canonicalProfileId, previousNpcUuid,
                    explicitLiveOwnerExpectation, expectedLiveOwnerId, idempotencyKey
            );
        } catch (RuntimeException | LinkageError failure) {
            terminality.denied(safeCallbacks, "owner-mutation-identity-unavailable", null);
            return false;
        }
        if (snapshot == null) {
            terminality.denied(safeCallbacks, "owner-mutation-snapshot-unavailable", null);
            return false;
        }
        final OwnerMutationAdmissionPlanFactory.Plan plan;
        try {
            OwnerPopulationEntry current = index.entry(snapshot.profileId()).orElse(null);
            if (canonicalProfileId != null && current == null
                    && operation != OwnerPopulationOperation.LEGACY_ADOPTION) {
                releaseAndDenyBeforePreparation(snapshot, safeCallbacks,
                        "owner-mutation-canonical-profile-unavailable", null);
                return false;
            }
            if (OwnerMutationSnapshotResolver.isDuplicateRepresentation(snapshot, current, operation)) {
                releaseAndDenyBeforePreparation(snapshot, safeCallbacks,
                        "owner-mutation-duplicate-active-profile", null);
                return false;
            }
            if (current == null && snapshot.liveOwnerId() != null) {
                current = new OwnerPopulationEntry(
                        snapshot.profileId(), snapshot.liveOwnerId(), snapshot.worldName(),
                        CompanionLifecycleState.UNKNOWN_DORMANT, 0L
                );
                index.reconcileCommittedEntry(current);
            }
            plan = OwnerMutationAdmissionPlanFactory.create(
                    snapshot, current, newOwnerId, lifecycleState, operation, force,
                    permanentRelease, durableContextJson, policyResolver
            );
        } catch (RuntimeException | LinkageError failure) {
            releaseAndDenyBeforePreparation(
                    snapshot, safeCallbacks, "owner-mutation-plan-unavailable", null
            );
            return false;
        }
        try {
            ClaimLookupSession lookupSession = new ClaimLookupSession(
                    plan.policy().claimContext(),
                    plan.policy().claimLimitPerChunk() > 0,
                    lookupMetrics
            );
            CompletableFuture<CompanionPopulationPreparationResult> preparation =
                    companionCoordinator.prepareAsync(
                            plan.ownerPlan(), plan.claimRequest(), lookupSession
                    );
            if (preparation == null) {
                terminality.degrade("owner_mutation_prepare_stage_missing");
                terminality.denied(
                        safeCallbacks, "owner-mutation-prepare-stage-missing", null
                );
                return false;
            }
            preparation.whenComplete((result, failure) -> dispatchPrepared(
                    snapshot, newOwnerId, newOwnerName, safeCallbacks, result, failure
            ));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            terminality.degrade("owner_mutation_prepare_start_ambiguous");
            terminality.denied(safeCallbacks, "owner-mutation-prepare-failed", null);
            return false;
        }
    }

    private void dispatchPrepared(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
                                  @Nullable UUID newOwnerId,
                                  @Nullable String newOwnerName,
                                  @Nonnull MutationCallbacks callbacks,
                                  @Nullable CompanionPopulationPreparationResult preparation,
                                  @Nullable Throwable failure) {
        if (failure != null || preparation == null || !preparation.allowed()) {
            String reason = failure == null && preparation != null
                    ? preparation.reason()
                    : "owner-mutation-prepare-failed";
            if (failure == null && preparation != null) {
                identityLifecycle.releaseBeforeDurablePreparation(snapshot);
            } else {
                terminality.degrade("owner_mutation_prepare_completion_ambiguous");
            }
            LeaseBoundWorldDispatcher.execute(snapshot.world(), () -> {
                if (preparation != null) {
                    try {
                        callbacks.onPopulationDenied(preparation);
                    } catch (RuntimeException | LinkageError ignored) {
                        // The ordinary denial callback must still run.
                    }
                }
                terminality.denied(callbacks,
                        reason,
                        preparation == null ? null : preparation.ownerDecision()
                );
            }, () -> callbacks.onWorldDispatchRejected(reason, false, null));
            return;
        }
        PreparedCompanionPopulationAdmission prepared = preparation.preparedAdmission();
        if (prepared == null) {
            terminality.degrade("owner_mutation_prepared_capability_missing");
            terminality.denied(callbacks, "owner-mutation-prepared-capability-missing", null);
            return;
        }
        if (!identityLifecycle.promotePrepared(snapshot)) {
            identityLifecycle.cancelPrepared(
                    prepared, "owner-mutation-prepared-identity-promotion-failed"
            );
            terminality.denied(
                    callbacks,
                    "owner-mutation-prepared-identity-promotion-failed",
                    prepared.ownerAdmission().decision()
            );
            return;
        }
        LeaseBoundWorldDispatcher.execute(snapshot.world(), () -> applyPrepared(
                snapshot,
                newOwnerId,
                newOwnerName,
                callbacks,
                prepared
        ), () -> {
            cancelPrepared(prepared, "owner-mutation-world-unavailable");
            callbacks.onWorldDispatchRejected("owner-mutation-world-unavailable", false, null);
        });
    }

    private void applyPrepared(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
                               @Nullable UUID newOwnerId,
                               @Nullable String newOwnerName,
                               @Nonnull MutationCallbacks callbacks,
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

    private void releaseAndDenyBeforePreparation(
            @Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
            @Nonnull MutationCallbacks callbacks,
            @Nonnull String reason,
            @Nullable OwnerPopulationDecision decision
    ) {
        identityLifecycle.releaseBeforeDurablePreparation(snapshot);
        terminality.denied(callbacks, reason, decision);
    }

    private void cancelPrepared(
            @Nonnull PreparedCompanionPopulationAdmission prepared,
            @Nonnull String reason
    ) {
        identityLifecycle.cancelPrepared(prepared, reason);
    }

    public interface MutationCallbacks extends OwnerMutationCallbacks {
        MutationCallbacks NOOP = new MutationCallbacks() {
        };
    }
}
