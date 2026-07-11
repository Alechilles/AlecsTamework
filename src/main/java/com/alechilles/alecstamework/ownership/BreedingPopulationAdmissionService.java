package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancySnapshot;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
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
        if (!replayService.rememberPlan(request.idempotencyKey(), request.birthPlan())) {
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
        PreparedUnits preparedUnits = buildUnits(request, boundedCount, destination, policy);
        return batchCoordinator.prepareAsync(
                preparedUnits.units(),
                context.lookupSession,
                CompanionPopulationBatchMode.UP_TO,
                context.occupancySnapshot
        ).thenApply(result -> mapResult(request, preparedUnits.children(), result));
    }

    /** Returns retained restart evidence for a stable breeding job without querying SQLite. */
    @Nonnull
    public BreedingPopulationReplayState replayState(@Nonnull String idempotencyKey) {
        return replayService.state(idempotencyKey);
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
        return batchCoordinator.cancelAsync(batch.populationBatch(), unitIndex, reason);
    }

    @Nonnull
    public CompletableFuture<Integer> cancelRemainingAsync(
            @Nonnull PreparedBreedingPopulationBatch batch,
            @Nonnull String reason
    ) {
        return batchCoordinator.cancelRemainingAsync(batch.populationBatch(), reason);
    }

    @Nonnull
    private PreparedUnits buildUnits(
            @Nonnull BreedingPopulationAdmissionRequest request,
            int boundedCount,
            @Nonnull ClaimChunkCoordinate destination,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        List<CompanionPopulationAdmissionUnit> units = new ArrayList<>(boundedCount);
        List<PreparedBreedingPopulationBatch.ReservedChild> children = new ArrayList<>(boundedCount);
        for (int index = 0; index < boundedCount; index++) {
            BreedingPopulationAdmissionRequest.PlannedChild child = request.plannedChildren().get(index);
            String profileId = BreedingAdmissionIdentity.profileId(
                    request.idempotencyKey(), child.childKey()
            );
            UUID plannedNpcUuid = BreedingAdmissionIdentity.npcUuid(
                    request.idempotencyKey(), child.childKey()
            );
            PreparedBreedingPopulationBatch.ReservedChild reservedChild =
                    new PreparedBreedingPopulationBatch.ReservedChild(
                            child.childKey(),
                            profileId,
                            plannedNpcUuid,
                            child.ownerId(),
                            child.ownerName()
                    );
            children.add(reservedChild);
            units.add(unit(request, reservedChild, destination, policy));
        }
        return new PreparedUnits(List.copyOf(units), List.copyOf(children));
    }

    @Nonnull
    private CompanionPopulationAdmissionUnit unit(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull PreparedBreedingPopulationBatch.ReservedChild child,
            @Nonnull ClaimChunkCoordinate destination,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                child.profileId(),
                OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                child.ownerId(),
                request.worldName(),
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.BREEDING,
                policy.scope(),
                policy.limit(),
                request.force()
        );
        CompanionPopulationStateRecord baseline = baseline(request, child, destination);
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                child.plannedNpcUuid(),
                request.worldName(),
                destination.chunkX(),
                destination.chunkZ(),
                "breeding",
                ownerJson(null),
                ownerJson(child.ownerId()),
                targetJson(request, child, destination),
                policy.settingsRevision(),
                policy.claimContext().providerGeneration()
        );
        ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(
                null,
                new ClaimOccupancyEntry(
                        child.profileId(),
                        child.ownerId(),
                        CompanionLifecycleState.ACTIVE,
                        destination,
                        1L
                )
        );
        ClaimAdmissionRequest claimRequest = new ClaimAdmissionRequest(
                ClaimAdmissionOperation.BREED,
                List.of(claimTransition),
                destination,
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                policy.requireClaim(),
                request.force(),
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
        return new CompanionPopulationAdmissionUnit(ownerPlan, claimRequest);
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull PreparedBreedingPopulationBatch.ReservedChild child,
            @Nonnull ClaimChunkCoordinate destination
    ) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationStateRecord(
                child.profileId(),
                child.plannedNpcUuid(),
                null,
                request.worldName(),
                request.worldName(),
                CompanionLifecycleState.ACTIVE.name(),
                request.worldName(),
                destination.chunkX(),
                destination.chunkZ(),
                0L,
                "breeding_prepared",
                now,
                now
        );
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

    @Nonnull
    private static String ownerJson(UUID ownerId) {
        JsonObject json = new JsonObject();
        if (ownerId == null) {
            json.add("ownerUuid", null);
        } else {
            json.addProperty("ownerUuid", ownerId.toString());
        }
        return json.toString();
    }

    @Nonnull
    private static String targetJson(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull PreparedBreedingPopulationBatch.ReservedChild child,
            @Nonnull ClaimChunkCoordinate destination
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("operation", OwnerPopulationOperation.BREEDING.name().toLowerCase(Locale.ROOT));
        json.addProperty("idempotencyKey", request.idempotencyKey());
        json.addProperty("childKey", child.childKey());
        json.addProperty("world", destination.worldName());
        json.addProperty("chunkX", destination.chunkX());
        json.addProperty("chunkZ", destination.chunkZ());
        json.addProperty("plannedNpcUuid", child.plannedNpcUuid().toString());
        json.add("birthPlan", BreedingBirthPlanSnapshotJsonCodec.encode(request.birthPlan()));
        return json.toString();
    }

    private record PreparedUnits(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull List<PreparedBreedingPopulationBatch.ReservedChild> children
    ) {
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
