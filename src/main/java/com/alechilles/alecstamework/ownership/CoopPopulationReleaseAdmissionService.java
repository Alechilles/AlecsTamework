package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimProviderRegistry;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Prepares a dormant coop profile before any replacement NPC is physically spawned. */
public final class CoopPopulationReleaseAdmissionService {
    static final ClaimAdmissionOperation CLAIM_OPERATION = ClaimAdmissionOperation.COOP_RELEASE;

    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final OwnerComponentMutationService mutationService;
    private final ClaimLookupMetrics lookupMetrics;

    CoopPopulationReleaseAdmissionService(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull ClaimProviderRegistry providerRegistry,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull OwnerComponentMutationService mutationService,
            @Nonnull ClaimLookupMetrics lookupMetrics
    ) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.policyResolver = new CompanionAdmissionPolicyResolver(claimIndex, providerRegistry);
        this.mutationService = Objects.requireNonNull(mutationService, "mutationService");
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
    }

    @Nonnull
    public CompletableFuture<PreparationResult> prepareAsync(@Nonnull ReleaseRequest request) {
        return prepareAsync(request, null);
    }

    /**
     * Prepares a replacement release with an optional journal-context extension derived from the
     * exact planned replacement UUID. The extension is persisted with the owner transition and is
     * therefore committed in the same SQLite transaction as population state.
     */
    @Nonnull
    public CompletableFuture<PreparationResult> prepareAsync(
            @Nonnull ReleaseRequest request,
            @Nullable Function<UUID, String> durableContextFactory
    ) {
        Objects.requireNonNull(request, "request");
        PlanResult planned = plan(request, durableContextFactory);
        if (!planned.allowed()) {
            return CompletableFuture.completedFuture(new PreparationResult(
                    false, planned.reason(), null, planned.disposition()));
        }
        ClaimLookupSession session = new ClaimLookupSession(
                planned.policy().claimContext(),
                planned.policy().claimLimitPerChunk() > 0,
                lookupMetrics
        );
        return coordinator.prepareAsync(planned.ownerPlan(), planned.claimRequest(), session)
                .thenApply(result -> {
                    if (result != null
                            && result.allowed()
                            && result.preparedAdmission() != null) {
                        return PreparationResult.prepared(new PreparedRelease(
                                request,
                                planned.profileId(),
                                request.plannedNpcUuid(),
                                result.preparedAdmission()
                        ));
                    }
                    return PreparationResult.denied(result);
                });
    }

    public boolean claimForSpawn(@Nonnull PreparedRelease prepared) {
        CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                OwnerPopulationOperation.RESTORE,
                true
        );
        ClaimLookupSession session = new ClaimLookupSession(
                current.claimContext(), current.claimLimitPerChunk() > 0, lookupMetrics
        );
        return coordinator.claimForApply(
                prepared.admission(), current.settingsRevision(), session
        );
    }

    @Nonnull
    public OwnerComponentMutationService.WriteResult writeSpawnHolder(
            @Nonnull PreparedRelease prepared,
            @Nonnull Holder<EntityStore> holder
    ) {
        return mutationService.writeClaimedSpawnHolder(
                holder,
                prepared.admission().ownerAdmission(),
                prepared.plannedNpcUuid(),
                prepared.request().ownerId(),
                prepared.request().ownerName()
        );
    }

    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitAsync(
            @Nonnull PreparedRelease prepared
    ) {
        boolean mapped = remapLive(prepared);
        final CompletableFuture<CompanionPopulationCommitResult> completion;
        try {
            completion = coordinator.commitAsync(prepared.admission());
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("coop_release_population_commit_start_failed");
            return CompletableFuture.completedFuture(degraded(
                    "coop-release-population-commit-start-failed", null
            ));
        }
        if (completion == null) {
            markDegraded("coop_release_population_commit_stage_missing");
            return CompletableFuture.completedFuture(degraded(
                    "coop-release-population-commit-stage-missing", null
            ));
        }
        return completion.handle((result, failure) -> {
            if (failure != null || result == null) {
                markDegraded("coop_release_population_commit_failed");
                return degraded("coop-release-population-commit-failed", result);
            }
            if (!mapped) {
                return degraded("coop-release-live-identity-remap-failed", result);
            }
            if (identityDurable(result)) {
                try {
                    identityResolver.markDurable(
                            prepared.profileId(), prepared.plannedNpcUuid()
                    );
                } catch (RuntimeException | LinkageError identityFailure) {
                    markDegraded("coop_release_identity_durable_mark_failed");
                    return degraded("coop-release-identity-cache-degraded", result);
                }
            }
            return result;
        });
    }

    /** Quarantines new admissions while an exception-ambiguous live spawn remains recoverable. */
    public void markReadinessDegraded(@Nonnull String reason) {
        markDegraded(reason);
    }

    /** Revalidates canonical profile and owner identity immediately before post-commit effects. */
    public boolean matchesLiveIdentity(
            @Nonnull PreparedRelease prepared,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        try {
            if (!prepared.plannedNpcUuid().equals(
                    identityResolver.currentNpcUuid(prepared.profileId()).orElse(null)
            )) {
                return false;
            }
            ComponentType<EntityStore, TameworkOwnerComponent> type =
                    TameworkOwnerComponent.getComponentType();
            if (type == null || !ref.isValid()) {
                return false;
            }
            TameworkOwnerComponent owner = store.getComponent(ref, type);
            return Objects.equals(
                    prepared.request().ownerId(), owner == null ? null : owner.getOwnerId()
            );
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean remapLive(@Nonnull PreparedRelease prepared) {
        try {
            identityResolver.remap(
                    prepared.profileId(),
                    prepared.request().previousNpcUuid(),
                    prepared.plannedNpcUuid()
            );
            return true;
        } catch (RuntimeException | LinkageError failure) {
            markDegraded("coop_release_live_identity_remap_failed");
            return false;
        }
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

    private static boolean identityDurable(@Nonnull CompanionPopulationCommitResult result) {
        return result.committed()
                || (result.ownerCommit() != null && result.ownerCommit().committed());
    }

    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(@Nonnull PreparedRelease prepared,
                                                   @Nonnull String reason) {
        return coordinator.cancelAsync(prepared.admission(), reason);
    }

    @Nonnull
    private PlanResult plan(@Nonnull ReleaseRequest request,
                            @Nullable Function<UUID, String> durableContextFactory) {
        String profileId = identityResolver.resolveProfileId(request.previousNpcUuid()).orElse(null);
        if (profileId == null) {
            return PlanResult.denied("coop-release-canonical-profile-unavailable");
        }
        UUID currentUuid = identityResolver.currentNpcUuid(profileId).orElse(null);
        if (currentUuid == null || !currentUuid.equals(request.previousNpcUuid())) {
            return PlanResult.denied("coop-release-duplicate-active-profile");
        }
        OwnerPopulationEntry owner = ownerIndex.entry(profileId).orElse(null);
        ClaimOccupancyEntry claim = claimIndex.entry(profileId).orElse(null);
        if (owner == null || claim == null) {
            return PlanResult.denied("coop-release-population-profile-unavailable");
        }
        String invalidSource = validateDormantSource(
                request.previousNpcUuid(), currentUuid, request.ownerId(), owner, claim
        );
        if (invalidSource != null) {
            return PlanResult.denied(invalidSource);
        }
        if (claim.revision() == Long.MAX_VALUE) {
            return PlanResult.definitivelyDenied(
                    "coop-release-population-revision-exhausted");
        }
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                OwnerPopulationOperation.RESTORE,
                true
        );
        UUID plannedNpcUuid = request.plannedNpcUuid();
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate(
                request.worldName(), request.chunkX(), request.chunkZ()
        );
        ClaimOccupancyEntry proposed = new ClaimOccupancyEntry(
                profileId,
                request.ownerId(),
                CompanionLifecycleState.ACTIVE,
                destination,
                claim.revision() + 1L
        );
        ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(claim, proposed);
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                owner.revision(),
                owner.ownerId(),
                owner.ownershipWorldName(),
                request.ownerId(),
                request.ownerId() == null ? null : request.worldName(),
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.RESTORE,
                policy.scope(),
                policy.limit(),
                false
        );
        CompanionPopulationStateRecord baseline = baseline(
                request, profileId, owner, claim
        );
        final String targetContext;
        try {
            targetContext = contextJson(request, durableContextFactory);
        } catch (RuntimeException | LinkageError invalidContext) {
            return PlanResult.definitivelyDenied("coop-release-durable-context-invalid");
        }
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                plannedNpcUuid,
                request.worldName(),
                request.chunkX(),
                request.chunkZ(),
                "coop_release",
                ownerJson(owner.ownerId()),
                ownerJson(request.ownerId()),
                targetContext,
                policy.settingsRevision(),
                policy.claimContext().providerGeneration()
        );
        ClaimAdmissionRequest claimRequest = new ClaimAdmissionRequest(
                CLAIM_OPERATION,
                List.of(claimTransition),
                proposed.occupiesClaim() ? destination : null,
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                false,
                false,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
        return PlanResult.allowed(profileId, plannedNpcUuid, policy, ownerPlan, claimRequest);
    }

    /**
     * Verifies that the canonical profile still denotes the exact resident represented by the
     * coop ledger. A replacement spawn may only migrate a paired COOP owner/claim projection;
     * accepting another dormant lifecycle would turn stale ledger data into a duplicate entity.
     */
    @Nullable
    static String validateDormantSource(
            @Nonnull UUID previousNpcUuid,
            @Nullable UUID currentNpcUuid,
            @Nullable UUID requestedOwnerId,
            @Nonnull OwnerPopulationEntry owner,
            @Nonnull ClaimOccupancyEntry claim
    ) {
        if (!previousNpcUuid.equals(currentNpcUuid)) {
            return "coop-release-duplicate-active-profile";
        }
        if (owner.lifecycleState() != CompanionLifecycleState.COOP
                || claim.lifecycleState() != CompanionLifecycleState.COOP) {
            return "coop-release-profile-not-cooped";
        }
        if (owner.revision() != claim.revision()
                || !Objects.equals(owner.ownerId(), claim.ownerId())
                || !Objects.equals(owner.ownerId(), requestedOwnerId)) {
            return "coop-release-population-state-mismatch";
        }
        return null;
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            @Nonnull ReleaseRequest request,
            @Nonnull String profileId,
            @Nonnull OwnerPopulationEntry owner,
            @Nonnull ClaimOccupancyEntry claim
    ) {
        long now = System.currentTimeMillis();
        ClaimChunkCoordinate physical = claim.physicalChunk();
        return new CompanionPopulationStateRecord(
                profileId,
                request.previousNpcUuid(),
                owner.ownerId(),
                physical == null ? request.worldName() : physical.worldName(),
                owner.ownershipWorldName(),
                owner.lifecycleState().name(),
                physical == null ? null : physical.worldName(),
                physical == null ? null : physical.chunkX(),
                physical == null ? null : physical.chunkZ(),
                owner.revision(),
                "coop_release",
                now,
                now
        );
    }

    @Nonnull
    private static String ownerJson(@Nullable UUID ownerId) {
        JsonObject json = new JsonObject();
        if (ownerId == null) {
            json.add("ownerUuid", null);
        } else {
            json.addProperty("ownerUuid", ownerId.toString());
        }
        return json.toString();
    }

    @Nonnull
    static String contextJson(
            @Nonnull ReleaseRequest request,
            @Nullable Function<UUID, String> durableContextFactory
    ) {
        UUID plannedUuid = request.plannedNpcUuid();
        JsonObject json = new JsonObject();
        json.addProperty("operation", "coop_release");
        json.addProperty("idempotencyKey", request.idempotencyKey());
        json.addProperty("previousNpcUuid", request.previousNpcUuid().toString());
        json.addProperty("plannedNpcUuid", plannedUuid.toString());
        json.addProperty("world", request.worldName());
        json.addProperty("chunkX", request.chunkX());
        json.addProperty("chunkZ", request.chunkZ());
        if (durableContextFactory != null) {
            String extensionJson = durableContextFactory.apply(plannedUuid);
            if (extensionJson == null || extensionJson.isBlank()) {
                throw new IllegalArgumentException("Durable context factory returned no context.");
            }
            JsonObject extension = JsonParser.parseString(extensionJson).getAsJsonObject();
            for (var field : extension.entrySet()) {
                if (json.has(field.getKey())) {
                    throw new IllegalArgumentException(
                            "Durable context cannot replace reserved field: " + field.getKey()
                    );
                }
                json.add(field.getKey(), field.getValue().deepCopy());
            }
        }
        return json.toString();
    }

    public record ReleaseRequest(@Nonnull UUID previousNpcUuid,
                                 @Nonnull UUID plannedNpcUuid,
                                 @Nullable UUID ownerId,
                                 @Nullable String ownerName,
                                 @Nonnull String worldName,
                                 int chunkX,
                                 int chunkZ,
                                 @Nonnull String idempotencyKey) {
        public ReleaseRequest {
            Objects.requireNonNull(previousNpcUuid, "previousNpcUuid");
            Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
            if (previousNpcUuid.equals(plannedNpcUuid)) {
                throw new IllegalArgumentException(
                        "plannedNpcUuid must differ from previousNpcUuid."
                );
            }
            worldName = Objects.requireNonNull(worldName, "worldName").trim();
            idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").trim();
            if (worldName.isEmpty() || idempotencyKey.isEmpty()) {
                throw new IllegalArgumentException("World and idempotency key are required.");
            }
        }
    }

    public record PreparedRelease(@Nonnull ReleaseRequest request,
                                  @Nonnull String profileId,
                                  @Nonnull UUID plannedNpcUuid,
                                  @Nonnull PreparedCompanionPopulationAdmission admission) {
        public PreparedRelease {
            Objects.requireNonNull(request, "request");
            profileId = OwnerPopulationEntry.normalizeProfileId(profileId);
            Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
            Objects.requireNonNull(admission, "admission");
            if (!request.plannedNpcUuid().equals(plannedNpcUuid)) {
                throw new IllegalArgumentException(
                        "Prepared release UUID must match the caller-planned UUID."
                );
            }
        }
    }

    /** Whether a failed preparation can safely release its managed-coop lifecycle claim. */
    public enum PreparationDisposition {
        PREPARED,
        DEFINITIVE_DENIAL,
        AMBIGUOUS
    }

    public record PreparationResult(boolean allowed,
                                    @Nonnull String reason,
                                    @Nullable PreparedRelease preparedRelease,
                                    @Nonnull PreparationDisposition disposition) {
        public PreparationResult {
            reason = Objects.requireNonNull(reason, "reason");
            disposition = Objects.requireNonNull(disposition, "disposition");
            if (allowed != (preparedRelease != null)
                    || (allowed != (disposition == PreparationDisposition.PREPARED))) {
                throw new IllegalArgumentException(
                        "Prepared release, allowed state, and disposition must agree.");
            }
        }

        static PreparationResult prepared(PreparedRelease release) {
            return new PreparationResult(
                    true,
                    "coop-release-population-prepared",
                    release,
                    PreparationDisposition.PREPARED);
        }

        static PreparationResult denied(@Nullable CompanionPopulationPreparationResult result) {
            String reason = result != null && result.reason() != null
                    ? result.reason()
                    : "coop-release-population-prepare-result-missing";
            PreparationDisposition disposition = definitivePreAdmissionDenial(result)
                    ? PreparationDisposition.DEFINITIVE_DENIAL
                    : PreparationDisposition.AMBIGUOUS;
            return new PreparationResult(false, reason, null, disposition);
        }
    }

    /**
     * Allows rollback only for policy denials proven to occur before owner-journal preparation.
     * Pending, degraded, identity, revision, and unknown outcomes can describe a replay of an
     * already-APPLYING release and must therefore retain the managed-coop lifecycle journal.
     */
    static boolean definitivePreAdmissionDenial(
            @Nullable CompanionPopulationPreparationResult result) {
        if (result == null || result.reason() == null) {
            return false;
        }
        if (result.ownerDecision() != null
                && result.ownerDecision().readiness() != OwnerPopulationReadiness.READY) {
            return false;
        }
        if (result.claimDecision() != null
                && result.claimDecision().readiness()
                != com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness.READY) {
            return false;
        }
        return switch (result.reason()) {
            case "owner-cap-reached",
                    "owner-cap-world-context-required",
                    "claim-cap-reached",
                    "claim-required",
                    "claim-footprint-required" -> true;
            default -> false;
        };
    }

    private record PlanResult(boolean allowed,
                              @Nonnull String reason,
                              @Nonnull PreparationDisposition disposition,
                              @Nullable String profileId,
                              @Nullable UUID plannedNpcUuid,
                              @Nullable CompanionAdmissionPolicyResolver.Policy policy,
                              @Nullable OwnerPopulationAdmissionPlan ownerPlan,
                              @Nullable ClaimAdmissionRequest claimRequest) {
        static PlanResult allowed(String profileId,
                                  UUID plannedNpcUuid,
                                  CompanionAdmissionPolicyResolver.Policy policy,
                                  OwnerPopulationAdmissionPlan ownerPlan,
                                  ClaimAdmissionRequest claimRequest) {
            return new PlanResult(true, "coop-release-population-planned",
                    PreparationDisposition.PREPARED, profileId,
                    plannedNpcUuid, policy, ownerPlan, claimRequest);
        }

        static PlanResult denied(String reason) {
            return new PlanResult(false, reason, PreparationDisposition.AMBIGUOUS,
                    null, null, null, null, null);
        }

        static PlanResult definitivelyDenied(String reason) {
            return new PlanResult(false, reason, PreparationDisposition.DEFINITIVE_DENIAL,
                    null, null, null, null, null);
        }
    }
}
