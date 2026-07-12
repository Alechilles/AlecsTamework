package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancySnapshot;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Breeding adapter over the shared owner-and-claim batch authority.
 *
 * <p>The caller supplies the exact owner selected for every already-planned child. A null owner
 * produces no owner-slot or physical-occupancy delta, while a configured breeding claim
 * requirement still evaluates the destination location.</p>
 */
public final class BreedingPopulationAdmissionService {
    private final CompanionPopulationBatchAdmissionCoordinator batchCoordinator;
    private final ClaimOccupancyIndex claimOccupancyIndex;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final OwnerComponentMutationService mutationService;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimLookupMetrics lookupMetrics;
    private final BreedingPopulationReplayService replayService;
    private final BreedingPopulationAdmissionUnitFactory unitFactory;

    BreedingPopulationAdmissionService(
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
            @Nonnull ClaimProviderRegistry claimProviderRegistry,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull CompanionIdentityResolver identityResolver
    ) {
        this(batchCoordinator, claimOccupancyIndex, claimProviderRegistry, mutationService,
                identityResolver, new ClaimLookupMetrics(),
                new BreedingPopulationReplayService(List.of()));
    }

    BreedingPopulationAdmissionService(
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
            @Nonnull ClaimProviderRegistry claimProviderRegistry,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimLookupMetrics lookupMetrics
    ) {
        this(batchCoordinator, claimOccupancyIndex, claimProviderRegistry, mutationService,
                identityResolver, lookupMetrics, new BreedingPopulationReplayService(List.of()));
    }

    BreedingPopulationAdmissionService(
            @Nonnull CompanionPopulationBatchAdmissionCoordinator batchCoordinator,
            @Nonnull ClaimOccupancyIndex claimOccupancyIndex,
            @Nonnull ClaimProviderRegistry claimProviderRegistry,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimLookupMetrics lookupMetrics,
            @Nonnull BreedingPopulationReplayService replayService
    ) {
        this.batchCoordinator = Objects.requireNonNull(batchCoordinator, "batchCoordinator");
        this.claimOccupancyIndex = Objects.requireNonNull(
                claimOccupancyIndex, "claimOccupancyIndex"
        );
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                this.claimOccupancyIndex,
                Objects.requireNonNull(claimProviderRegistry, "claimProviderRegistry")
        );
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
        this.replayService = Objects.requireNonNull(replayService, "replayService");
        this.unitFactory = new BreedingPopulationAdmissionUnitFactory();
    }

    @Nonnull
    public CompletableFuture<BreedingPopulationPreparationResult> prepareAsync(
            @Nonnull BreedingPopulationAdmissionRequest request
    ) {
        return prepareAsync(request, null);
    }

    /** Uses a lazy passive-sweep context when supplied; manual calls retain operation scope. */
    @Nonnull
    public CompletableFuture<BreedingPopulationPreparationResult> prepareAsync(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nullable PreparationContext sharedContext
    ) {
        Objects.requireNonNull(request, "request");
        int requestedCount = request.plannedChildren().size();
        if (!replayService.accepts(request)) {
            return CompletableFuture.completedFuture(new BreedingPopulationPreparationResult(
                    false,
                    "breeding-replay-plan-conflict",
                    requestedCount,
                    0,
                    null,
                    null
            ));
        }
        int boundedCount = request.boundedAdmittedCount();
        if (boundedCount <= 0) {
            return CompletableFuture.completedFuture(new BreedingPopulationPreparationResult(
                    false,
                    "breeding-nearby-cap-reached",
                    requestedCount,
                    0,
                    null,
                    null
            ));
        }
        PreparationContext context = sharedContext == null
                ? openPreparationContext() : requireContext(sharedContext);
        CompanionAdmissionPolicyResolver.Policy policy = context.policy;
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate(
                request.worldName(),
                request.destinationChunkX(),
                request.destinationChunkZ()
        );
        BreedingPopulationAdmissionUnitFactory.PreparedUnits preparedUnits = unitFactory.build(
                request, boundedCount, destination, policy
        );
        return batchCoordinator.prepareAsync(
                preparedUnits.units(),
                context.lookupSession,
                CompanionPopulationBatchMode.UP_TO,
                context.occupancySnapshot
        ).thenCompose(result -> recordPreparation(
                request, preparedUnits.children(), result
        ));
    }

    /** Returns retained restart evidence for a stable breeding job without querying SQLite. */
    @Nonnull
    public BreedingPopulationReplayState replayState(@Nonnull String idempotencyKey) {
        return replayService.state(idempotencyKey);
    }

    /** Returns the sole journal-proven incomplete attempt for this canonical pair and world. */
    @Nonnull
    public BreedingPopulationReplayState replayStateForPair(
            @Nonnull String worldName,
            @Nonnull List<String> parentProfileIds
    ) {
        return replayService.stateForPair(worldName, parentProfileIds);
    }

    /** Captures one immutable policy, lookup cache, and optional occupancy snapshot for a sweep. */
    @Nonnull
    public PreparationContext openPreparationContext() {
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                OwnerPopulationOperation.BREEDING,
                true
        );
        ClaimLookupSession lookupSession = new ClaimLookupSession(
                policy.claimContext(),
                policy.claimLimitPerChunk() > 0,
                lookupMetrics
        );
        boolean capped = policy.claimLimitPerChunk() > 0 || policy.claimLimitTotal() > 0;
        ClaimOccupancySnapshot occupancySnapshot = capped && policy.claimContext().ready()
                ? claimOccupancyIndex.snapshot() : null;
        return new PreparationContext(this, policy, lookupSession, occupancySnapshot);
    }

    @Nonnull
    private PreparationContext requireContext(@Nonnull PreparationContext context) {
        if (context.authority != this) {
            throw new IllegalArgumentException("Breeding preparation context belongs to another runtime.");
        }
        return context;
    }

    /** Rechecks settings/provider/topology and claims one exact child unit for holder mutation. */
    public boolean claimForSpawn(@Nonnull PreparedBreedingPopulationBatch batch, int unitIndex) {
        Objects.requireNonNull(batch, "batch");
        CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                OwnerPopulationOperation.BREEDING,
                true
        );
        ClaimLookupSession refreshed = new ClaimLookupSession(
                current.claimContext(),
                current.claimLimitPerChunk() > 0,
                lookupMetrics
        );
        return batchCoordinator.claimForApply(
                batch.populationBatch(),
                unitIndex,
                current.settingsRevision(),
                refreshed
        );
    }

    /** Installs the planned identity/owner into the pre-add NPC holder. */
    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull PreparedBreedingPopulationBatch batch,
            int unitIndex,
            @Nonnull Holder<EntityStore> holder
    ) {
        PreparedBreedingPopulationBatch.ReservedChild child = batch.child(unitIndex);
        return mutationService.writeClaimedSpawnHolder(
                holder,
                batch.populationBatch().admission(unitIndex).ownerAdmission(),
                child.plannedNpcUuid(),
                child.ownerId(),
                child.ownerName()
        );
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitAsync(
            @Nonnull PreparedBreedingPopulationBatch batch,
            int unitIndex
    ) {
        PreparedBreedingPopulationBatch.ReservedChild child = batch.child(unitIndex);
        boolean mapped = remapLive(child);
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = batchCoordinator.commitAsync(batch.populationBatch(), unitIndex);
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("breeding_population_commit_start_failed");
            return CompletableFuture.completedFuture(degraded(
                    "breeding-population-commit-start-failed", null
            ));
        }
        if (completion == null) {
            markDegraded("breeding_population_commit_stage_missing");
            return CompletableFuture.completedFuture(degraded(
                    "breeding-population-commit-stage-missing", null
            ));
        }
        return completion.handle((result, failure) -> {
            if (failure != null || result == null) {
                markDegraded("breeding_population_commit_failed");
                return degraded("breeding-population-commit-failed", result);
            }
            if (result.committed()) {
                replayService.recordCommitted(
                        batch.attemptKey(),
                        child.childKey(),
                        child.profileId(),
                        child.plannedNpcUuid(),
                        batch.birthPlan()
                );
            }
            if (!mapped) {
                return degraded("breeding-live-identity-remap-failed", result);
            }
            if (identityDurable(result)) {
                try {
                    identityResolver.markDurable(child.profileId(), child.plannedNpcUuid());
                } catch (RuntimeException | LinkageError identityFailure) {
                    markDegraded("breeding_identity_durable_mark_failed");
                    return degraded("breeding-identity-cache-degraded", result);
                }
            }
            return result;
        });
    }

    private boolean remapLive(@Nonnull PreparedBreedingPopulationBatch.ReservedChild child) {
        try {
            identityResolver.remap(child.profileId(), null, child.plannedNpcUuid());
            return true;
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("breeding_live_identity_remap_failed");
            return false;
        }
    }

    private void markDegraded(@Nonnull String reason) {
        try {
            batchCoordinator.markReadinessDegraded(reason);
        } catch (RuntimeException | LinkageError ignored) {
            // The unresolved live identity remains conservative if diagnostics also fail.
        }
    }

    /** Fails closed after an ambiguous live spawn while preserving its APPLYING journal evidence. */
    public void markReadinessDegraded(@Nonnull String reason) {
        markDegraded(reason);
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

    private static boolean identityDurable(@Nonnull CompanionPopulationCommitResult result) {
        return result.committed()
                || (result.ownerCommit() != null && result.ownerCommit().committed());
    }

    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedBreedingPopulationBatch batch,
                                                   int unitIndex,
                                                   @Nonnull String reason) {
        Objects.requireNonNull(batch, "batch");
        PreparedBreedingPopulationBatch.ReservedChild child = batch.child(unitIndex);
        return batchCoordinator.cancelAsync(
                batch.populationBatch(), unitIndex, reason
        ).thenApply(canceled -> {
            if (Boolean.TRUE.equals(canceled)) {
                replayService.recordAborted(
                        batch.attemptKey(), child.childKey(), batch.birthPlan()
                );
            }
            return canceled;
        });
    }

    @Nonnull
    public CompletableFuture<Integer> cancelRemainingAsync(
            @Nonnull PreparedBreedingPopulationBatch batch,
            @Nonnull String reason
    ) {
        Objects.requireNonNull(batch, "batch");
        List<CompletableFuture<Boolean>> cancellations = new ArrayList<>(
                batch.admittedCount()
        );
        for (int unitIndex = 0; unitIndex < batch.admittedCount(); unitIndex++) {
            cancellations.add(cancelAsync(batch, unitIndex, reason));
        }
        CompletableFuture<?>[] waits = cancellations.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(waits).thenApply(ignored -> {
            int canceled = 0;
            for (CompletableFuture<Boolean> cancellation : cancellations) {
                if (Boolean.TRUE.equals(cancellation.getNow(false))) {
                    canceled++;
                }
            }
            return canceled;
        });
    }

    @Nonnull
    private CompletableFuture<BreedingPopulationPreparationResult> recordPreparation(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull List<PreparedBreedingPopulationBatch.ReservedChild> plannedChildren,
            @Nonnull CompanionPopulationBatchPreparationResult result
    ) {
        BreedingPopulationPreparationResult mapped = mapResult(
                request, plannedChildren, result
        );
        PreparedBreedingPopulationBatch batch = mapped.preparedBatch();
        if (!mapped.allowed() || batch == null) {
            return CompletableFuture.completedFuture(mapped);
        }
        if (replayService.recordPrepared(request, batch.children())) {
            return CompletableFuture.completedFuture(mapped);
        }
        markDegraded("breeding_replay_preparation_conflict");
        return cancelRemainingAsync(
                batch, "breeding-replay-preparation-conflict"
        ).handle((ignored, failure) -> new BreedingPopulationPreparationResult(
                false,
                "breeding-replay-preparation-conflict",
                request.plannedChildren().size(),
                0,
                result,
                null
        ));
    }

    @Nonnull
    private static BreedingPopulationPreparationResult mapResult(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull List<PreparedBreedingPopulationBatch.ReservedChild> plannedChildren,
            @Nonnull CompanionPopulationBatchPreparationResult result
    ) {
        int requestedCount = request.plannedChildren().size();
        if (!result.allowed() || result.preparedBatch() == null) {
            return new BreedingPopulationPreparationResult(
                    false,
                    result.reason(),
                    requestedCount,
                    0,
                    result,
                    null
            );
        }
        int admitted = result.admittedCount();
        PreparedBreedingPopulationBatch prepared = new PreparedBreedingPopulationBatch(
                requestedCount,
                request.idempotencyKey(),
                request.birthPlan(),
                result.preparedBatch(),
                plannedChildren.subList(0, admitted)
        );
        String reason = admitted < requestedCount
                ? "breeding-population-clamped"
                : "breeding-population-prepared";
        return new BreedingPopulationPreparationResult(
                true,
                reason,
                requestedCount,
                admitted,
                result,
                prepared
        );
    }

    /** Thread-confined passive-sweep cache; spawn claims still revalidate current policy. */
    public static final class PreparationContext {
        private final BreedingPopulationAdmissionService authority;
        private final CompanionAdmissionPolicyResolver.Policy policy;
        private final ClaimLookupSession lookupSession;
        @Nullable
        private final ClaimOccupancySnapshot occupancySnapshot;

        private PreparationContext(
                BreedingPopulationAdmissionService authority,
                CompanionAdmissionPolicyResolver.Policy policy,
                ClaimLookupSession lookupSession,
                @Nullable ClaimOccupancySnapshot occupancySnapshot
        ) {
            this.authority = authority;
            this.policy = policy;
            this.lookupSession = lookupSession;
            this.occupancySnapshot = occupancySnapshot;
        }
    }
}
