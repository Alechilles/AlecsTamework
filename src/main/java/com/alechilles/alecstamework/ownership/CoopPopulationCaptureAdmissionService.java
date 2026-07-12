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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prepares one canonical population transition before a schema-v5 managed-coop slot is claimed.
 *
 * <p>This service owns no coop occupancy state. It validates the exact canonical source and embeds
 * the caller's v5 capture request in the population journal context so the persistence layer can
 * commit that request with population state in one transaction.</p>
 */
public final class CoopPopulationCaptureAdmissionService {
    private static final String OPERATION_NAME = "managed_coop_capture";

    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionPopulationAdmissionCoordinator coordinator;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final ClaimLookupMetrics lookupMetrics;

    CoopPopulationCaptureAdmissionService(
            @Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull ClaimProviderRegistry providerRegistry,
            @Nonnull CompanionPopulationAdmissionCoordinator coordinator,
            @Nonnull ClaimLookupMetrics lookupMetrics) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.policyResolver = new CompanionAdmissionPolicyResolver(
                claimIndex, Objects.requireNonNull(providerRegistry, "providerRegistry"));
        this.lookupMetrics = Objects.requireNonNull(lookupMetrics, "lookupMetrics");
    }

    /** Prepares owner and claim reservations without performing any coop or source mutation. */
    @Nonnull
    public CompletableFuture<PreparationResult> prepareAsync(
            @Nonnull CaptureRequest request,
            @Nonnull DurableContextFactory durableContextFactory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(durableContextFactory, "durableContextFactory");
        PlanResult planned = plan(request, durableContextFactory);
        if (!planned.allowed()) {
            return CompletableFuture.completedFuture(PreparationResult.denied(planned.reason()));
        }
        ClaimLookupSession session = new ClaimLookupSession(
                planned.policy().claimContext(),
                planned.policy().claimLimitPerChunk() > 0,
                lookupMetrics
        );
        try {
            CompletableFuture<CompanionPopulationPreparationResult> preparation =
                    coordinator.prepareAsync(
                            planned.ownerPlan(), planned.claimRequest(), session);
            if (preparation == null) {
                return CompletableFuture.completedFuture(
                        PreparationResult.denied("coop-capture-population-prepare-stage-missing"));
            }
            return preparation.thenApply(result ->
                    result != null && result.allowed() && result.preparedAdmission() != null
                            ? PreparationResult.prepared(new PreparedCapture(
                                    request, result.preparedAdmission()))
                            : PreparationResult.denied(result == null
                                    ? "coop-capture-population-prepare-result-missing"
                                    : result.reason()));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    PreparationResult.denied("coop-capture-population-prepare-failed"));
        }
    }

    /** Revalidates the shared settings/provider generation and claims this exact transition. */
    public boolean claimForCommit(@Nonnull PreparedCapture prepared) {
        Objects.requireNonNull(prepared, "prepared");
        try {
            CompanionAdmissionPolicyResolver.Policy current = policyResolver.resolve(
                    OwnerPopulationOperation.LIFECYCLE_CHANGE, false);
            ClaimLookupSession session = new ClaimLookupSession(
                    current.claimContext(), current.claimLimitPerChunk() > 0, lookupMetrics);
            return coordinator.claimForApply(
                    prepared.admission(), current.settingsRevision(), session);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Commits the already-claimed population capability. */
    @Nonnull
    public CompletableFuture<CompanionPopulationCommitResult> commitAsync(
            @Nonnull PreparedCapture prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return coordinator.commitAsync(prepared.admission());
    }

    /** Cancels a prepared capture before its combined persistence mutation begins. */
    @Nonnull
    public CompletableFuture<Boolean> cancelAsync(
            @Nonnull PreparedCapture prepared,
            @Nonnull String reason) {
        Objects.requireNonNull(prepared, "prepared");
        return coordinator.cancelAsync(prepared.admission(), requireText(reason, "reason"));
    }

    @Nonnull
    private PlanResult plan(CaptureRequest request, DurableContextFactory contextFactory) {
        String mappedProfile;
        UUID currentNpcUuid;
        try {
            mappedProfile = identityResolver.resolveProfileId(request.sourceNpcUuid()).orElse(null);
            currentNpcUuid = identityResolver.currentNpcUuid(request.profileId()).orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            return PlanResult.denied("coop-capture-canonical-identity-unavailable");
        }
        try {
            return planResolved(
                    request,
                    mappedProfile,
                    currentNpcUuid,
                    ownerIndex.entry(request.profileId()).orElse(null),
                    claimIndex.entry(request.profileId()).orElse(null),
                    policyResolver.resolve(OwnerPopulationOperation.LIFECYCLE_CHANGE, false),
                    contextFactory
            );
        } catch (RuntimeException | LinkageError failure) {
            return PlanResult.denied("coop-capture-population-plan-unavailable");
        }
    }

    @Nonnull
    static PlanResult planResolved(
            @Nonnull CaptureRequest request,
            @Nullable String mappedProfile,
            @Nullable UUID currentNpcUuid,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy,
            @Nonnull DurableContextFactory contextFactory) {
        String invalid = validateSource(
                request, mappedProfile, currentNpcUuid, owner, claim);
        if (invalid != null) {
            return PlanResult.denied(invalid);
        }
        final String targetContext;
        try {
            targetContext = contextJson(request, contextFactory);
        } catch (RuntimeException | LinkageError failure) {
            return PlanResult.denied("coop-capture-durable-context-invalid");
        }

        boolean newProfile = request.newlyEnsuredUnownedProfile();
        long expectedRevision = newProfile
                ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION
                : owner.revision();
        long nextRevision = newProfile ? 1L : owner.revision() + 1L;
        UUID expectedOwnerId = newProfile ? null : owner.ownerId();
        String expectedWorld = newProfile ? null : owner.ownershipWorldName();
        ClaimOccupancyEntry proposedClaim = new ClaimOccupancyEntry(
                request.profileId(), request.ownerId(), CompanionLifecycleState.COOP,
                null, nextRevision);
        ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(
                claim, proposedClaim);
        OwnerPopulationTransitionRequest ownerTransition = new OwnerPopulationTransitionRequest(
                request.profileId(),
                expectedRevision,
                expectedOwnerId,
                expectedWorld,
                request.ownerId(),
                expectedWorld,
                CompanionLifecycleState.COOP,
                OwnerPopulationOperation.LIFECYCLE_CHANGE,
                policy.scope(),
                policy.limit(),
                false
        );
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                ownerTransition,
                baseline(request, owner, claim),
                request.sourceNpcUuid(),
                null,
                null,
                null,
                OPERATION_NAME,
                ownerJson(expectedOwnerId),
                ownerJson(request.ownerId()),
                targetContext,
                policy.settingsRevision(),
                policy.claimContext().providerGeneration()
        );
        ClaimAdmissionRequest claimRequest = new ClaimAdmissionRequest(
                ClaimAdmissionOperation.COOP_CAPTURE,
                List.of(claimTransition),
                null,
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                false,
                false,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
        return PlanResult.allowed(policy, ownerPlan, claimRequest);
    }

    /** Validates both canonical alias directions and the exact paired population projection. */
    @Nullable
    static String validateSource(
            @Nonnull CaptureRequest request,
            @Nullable String mappedProfile,
            @Nullable UUID currentNpcUuid,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim) {
        Objects.requireNonNull(request, "request");
        if (mappedProfile == null || currentNpcUuid == null) {
            return "coop-capture-canonical-identity-unavailable";
        }
        if (!request.profileId().equals(mappedProfile)) {
            return "coop-capture-source-profile-mismatch";
        }
        if (!request.sourceNpcUuid().equals(currentNpcUuid)) {
            return "coop-capture-duplicate-active-profile";
        }
        if (request.newlyEnsuredUnownedProfile()) {
            return owner == null && claim == null
                    ? null : "coop-capture-new-profile-population-present";
        }
        if (owner == null || claim == null) {
            return "coop-capture-population-profile-unavailable";
        }
        if (!request.profileId().equals(owner.profileId())
                || !request.profileId().equals(claim.profileId())) {
            return "coop-capture-population-profile-mismatch";
        }
        if (!Objects.equals(request.ownerId(), owner.ownerId())) {
            return "coop-capture-owner-mismatch";
        }
        if (!Objects.equals(owner.ownerId(), claim.ownerId())
                || owner.revision() != claim.revision()) {
            return "coop-capture-population-state-mismatch";
        }
        if (owner.revision() == Long.MAX_VALUE) {
            return "coop-capture-population-revision-exhausted";
        }
        if (owner.lifecycleState() != claim.lifecycleState()) {
            return "coop-capture-population-state-mismatch";
        }
        return switch (request.sourceKind()) {
            case LIVE_ENTITY -> validatePhysicalSource(request, claim);
            case CAPTURED_ITEM -> owner.lifecycleState() == CompanionLifecycleState.CAPTURED
                    ? null : "coop-capture-profile-not-captured";
        };
    }

    @Nullable
    private static String validatePhysicalSource(
            CaptureRequest request,
            ClaimOccupancyEntry claim) {
        CompanionLifecycleState lifecycle = claim.lifecycleState();
        if (lifecycle != CompanionLifecycleState.ACTIVE
                && lifecycle != CompanionLifecycleState.UNLOADED) {
            return "coop-capture-profile-not-physical";
        }
        return Objects.equals(request.sourceChunk(), claim.physicalChunk())
                ? null : "coop-capture-source-location-mismatch";
    }

    @Nonnull
    static String contextJson(
            @Nonnull CaptureRequest request,
            @Nonnull DurableContextFactory contextFactory) {
        String extensionJson = contextFactory.create(request.profileId());
        if (extensionJson == null || extensionJson.isBlank()) {
            throw new IllegalArgumentException("Durable capture context is required.");
        }
        JsonObject extension = JsonParser.parseString(extensionJson).getAsJsonObject();
        if (extension.size() == 0) {
            throw new IllegalArgumentException("Durable capture context must not be empty.");
        }
        JsonObject root = new JsonObject();
        root.addProperty("operation", OPERATION_NAME);
        root.addProperty("idempotencyKey", request.idempotencyKey());
        root.addProperty("profileId", request.profileId());
        root.addProperty("npcUuid", request.sourceNpcUuid().toString());
        root.addProperty("sourceKind", request.sourceKind().name().toLowerCase(Locale.ROOT));
        root.addProperty(
                "newlyEnsuredUnownedProfile", request.newlyEnsuredUnownedProfile());
        if (request.sourceChunk() != null) {
            root.addProperty("world", request.sourceChunk().worldName());
            root.addProperty("chunkX", request.sourceChunk().chunkX());
            root.addProperty("chunkZ", request.sourceChunk().chunkZ());
        }
        for (var field : extension.entrySet()) {
            if (root.has(field.getKey())) {
                throw new IllegalArgumentException(
                        "Durable context cannot replace reserved field: " + field.getKey());
            }
            root.add(field.getKey(), field.getValue().deepCopy());
        }
        return root.toString();
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            CaptureRequest request,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim) {
        long now = System.currentTimeMillis();
        if (owner == null) {
            ClaimChunkCoordinate physical = request.sourceChunk();
            return new CompanionPopulationStateRecord(
                    request.profileId(),
                    request.sourceNpcUuid(),
                    null,
                    physical.worldName(),
                    null,
                    CompanionLifecycleState.ACTIVE.name(),
                    physical.worldName(),
                    physical.chunkX(),
                    physical.chunkZ(),
                    0L,
                    OPERATION_NAME,
                    now,
                    now
            );
        }
        ClaimChunkCoordinate physical = claim.physicalChunk();
        String lastWorld = physical != null
                ? physical.worldName() : owner.ownershipWorldName();
        return new CompanionPopulationStateRecord(
                request.profileId(),
                request.sourceNpcUuid(),
                owner.ownerId(),
                lastWorld,
                owner.ownershipWorldName(),
                owner.lifecycleState().name(),
                physical == null ? null : physical.worldName(),
                physical == null ? null : physical.chunkX(),
                physical == null ? null : physical.chunkZ(),
                owner.revision(),
                OPERATION_NAME,
                now,
                now
        );
    }

    @Nonnull
    private static String ownerJson(@Nullable UUID ownerId) {
        JsonObject json = new JsonObject();
        if (ownerId == null) {
            json.add("ownerUuid", JsonNull.INSTANCE);
        } else {
            json.addProperty("ownerUuid", ownerId.toString());
        }
        return json.toString();
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum SourceKind {
        LIVE_ENTITY,
        CAPTURED_ITEM
    }

    /** Immutable canonical population source copied before asynchronous admission begins. */
    public record CaptureRequest(
            @Nonnull String profileId,
            @Nonnull UUID sourceNpcUuid,
            @Nullable UUID ownerId,
            @Nonnull SourceKind sourceKind,
            @Nullable ClaimChunkCoordinate sourceChunk,
            boolean newlyEnsuredUnownedProfile,
            @Nonnull String idempotencyKey) {
        public CaptureRequest {
            profileId = OwnerPopulationEntry.normalizeProfileId(profileId);
            Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid");
            Objects.requireNonNull(sourceKind, "sourceKind");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
            if (sourceKind == SourceKind.LIVE_ENTITY && sourceChunk == null) {
                throw new IllegalArgumentException("A live capture requires its exact source chunk.");
            }
            if (sourceKind == SourceKind.CAPTURED_ITEM && sourceChunk != null) {
                throw new IllegalArgumentException("A captured item cannot declare a live source chunk.");
            }
            if (newlyEnsuredUnownedProfile
                    && (ownerId != null || sourceKind != SourceKind.LIVE_ENTITY)) {
                throw new IllegalArgumentException(
                        "A newly ensured capture must be an unowned live profile.");
            }
        }
    }

    /** Builds the exact schema-v5 request extension after canonical profile validation. */
    @FunctionalInterface
    public interface DurableContextFactory {
        @Nonnull
        String create(@Nonnull String profileId);
    }

    public record PreparedCapture(
            @Nonnull CaptureRequest request,
            @Nonnull PreparedCompanionPopulationAdmission admission) {
        public PreparedCapture {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(admission, "admission");
        }
    }

    public record PreparationResult(
            boolean allowed,
            @Nonnull String reason,
            @Nullable PreparedCapture preparedCapture) {
        static PreparationResult prepared(PreparedCapture prepared) {
            return new PreparationResult(
                    true, "coop-capture-population-prepared", prepared);
        }

        static PreparationResult denied(String reason) {
            return new PreparationResult(false, reason, null);
        }
    }

    record PlanResult(
            boolean allowed,
            @Nonnull String reason,
            @Nullable CompanionAdmissionPolicyResolver.Policy policy,
            @Nullable OwnerPopulationAdmissionPlan ownerPlan,
            @Nullable ClaimAdmissionRequest claimRequest) {
        static PlanResult allowed(
                CompanionAdmissionPolicyResolver.Policy policy,
                OwnerPopulationAdmissionPlan ownerPlan,
                ClaimAdmissionRequest claimRequest) {
            return new PlanResult(
                    true, "coop-capture-population-planned",
                    policy, ownerPlan, claimRequest);
        }

        static PlanResult denied(String reason) {
            return new PlanResult(false, reason, null, null, null);
        }
    }
}
