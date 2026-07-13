package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reserves combined owner and claim capacity before creating an owned NPC representation.
 *
 * <p>Replacement requests are admitted only from the exact canonical UUID, lifecycle, owner, and
 * revision pair recorded in both population indexes. Legacy captured items may allocate one
 * provisional profile, but a durable alias without matching population state fails closed.</p>
 */
public final class CompanionSpawnPopulationAdmissionService {
    private final CompanionIdentityResolver identityResolver;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionPopulationBatchAdmissionCoordinator batchCoordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final OwnerComponentMutationService mutationService;
    private final ClaimLookupMetrics lookupMetrics;
    private final CompanionSpawnAdmissionPlanner planner;

    public CompanionSpawnPopulationAdmissionService(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull ClaimProviderRegistry providerRegistry,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull ClaimLookupMetrics lookupMetrics
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        OwnerPopulationIndex safeOwnerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        ClaimOccupancyIndex safeClaimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.batchCoordinator = Objects.requireNonNull(batchCoordinator, "batchCoordinator");
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                safeClaimIndex, Objects.requireNonNull(providerRegistry, "providerRegistry")
        );
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        this.planner = new CompanionSpawnAdmissionPlanner(
                safeOwnerIndex, this.identityResolver, safeClaimIndex
        );
    }

    @Nonnull
    public CompletableFuture<CompanionSpawnPreparationResult> prepareAsync(
            @Nonnull CompanionSpawnAdmissionRequest request
    ) {
        return prepareBatchAsync(List.of(request), CompanionPopulationBatchMode.EXACT);
    }

    @Nonnull
    public CompletableFuture<CompanionSpawnPreparationResult> prepareBatchAsync(
            @Nonnull List<CompanionSpawnAdmissionRequest> requests,
            @Nonnull CompanionPopulationBatchMode mode
    ) {
        List<CompanionSpawnAdmissionRequest> safeRequests = List.copyOf(requests);
        if (safeRequests.isEmpty()) {
            throw new IllegalArgumentException("At least one spawn request is required.");
        }
        for (CompanionSpawnAdmissionRequest request : safeRequests) {
            Objects.requireNonNull(request, "requests cannot contain null");
        }
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                safeRequests.get(0).operation(),
                safeRequests.stream().anyMatch(request -> request.ownerId() != null)
        );
        CompanionSpawnAdmissionPlanner.PlannedBatch planned = planner.planBatch(
                safeRequests, policy
        );
        if (!planned.allowed()) {
            return CompletableFuture.completedFuture(denied(
                    safeRequests.size(), planned.reason(), null
            ));
        }
        ClaimLookupSession session = new ClaimLookupSession(
                policy.claimContext(), policy.claimLimitPerChunk() > 0, lookupMetrics
        );
        final CompletableFuture<CompanionPopulationBatchPreparationResult> preparation;
        try {
            preparation = batchCoordinator.prepareAsync(planned.units(), session, mode);
        } catch (RuntimeException | LinkageError failure) {
            planner.releaseUnadmitted(planned.spawns(), 0);
            throw failure;
        }
        return preparation.whenComplete((result, failure) -> {
            int admitted = failure == null && result != null && result.allowed()
                    && result.preparedBatch() != null
                    ? result.admittedCount()
                    : 0;
            planner.releaseUnadmitted(planned.spawns(), admitted);
        }).thenApply(result -> mapResult(safeRequests.size(), planned.spawns(), result));
    }

    /** Revalidates current settings, provider generation, and destination topology. */
    public boolean claimForSpawn(@Nonnull PreparedCompanionSpawnBatch batch, int unitIndex) {
        PreparedCompanionSpawnBatch.ReservedSpawn spawn = batch.spawn(unitIndex);
        if (!identityResolver.retainPreparedAlias(spawn.profileId(), spawn.plannedNpcUuid())) {
            releaseAfterCancellation(batch, unitIndex, "spawn-prepared-identity-conflict");
            return false;
        }
        CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                spawn.effectiveOperation(), spawn.request().ownerId() != null
        );
        ClaimLookupSession refreshed = new ClaimLookupSession(
                current.claimContext(), current.claimLimitPerChunk() > 0, lookupMetrics
        );
        boolean claimed = batchCoordinator.claimForApply(
                batch.populationBatch(), unitIndex, current.settingsRevision(), refreshed
        );
        if (!claimed) {
            releasePreparedAlias(spawn);
            releaseAfterCancellation(batch, unitIndex, "spawn-population-claim-invalid");
        }
        return claimed;
    }

    /** Writes the fixed UUID and owner into NPCPlugin's pre-add holder. */
    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nonnull Holder<EntityStore> holder
    ) {
        PreparedCompanionSpawnBatch.ReservedSpawn spawn = batch.spawn(unitIndex);
        return mutationService.writeClaimedSpawnHolder(
                holder,
                batch.populationBatch().admission(unitIndex).ownerAdmission(),
                spawn.plannedNpcUuid(),
                spawn.request().ownerId(),
                spawn.request().ownerName()
        );
    }

    /**
     * Marks the replacement identity live before starting terminal persistence. This makes any
     * still-present copied source stale immediately, even when final item/snapshot cleanup fails.
     */
    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitLiveAsync(
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex
    ) {
        PreparedCompanionSpawnBatch.ReservedSpawn spawn = batch.spawn(unitIndex);
        boolean mapped = remapLive(spawn);
        CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = batchCoordinator.commitAsync(batch.populationBatch(), unitIndex);
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("spawn_population_commit_start_failed");
            return CompletableFuture.completedFuture(degraded(
                    "spawn-population-commit-start-failed", null
            ));
        }
        if (completion == null) {
            markDegraded("spawn_population_commit_stage_missing");
            return CompletableFuture.completedFuture(degraded(
                    "spawn-population-commit-stage-missing", null
            ));
        }
        return completion.handle((result, failure) -> {
            if (failure != null || result == null) {
                markDegraded("spawn_population_commit_failed");
                return degraded("spawn-population-commit-failed", result);
            }
            if (!mapped) {
                markDegraded("spawn_identity_remap_failed");
                return degraded("spawn-identity-remap-failed", result);
            }
            if (shouldMarkIdentityDurable(result)) {
                spawn.claimIdentityTerminal();
                try {
                    identityResolver.markDurable(spawn.profileId(), spawn.plannedNpcUuid());
                } catch (RuntimeException | LinkageError identityFailure) {
                    markDegraded("spawn_identity_durable_mark_failed");
                    return degraded("spawn-identity-cache-degraded", result);
                }
            }
            return result;
        });
    }

    /** Completes the retained journal only after the caller's source CAS has succeeded. */
    @Nonnull
    public CompletableFuture<Boolean> completeSourceFinalizationAsync(
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex
    ) {
        return batchCoordinator.completeSourceFinalizationAsync(batch.populationBatch(), unitIndex);
    }

    /** Confirms that the planned UUID is still the canonical live identity for this profile. */
    public boolean isCurrentLiveIdentity(@Nonnull PreparedCompanionSpawnBatch batch, int unitIndex) {
        PreparedCompanionSpawnBatch.ReservedSpawn spawn = batch.spawn(unitIndex);
        try {
            return spawn.plannedNpcUuid().equals(
                    identityResolver.currentNpcUuid(spawn.profileId()).orElse(null)
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nonnull String reason
    ) {
        return batchCoordinator.cancelAsync(batch.populationBatch(), unitIndex, reason)
                .thenApply(canceled -> {
                    if (Boolean.TRUE.equals(canceled)
                            && !releaseCanceledIdentities(batch.spawn(unitIndex))) {
                        markDegraded("spawn_canceled_identity_release_failed");
                        return false;
                    }
                    return canceled;
                });
    }

    @Nonnull
    public CompletableFuture<Integer> cancelRemainingAsync(
            @Nonnull PreparedCompanionSpawnBatch batch,
            @Nonnull String reason
    ) {
        List<CompletableFuture<Boolean>> cancellations = new ArrayList<>();
        for (int index = 0; index < batch.populationBatch().admittedCount(); index++) {
            cancellations.add(cancelAsync(batch, index, reason));
        }
        return CompletableFuture.allOf(cancellations.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    int canceled = 0;
                    for (CompletableFuture<Boolean> cancellation : cancellations) {
                        if (Boolean.TRUE.equals(cancellation.getNow(false))) {
                            canceled++;
                        }
                    }
                    return canceled;
                });
    }

    /** Quarantines both authorities when a live spawn terminal action cannot be started. */
    public void markReadinessDegraded(@Nonnull String reason) {
        markDegraded(reason);
    }

    private void markDegraded(@Nonnull String reason) {
        try {
            coordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The unresolved live identity remains conservative if diagnostics also fail.
        }
    }

    @Nonnull
    private static CompanionPopulationCommitResult degraded(
            @Nonnull String reason,
            CompanionPopulationCommitResult result
    ) {
        return new CompanionPopulationCommitResult(
                false,
                reason,
                result != null && result.claimCommitted(),
                result == null ? null : result.ownerCommit()
        );
    }

    private boolean remapLive(@Nonnull PreparedCompanionSpawnBatch.ReservedSpawn spawn) {
        try {
            identityResolver.remap(
                    spawn.profileId(), spawn.previousNpcUuid(), spawn.plannedNpcUuid()
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private void releaseAfterCancellation(
            @Nonnull PreparedCompanionSpawnBatch batch,
            int unitIndex,
            @Nonnull String reason
    ) {
        try {
            CompletableFuture<Boolean> cancellation = batchCoordinator.cancelAsync(
                    batch.populationBatch(), unitIndex, reason
            );
            if (cancellation == null) {
                markDegraded("spawn_provisional_identity_cancel_stage_missing");
                return;
            }
            cancellation.whenComplete((canceled, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(canceled)
                        || !releaseCanceledIdentities(batch.spawn(unitIndex))) {
                    markDegraded("spawn_canceled_identity_release_failed");
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("spawn_canceled_identity_release_failed");
        }
    }

    private boolean releaseCanceledIdentities(
            @Nonnull PreparedCompanionSpawnBatch.ReservedSpawn spawn
    ) {
        boolean preparedReleased = releasePreparedAlias(spawn);
        boolean provisionalReleased = planner.releaseProvisional(spawn);
        return preparedReleased && provisionalReleased;
    }

    private boolean releasePreparedAlias(
            @Nonnull PreparedCompanionSpawnBatch.ReservedSpawn spawn
    ) {
        try {
            return identityResolver.releasePreparedAlias(
                    spawn.profileId(), spawn.plannedNpcUuid()
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Exact canonical source validation shared by restore-path regression tests. */
    @Nullable
    static String validateDormantSource(
            @Nonnull UUID previousNpcUuid,
            @Nullable UUID currentNpcUuid,
            @Nonnull CompanionLifecycleState requiredLifecycle,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim,
            boolean legacyAdoption
    ) {
        return CompanionSpawnAdmissionPlanner.validateDormantSource(
                previousNpcUuid, currentNpcUuid, requiredLifecycle,
                owner, claim, legacyAdoption
        );
    }

    @Nullable
    static String validateRequestedOwner(
            @Nullable OwnerPopulationEntry owner,
            @Nullable UUID requestedOwnerId,
            @Nonnull OwnerPopulationOperation operation,
            boolean legacyAdoption
    ) {
        return CompanionSpawnAdmissionPlanner.validateRequestedOwner(
                owner, requestedOwnerId, operation, legacyAdoption
        );
    }

    static boolean shouldMarkIdentityDurable(
            @Nonnull CompanionPopulationCommitResult result
    ) {
        return result.committed()
                || (result.ownerCommit() != null && result.ownerCommit().committed());
    }

    /** Stable identities make an exact top-level retry contend on the same canonical unit. */
    @Nonnull
    static UUID deterministicIdentity(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull String purpose
    ) {
        return CompanionSpawnAdmissionPlanner.deterministicIdentity(request, purpose);
    }

    @Nonnull
    private static CompanionSpawnPreparationResult mapResult(
            int requestedCount,
            @Nonnull List<PreparedCompanionSpawnBatch.ReservedSpawn> planned,
            @Nonnull CompanionPopulationBatchPreparationResult result
    ) {
        if (!result.allowed() || result.preparedBatch() == null) {
            return denied(requestedCount, result.reason(), result.limitingDecision());
        }
        int admitted = result.admittedCount();
        return new CompanionSpawnPreparationResult(
                true,
                admitted < requestedCount ? "spawn-population-clamped" : "spawn-population-prepared",
                requestedCount,
                admitted,
                result.limitingDecision(),
                new PreparedCompanionSpawnBatch(
                        result.preparedBatch(), planned.subList(0, admitted)
                )
        );
    }

    @Nonnull
    private static CompanionSpawnPreparationResult denied(
            int requestedCount,
            @Nonnull String reason,
            @Nullable CompanionPopulationPreparationResult limiting
    ) {
        return new CompanionSpawnPreparationResult(
                false, reason, requestedCount, 0, limiting, null
        );
    }

}
