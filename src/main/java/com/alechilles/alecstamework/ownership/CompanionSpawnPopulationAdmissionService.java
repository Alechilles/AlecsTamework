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
import java.nio.charset.StandardCharsets;
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
    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionPopulationBatchAdmissionCoordinator batchCoordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final OwnerComponentMutationService mutationService;
    private final ClaimLookupMetrics lookupMetrics;

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
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.batchCoordinator = Objects.requireNonNull(batchCoordinator, "batchCoordinator");
        this.policyResolver = new CompanionAdmissionPolicyResolver(claimIndex, providerRegistry);
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
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
        PlannedBatch planned = planBatch(safeRequests, policy);
        if (!planned.allowed()) {
            return CompletableFuture.completedFuture(denied(
                    safeRequests.size(), planned.reason(), null
            ));
        }
        ClaimLookupSession session = new ClaimLookupSession(
                policy.claimContext(), policy.claimLimitPerChunk() > 0, lookupMetrics
        );
        return batchCoordinator.prepareAsync(planned.units(), session, mode)
                .thenApply(result -> mapResult(safeRequests.size(), planned.spawns(), result));
    }

    /** Revalidates current settings, provider generation, and destination topology. */
    public boolean claimForSpawn(@Nonnull PreparedCompanionSpawnBatch batch, int unitIndex) {
        PreparedCompanionSpawnBatch.ReservedSpawn spawn = batch.spawn(unitIndex);
        CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                spawn.effectiveOperation(), spawn.request().ownerId() != null
        );
        ClaimLookupSession refreshed = new ClaimLookupSession(
                current.claimContext(), current.claimLimitPerChunk() > 0, lookupMetrics
        );
        return batchCoordinator.claimForApply(
                batch.populationBatch(), unitIndex, current.settingsRevision(), refreshed
        );
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
        return batchCoordinator.cancelAsync(batch.populationBatch(), unitIndex, reason);
    }

    @Nonnull
    public CompletableFuture<Integer> cancelRemainingAsync(
            @Nonnull PreparedCompanionSpawnBatch batch,
            @Nonnull String reason
    ) {
        return batchCoordinator.cancelRemainingAsync(batch.populationBatch(), reason);
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

    @Nonnull
    private PlannedBatch planBatch(
            @Nonnull List<CompanionSpawnAdmissionRequest> requests,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        List<CompanionPopulationAdmissionUnit> units = new ArrayList<>(requests.size());
        List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns = new ArrayList<>(requests.size());
        for (CompanionSpawnAdmissionRequest request : requests) {
            PlanResult result = plan(request, policy);
            if (!result.allowed()) {
                return PlannedBatch.denied(result.reason());
            }
            units.add(result.unit());
            spawns.add(result.spawn());
        }
        return PlannedBatch.allowed(units, spawns);
    }

    @Nonnull
    private PlanResult plan(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        Source source = request.replacement()
                ? resolveSource(request)
                : Source.fresh(deterministicIdentity(request, "profile").toString());
        if (!source.allowed()) {
            return PlanResult.denied(source.reason());
        }
        String profileId = source.profileId();
        UUID plannedNpcUuid = deterministicIdentity(request, "npc");
        OwnerPopulationOperation effectiveOperation = source.legacyAdoption()
                ? OwnerPopulationOperation.LEGACY_ADOPTION
                : request.operation();
        CompanionPopulationAdmissionUnit unit = CompanionSpawnAdmissionPlanFactory.create(
                request,
                profileId,
                plannedNpcUuid,
                effectiveOperation,
                source.owner(),
                source.claim(),
                policy
        );
        PreparedCompanionSpawnBatch.ReservedSpawn spawn =
                new PreparedCompanionSpawnBatch.ReservedSpawn(
                        request, profileId, plannedNpcUuid, request.previousNpcUuid(),
                        effectiveOperation, source.legacyAdoption()
                );
        return PlanResult.allowed(unit, spawn);
    }

    @Nonnull
    private Source resolveSource(@Nonnull CompanionSpawnAdmissionRequest request) {
        UUID previousUuid = request.previousNpcUuid();
        String aliasProfile = identityResolver.resolveProfileId(previousUuid).orElse(null);
        if (request.canonicalProfileId() != null
                && aliasProfile != null
                && !request.canonicalProfileId().equals(aliasProfile)) {
            return Source.denied("spawn-source-canonical-profile-mismatch");
        }
        String profileId = request.canonicalProfileId() != null
                ? request.canonicalProfileId()
                : aliasProfile;
        OwnerPopulationEntry owner = profileId == null ? null : ownerIndex.entry(profileId).orElse(null);
        ClaimOccupancyEntry claim = profileId == null ? null : claimIndex.entry(profileId).orElse(null);
        boolean legacy = false;
        if (owner == null && claim == null && request.allowLegacyAdoption()) {
            CompanionIdentityResolver.Resolution resolution = identityResolver.resolveOrAllocate(
                    previousUuid, request.idempotencyKey() + ":legacy-adoption"
            );
            if (request.canonicalProfileId() != null
                    && !request.canonicalProfileId().equals(resolution.profileId())) {
                return Source.denied("spawn-source-canonical-profile-mismatch");
            }
            if (!resolution.provisional()) {
                return Source.denied("spawn-source-population-profile-unavailable");
            }
            profileId = resolution.profileId();
            legacy = true;
        }
        if (profileId == null) {
            return Source.denied("spawn-source-canonical-profile-unavailable");
        }
        UUID currentUuid = identityResolver.currentNpcUuid(profileId).orElse(null);
        String invalid = validateDormantSource(
                previousUuid, currentUuid, request.requiredSourceLifecycle(), owner, claim, legacy
        );
        if (invalid == null) {
            invalid = validateRequestedOwner(
                    owner, request.ownerId(), request.operation(), legacy
            );
        }
        return invalid == null
                ? Source.allowed(profileId, owner, claim, legacy)
                : Source.denied(invalid);
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
        if (!previousNpcUuid.equals(currentNpcUuid)) {
            return "spawn-source-duplicate-active-profile";
        }
        if (legacyAdoption) {
            return owner == null && claim == null
                    ? null
                    : "spawn-source-population-state-mismatch";
        }
        if (owner == null || claim == null) {
            return "spawn-source-population-profile-unavailable";
        }
        if (owner.lifecycleState() == CompanionLifecycleState.ACTIVE
                || owner.lifecycleState() == CompanionLifecycleState.UNLOADED
                || claim.lifecycleState() == CompanionLifecycleState.ACTIVE
                || claim.lifecycleState() == CompanionLifecycleState.UNLOADED) {
            return "spawn-source-duplicate-active-profile";
        }
        if (owner.lifecycleState() != requiredLifecycle || claim.lifecycleState() != requiredLifecycle) {
            return "spawn-source-lifecycle-mismatch";
        }
        if (owner.revision() != claim.revision()
                || !Objects.equals(owner.ownerId(), claim.ownerId())) {
            return "spawn-source-population-state-mismatch";
        }
        return null;
    }

    @Nullable
    static String validateRequestedOwner(
            @Nullable OwnerPopulationEntry owner,
            @Nullable UUID requestedOwnerId,
            @Nonnull OwnerPopulationOperation operation,
            boolean legacyAdoption
    ) {
        return !legacyAdoption
                && operation != OwnerPopulationOperation.ADMIN_FORCE
                && owner != null
                && !Objects.equals(owner.ownerId(), requestedOwnerId)
                ? "spawn-source-owner-mismatch"
                : null;
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
        String fingerprint = "tamework-companion-spawn-v1|" + purpose
                + "|" + request.idempotencyKey()
                + "|" + Objects.toString(request.canonicalProfileId(), "-")
                + "|" + Objects.toString(request.previousNpcUuid(), "-")
                + "|" + Objects.toString(request.requiredSourceLifecycle(), "-")
                + "|" + Objects.toString(request.ownerId(), "-")
                + "|" + request.worldName()
                + "|" + request.chunkX() + "|" + request.chunkZ()
                + "|" + request.operation()
                + "|" + request.sourceKind()
                + "|" + request.force();
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
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

    private record PlannedBatch(
            boolean allowed,
            @Nonnull String reason,
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns
    ) {
        static PlannedBatch allowed(List<CompanionPopulationAdmissionUnit> units,
                                    List<PreparedCompanionSpawnBatch.ReservedSpawn> spawns) {
            return new PlannedBatch(true, "spawn-population-planned", List.copyOf(units), List.copyOf(spawns));
        }

        static PlannedBatch denied(String reason) {
            return new PlannedBatch(false, reason, List.of(), List.of());
        }
    }

    private record PlanResult(boolean allowed,
                              @Nonnull String reason,
                              @Nullable CompanionPopulationAdmissionUnit unit,
                              @Nullable PreparedCompanionSpawnBatch.ReservedSpawn spawn) {
        static PlanResult allowed(CompanionPopulationAdmissionUnit unit,
                                  PreparedCompanionSpawnBatch.ReservedSpawn spawn) {
            return new PlanResult(true, "spawn-population-unit-planned", unit, spawn);
        }

        static PlanResult denied(String reason) {
            return new PlanResult(false, reason, null, null);
        }
    }

    private record Source(boolean allowed,
                          @Nonnull String reason,
                          @Nullable String profileId,
                          @Nullable OwnerPopulationEntry owner,
                          @Nullable ClaimOccupancyEntry claim,
                          boolean legacyAdoption) {
        static Source fresh(String profileId) {
            return new Source(true, "spawn-source-new", profileId, null, null, false);
        }

        static Source allowed(String profileId, OwnerPopulationEntry owner,
                              ClaimOccupancyEntry claim, boolean legacyAdoption) {
            return new Source(true, "spawn-source-resolved", profileId, owner, claim, legacyAdoption);
        }

        static Source denied(String reason) {
            return new Source(false, reason, null, null, null, false);
        }
    }
}
