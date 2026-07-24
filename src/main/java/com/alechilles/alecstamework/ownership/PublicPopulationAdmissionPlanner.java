package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.PopulationAdmissionIdentity;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.api.PopulationAdmissionOperation;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionRequestV2;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Validates a public request and translates it into one internal owner-and-claim admission unit. */
final class PublicPopulationAdmissionPlanner {
    private static final String SOURCE = "public_population_api";

    private final OwnerPopulationIndex ownerIndex;
    private final CompanionIdentityResolver identityResolver;
    private final ClaimOccupancyIndex claimIndex;
    private final CompanionAdmissionPolicyResolver policyResolver;
    private final CanonicalRoleResolver roleResolver;

    PublicPopulationAdmissionPlanner(@Nonnull OwnerPopulationIndex ownerIndex,
                                     @Nonnull CompanionIdentityResolver identityResolver,
                                     @Nonnull ClaimOccupancyIndex claimIndex,
                                     @Nonnull CompanionAdmissionPolicyResolver policyResolver) {
        this(ownerIndex, identityResolver, claimIndex, policyResolver, profileId -> null);
    }

    PublicPopulationAdmissionPlanner(@Nonnull OwnerPopulationIndex ownerIndex,
            @Nonnull CompanionIdentityResolver identityResolver,
            @Nonnull ClaimOccupancyIndex claimIndex,
            @Nonnull CompanionAdmissionPolicyResolver policyResolver,
            @Nonnull CanonicalRoleResolver roleResolver) {
        this.ownerIndex = Objects.requireNonNull(ownerIndex, "ownerIndex");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver");
        this.claimIndex = Objects.requireNonNull(claimIndex, "claimIndex");
        this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
    }

    @Nonnull
    Result plan(@Nonnull PopulationAdmissionRequest request) {
        return plan(request, null, null);
    }

    @Nonnull Result plan(@Nonnull PopulationAdmissionRequestV2 request) {
        Objects.requireNonNull(request, "request");
        return plan(request.request(), request.targetRoleId(), request.ownershipWorldName());
    }

    @Nonnull
    private Result plan(@Nonnull PopulationAdmissionRequest request, @Nullable String targetRoleId,
                        @Nullable String ownershipWorldName) {
        Objects.requireNonNull(request, "request");
        if (request.currentNpcUuid() == null) {
            return Result.denied("population-admission-current-npc-required");
        }
        IdentityResult identity = resolveIdentity(request);
        if (!identity.allowed()) {
            return Result.denied(identity.reason());
        }
        try {
        OwnerPopulationEntry currentOwner = ownerIndex.entry(identity.profileId()).orElse(null);
        ClaimOccupancyEntry currentClaim = claimIndex.entry(identity.profileId()).orElse(null);
        String representationReason = validateCurrentRepresentation(
                request,
                identityResolver.currentNpcUuid(identity.profileId()).orElse(null),
                currentOwner
        );
        if (representationReason != null) {
            return deniedAfterIdentity(identity, request.currentNpcUuid(), representationReason);
        }
        String stateReason = validateExpectedState(request, currentOwner, currentClaim);
        if (stateReason != null) {
            return deniedAfterIdentity(identity, request.currentNpcUuid(), stateReason);
        }
        String oldRoleId = null;
        if (targetRoleId != null
                && request.expectedProfileRevision() != PopulationAdmissionRequest.NEW_PROFILE_REVISION) {
            oldRoleId = roleResolver.resolve(identity.profileId());
            if (oldRoleId == null) {
                return deniedAfterIdentity(identity, request.currentNpcUuid(),
                        "population-admission-source-role-unavailable");
            }
        }
        ClaimOccupancyTransition claimTransition = claimTransition(
                identity.profileId(), request, currentClaim
        );
        boolean claimPolicyRelevant = request.operation() == PopulationAdmissionOperation.BREEDING
                || !claimTransition.isKnownNonPositiveAtSameLocation();
        OwnerPopulationOperation operation = OwnerPopulationOperation.valueOf(request.operation().name());
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                operation,
                claimPolicyRelevant
        );
        OwnerPopulationAdmissionPlan ownerPlan = ownerPlan(
                identity.profileId(),
                request,
                identityResolver.currentNpcUuid(identity.profileId()).orElse(request.currentNpcUuid()),
                currentOwner,
                currentClaim,
                claimTransition.proposed(),
                operation,
                policy,
                ownershipWorldName,
                targetRoleId == null ? null
                        : new PopulationGroupRoleContext(oldRoleId, targetRoleId)
        );
        ClaimAdmissionRequest claimRequest = claimRequest(
                request,
                operation,
                claimTransition,
                policy
        );
        return Result.allowed(
                identity.profileId(),
                request.currentNpcUuid(),
                identity.provisional(),
                policy,
                new CompanionPopulationAdmissionUnit(ownerPlan, claimRequest)
        );
        } catch (RuntimeException | LinkageError failure) {
            releaseProvisional(identity.profileId(), request.currentNpcUuid(), identity.provisional());
            throw failure;
        }
    }

    @Nonnull
    private IdentityResult resolveIdentity(PopulationAdmissionRequest request) {
        PopulationAdmissionIdentity identity = request.identity();
        UUID npcUuid = request.currentNpcUuid();
        String profileId;
        boolean provisional = false;
        if (identity.canonicalProfileId() != null) {
            profileId = identity.canonicalProfileId();
        } else if (identity.provisionalProfileId() != null) {
            profileId = identity.provisionalProfileId();
        } else {
            try {
                CompanionIdentityResolver.Resolution resolution = identityResolver.resolveOrAllocate(
                        npcUuid,
                        identity.idempotencyKey()
                );
                profileId = resolution.profileId();
                provisional = resolution.provisional();
            } catch (IllegalArgumentException exception) {
                return IdentityResult.denied("population-admission-idempotency-conflict");
            }
        }

        String mappedProfile = identityResolver.resolveProfileId(npcUuid).orElse(null);
        if (mappedProfile != null && !mappedProfile.equals(profileId)) {
            return IdentityResult.denied("population-admission-npc-identity-conflict");
        }
        return IdentityResult.allowed(profileId, provisional);
    }

    void releaseProvisional(@Nonnull Result result) {
        if (result.allowed() && result.currentNpcUuid() != null) {
            releaseProvisional(result.profileId(), result.currentNpcUuid(), result.provisionalIdentity());
        }
    }

    @Nonnull
    private Result deniedAfterIdentity(@Nonnull IdentityResult identity,
                                       @Nonnull UUID npcUuid,
                                       @Nonnull String reason) {
        releaseProvisional(identity.profileId(), npcUuid, identity.provisional());
        return Result.denied(reason);
    }

    private void releaseProvisional(@Nonnull String profileId,
                                    @Nonnull UUID npcUuid,
                                    boolean provisional) {
        if (provisional) {
            identityResolver.releaseProvisional(profileId, npcUuid);
        }
    }

    @Nullable
    static String validateCurrentRepresentation(
            @Nonnull PopulationAdmissionRequest request,
            @Nullable UUID authoritativeCurrentUuid,
            @Nullable OwnerPopulationEntry currentOwner
    ) {
        if (request.expectedProfileRevision() == PopulationAdmissionRequest.NEW_PROFILE_REVISION) {
            return null;
        }
        if (authoritativeCurrentUuid == null) {
            return "population-admission-current-npc-unavailable";
        }
        boolean sameUuid = authoritativeCurrentUuid.equals(request.currentNpcUuid());
        if (request.operation() != PopulationAdmissionOperation.RESTORE) {
            return sameUuid ? null : "population-admission-current-npc-mismatch";
        }
        if (currentOwner == null) {
            return "owner-population-profile-missing";
        }
        CompanionLifecycleState lifecycle = currentOwner.lifecycleState();
        if (lifecycle == CompanionLifecycleState.ACTIVE
                || lifecycle == CompanionLifecycleState.RESTORING
                || lifecycle == CompanionLifecycleState.STORING) {
            return "population-admission-duplicate-active-profile";
        }
        if (lifecycle == CompanionLifecycleState.UNLOADED) {
            return sameUuid ? null : "population-admission-duplicate-active-profile";
        }
        return lifecycle == CompanionLifecycleState.CAPTURED
                || lifecycle == CompanionLifecycleState.COOP
                || lifecycle == CompanionLifecycleState.DEAD_REVIVABLE
                || lifecycle == CompanionLifecycleState.LOST
                || lifecycle == CompanionLifecycleState.ROSTER_STORED
                ? null : "population-admission-restore-source-not-authoritative";
    }

    @Nullable
    private static String validateExpectedState(PopulationAdmissionRequest request,
                                                @Nullable OwnerPopulationEntry owner,
                                                @Nullable ClaimOccupancyEntry claim) {
        if (request.expectedProfileRevision() == PopulationAdmissionRequest.NEW_PROFILE_REVISION) {
            return owner == null && claim == null
                    ? null
                    : "population-admission-profile-already-exists";
        }
        if (owner == null) {
            return "owner-population-profile-missing";
        }
        if (claim == null) {
            return "claim-occupancy-profile-missing";
        }
        UUID expectedOwner = request.oldOwnerUuid();
        if (request.operation() == PopulationAdmissionOperation.RESTORE && expectedOwner == null) {
            expectedOwner = request.newOwnerUuid();
        }
        if (owner.revision() != request.expectedProfileRevision()
                || claim.revision() != request.expectedProfileRevision()) {
            return "population-admission-revision-mismatch";
        }
        if (!Objects.equals(owner.ownerId(), expectedOwner)
                || !Objects.equals(claim.ownerId(), expectedOwner)) {
            return "population-admission-owner-mismatch";
        }
        PopulationAdmissionLocation source = request.source();
        if (source != null && !sameChunk(claim.physicalChunk(), source)) {
            return "population-admission-source-mismatch";
        }
        return null;
    }

    @Nonnull
    private static ClaimOccupancyTransition claimTransition(
            @Nonnull String profileId,
            @Nonnull PopulationAdmissionRequest request,
            @Nullable ClaimOccupancyEntry current
    ) {
        if (current != null && current.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("Claim occupancy revision exhausted.");
        }
        ClaimChunkCoordinate physical = proposedPhysicalLocation(request, current);
        ClaimOccupancyEntry proposed = new ClaimOccupancyEntry(
                profileId,
                request.newOwnerUuid(),
                lifecycle(request),
                physical,
                current == null ? 1L : current.revision() + 1L
        );
        return new ClaimOccupancyTransition(current, proposed);
    }

    @Nullable
    private static ClaimChunkCoordinate proposedPhysicalLocation(
            @Nonnull PopulationAdmissionRequest request,
            @Nullable ClaimOccupancyEntry current
    ) {
        if (request.destination() != null) {
            return chunk(request.destination());
        }
        return current == null ? null : current.physicalChunk();
    }

    @Nonnull
    private static OwnerPopulationAdmissionPlan ownerPlan(
            @Nonnull String profileId,
            @Nonnull PopulationAdmissionRequest request,
            @Nonnull UUID baselineNpcUuid,
            @Nullable OwnerPopulationEntry currentOwner,
            @Nullable ClaimOccupancyEntry currentClaim,
            @Nonnull ClaimOccupancyEntry proposedClaim,
            @Nonnull OwnerPopulationOperation operation,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy,
            @Nullable String ownershipWorldName,
            @Nullable PopulationGroupRoleContext roleContext
    ) {
        String destinationWorld = request.newOwnerUuid() == null
                ? null
                : ownershipWorldName != null
                        ? ownershipWorldName
                        : request.destination() == null
                                ? currentOwner.ownershipWorldName()
                                : request.destination().worldName();
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                request.expectedProfileRevision(),
                currentOwner == null ? null : currentOwner.ownerId(),
                currentOwner == null ? null : currentOwner.ownershipWorldName(),
                request.newOwnerUuid(),
                destinationWorld,
                lifecycle(request),
                operation,
                policy.scope(),
                policy.limit(),
                request.forcePolicy() != com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy.ENFORCE
        );
        CompanionPopulationStateRecord baseline = baseline(
                profileId,
                request,
                baselineNpcUuid,
                currentOwner,
                currentClaim
        );
        ClaimChunkCoordinate finalPhysical = proposedClaim.physicalChunk();
        return new OwnerPopulationAdmissionPlan(
                transition,
                baseline,
                request.currentNpcUuid(),
                finalPhysical == null ? null : finalPhysical.worldName(),
                finalPhysical == null ? null : finalPhysical.chunkX(),
                finalPhysical == null ? null : finalPhysical.chunkZ(),
                SOURCE,
                ownerJson(currentOwner == null ? null : currentOwner.ownerId()),
                ownerJson(request.newOwnerUuid()),
                contextJson(request),
                policy.settingsRevision(),
                policy.claimContext().providerGeneration(),
                roleContext
        );
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            @Nonnull String profileId,
            @Nonnull PopulationAdmissionRequest request,
            @Nonnull UUID baselineNpcUuid,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim
    ) {
        long now = System.currentTimeMillis();
        ClaimChunkCoordinate physical = claim == null ? null : claim.physicalChunk();
        CompanionLifecycleState currentLifecycle = owner == null
                ? CompanionLifecycleState.ACTIVE
                : owner.lifecycleState();
        return new CompanionPopulationStateRecord(
                profileId,
                baselineNpcUuid,
                owner == null ? null : owner.ownerId(),
                physical == null ? null : physical.worldName(),
                owner == null ? null : owner.ownershipWorldName(),
                currentLifecycle.name(),
                physical == null ? null : physical.worldName(),
                physical == null ? null : physical.chunkX(),
                physical == null ? null : physical.chunkZ(),
                owner == null ? 0L : owner.revision(),
                SOURCE,
                now,
                now
        );
    }

    @Nonnull
    private static ClaimAdmissionRequest claimRequest(
            @Nonnull PopulationAdmissionRequest request,
            @Nonnull OwnerPopulationOperation operation,
            @Nonnull ClaimOccupancyTransition transition,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        ClaimChunkCoordinate destination = transition.proposed().occupiesClaim()
                || (request.operation() == PopulationAdmissionOperation.BREEDING && request.destination() != null)
                ? chunk(request.destination())
                : null;
        return new ClaimAdmissionRequest(
                claimOperation(operation),
                List.of(transition),
                destination,
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                policy.requireClaim(),
                request.forcePolicy() != com.alechilles.alecstamework.api.PopulationAdmissionForcePolicy.ENFORCE,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
    }

    @Nonnull
    private static CompanionLifecycleState lifecycle(@Nonnull PopulationAdmissionRequest request) {
        return CompanionLifecycleState.valueOf(request.targetLifecycle().name());
    }

    @Nonnull
    private static ClaimAdmissionOperation claimOperation(OwnerPopulationOperation operation) {
        return switch (operation) {
            case BREEDING -> ClaimAdmissionOperation.BREED;
            case RESTORE, LEGACY_ADOPTION -> ClaimAdmissionOperation.SPAWNER_RELEASE;
            case REHOME -> ClaimAdmissionOperation.TELEPORT;
            case NEW_OWNERSHIP, OWNER_TRANSFER, ADMIN_FORCE -> ClaimAdmissionOperation.SET_OWNER;
            case OWNER_CLEAR, LIFECYCLE_CHANGE -> ClaimAdmissionOperation.EXTERNAL;
        };
    }

    @Nonnull
    private static ClaimChunkCoordinate chunk(@Nonnull PopulationAdmissionLocation location) {
        return new ClaimChunkCoordinate(location.worldName(), location.chunkX(), location.chunkZ());
    }

    private static boolean sameChunk(@Nullable ClaimChunkCoordinate chunk,
                                     @Nonnull PopulationAdmissionLocation location) {
        return chunk != null
                && chunk.worldName().equals(location.worldName())
                && chunk.chunkX() == location.chunkX()
                && chunk.chunkZ() == location.chunkZ();
    }

    @Nonnull
    private static String ownerJson(@Nullable UUID ownerUuid) {
        JsonObject json = new JsonObject();
        if (ownerUuid == null) {
            json.add("ownerUuid", null);
        } else {
            json.addProperty("ownerUuid", ownerUuid.toString());
        }
        return json.toString();
    }

    @Nonnull
    private static String contextJson(@Nonnull PopulationAdmissionRequest request) {
        JsonObject json = new JsonObject();
        PopulationAdmissionLocation location = request.destination() == null
                ? request.source()
                : request.destination();
        if (location != null) {
            json.addProperty("world", location.worldName());
            json.addProperty("chunkX", location.chunkX());
            json.addProperty("chunkZ", location.chunkZ());
        }
        json.addProperty("npcUuid", request.currentNpcUuid().toString());
        return json.toString();
    }

    record Result(boolean allowed,
                  @Nonnull String reason,
                  @Nullable String profileId,
                  @Nullable UUID currentNpcUuid,
                  boolean provisionalIdentity,
                  @Nullable CompanionAdmissionPolicyResolver.Policy policy,
                  @Nullable CompanionPopulationAdmissionUnit unit) {
        @Nonnull
        static Result allowed(String profileId,
                              UUID currentNpcUuid,
                              boolean provisionalIdentity,
                              CompanionAdmissionPolicyResolver.Policy policy,
                              CompanionPopulationAdmissionUnit unit) {
            return new Result(
                    true, "population-admission-planned", profileId, currentNpcUuid,
                    provisionalIdentity, policy, unit
            );
        }

        @Nonnull
        static Result denied(String reason) {
            return new Result(false, reason, null, null, false, null, null);
        }
    }

    private record IdentityResult(boolean allowed,
                                  @Nullable String profileId,
                                  boolean provisional,
                                  @Nonnull String reason) {
        static IdentityResult allowed(String profileId, boolean provisional) {
            return new IdentityResult(
                    true, profileId, provisional, "population-admission-identity-resolved"
            );
        }

        static IdentityResult denied(String reason) {
            return new IdentityResult(false, null, false, reason);
        }
    }

    @FunctionalInterface interface CanonicalRoleResolver {
        @Nullable String resolve(@Nonnull String profileId);
    }
}
