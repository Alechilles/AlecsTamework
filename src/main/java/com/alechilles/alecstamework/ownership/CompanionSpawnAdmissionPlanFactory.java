package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the immutable owner/claim plan for one already-validated spawn source. */
final class CompanionSpawnAdmissionPlanFactory {
    private CompanionSpawnAdmissionPlanFactory() {
    }

    @Nonnull
    static CompanionPopulationAdmissionUnit create(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid,
            @Nonnull OwnerPopulationOperation operation,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate(
                request.worldName(), request.chunkX(), request.chunkZ()
        );
        ClaimOccupancyEntry proposedClaim = new ClaimOccupancyEntry(
                profileId,
                request.ownerId(),
                CompanionLifecycleState.ACTIVE,
                destination,
                claim == null ? 1L : increment(claim.revision())
        );
        OwnerPopulationTransitionRequest transition = new OwnerPopulationTransitionRequest(
                profileId,
                owner == null ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION : owner.revision(),
                owner == null ? null : owner.ownerId(),
                owner == null ? null : owner.ownershipWorldName(),
                request.ownerId(),
                request.ownerId() == null ? null : request.worldName(),
                CompanionLifecycleState.ACTIVE,
                operation,
                policy.scope(),
                policy.limit(),
                request.force()
        );
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition,
                baseline(request, profileId, plannedNpcUuid, owner, claim),
                plannedNpcUuid,
                request.worldName(),
                request.chunkX(),
                request.chunkZ(),
                request.sourceKind(),
                ownerJson(owner == null ? null : owner.ownerId()),
                ownerJson(request.ownerId()),
                contextJson(request, plannedNpcUuid, operation),
                policy.settingsRevision(),
                policy.claimContext().providerGeneration(),
                request.targetRoleId() == null ? null
                        : PopulationGroupRoleContext.unchanged(request.targetRoleId())
        );
        ClaimOccupancyTransition claimTransition = new ClaimOccupancyTransition(claim, proposedClaim);
        ClaimAdmissionRequest claimRequest = new ClaimAdmissionRequest(
                claimOperation(operation),
                List.of(claimTransition),
                proposedClaim.occupiesClaim() ? destination : null,
                policy.claimContext(),
                policy.claimLimitPerChunk(),
                policy.claimLimitTotal(),
                false,
                request.force(),
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
        return new CompanionPopulationAdmissionUnit(ownerPlan, claimRequest);
    }

    @Nonnull
    private static CompanionPopulationStateRecord baseline(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid,
            @Nullable OwnerPopulationEntry owner,
            @Nullable ClaimOccupancyEntry claim
    ) {
        long now = System.currentTimeMillis();
        ClaimChunkCoordinate physical = claim == null ? null : claim.physicalChunk();
        return new CompanionPopulationStateRecord(
                profileId,
                request.previousNpcUuid() == null ? plannedNpcUuid : request.previousNpcUuid(),
                owner == null ? null : owner.ownerId(),
                physical == null ? request.worldName() : physical.worldName(),
                owner == null ? request.worldName() : owner.ownershipWorldName(),
                owner == null
                        ? (request.requiredSourceLifecycle() == null
                        ? CompanionLifecycleState.ACTIVE.name()
                        : request.requiredSourceLifecycle().name())
                        : owner.lifecycleState().name(),
                physical == null ? null : physical.worldName(),
                physical == null ? null : physical.chunkX(),
                physical == null ? null : physical.chunkZ(),
                owner == null ? 0L : owner.revision(),
                request.sourceKind(),
                now,
                now
        );
    }

    private static long increment(long revision) {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Companion population revision exhausted.");
        }
        return revision + 1L;
    }

    @Nonnull
    private static ClaimAdmissionOperation claimOperation(@Nonnull OwnerPopulationOperation operation) {
        return switch (operation) {
            case RESTORE, LEGACY_ADOPTION -> ClaimAdmissionOperation.SPAWNER_RELEASE;
            case ADMIN_FORCE, NEW_OWNERSHIP, OWNER_TRANSFER -> ClaimAdmissionOperation.SET_OWNER;
            case REHOME -> ClaimAdmissionOperation.TELEPORT;
            case BREEDING -> ClaimAdmissionOperation.BREED;
            case OWNER_CLEAR, LIFECYCLE_CHANGE -> ClaimAdmissionOperation.EXTERNAL;
        };
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
    private static String contextJson(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull UUID plannedNpcUuid,
            @Nonnull OwnerPopulationOperation operation
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("operation", operation.name().toLowerCase(Locale.ROOT));
        json.addProperty("idempotencyKey", request.idempotencyKey());
        if (request.previousNpcUuid() != null) {
            json.addProperty("previousNpcUuid", request.previousNpcUuid().toString());
        }
        json.addProperty("plannedNpcUuid", plannedNpcUuid.toString());
        json.addProperty("world", request.worldName());
        json.addProperty("chunkX", request.chunkX());
        json.addProperty("chunkZ", request.chunkZ());
        if (request.durableContextJson() != null) {
            JsonObject extension = JsonParser.parseString(
                    request.durableContextJson()
            ).getAsJsonObject();
            for (var field : extension.entrySet()) {
                if (json.has(field.getKey())) {
                    throw new IllegalArgumentException(
                            "Durable spawn context cannot replace reserved field: " + field.getKey()
                    );
                }
                json.add(field.getKey(), field.getValue().deepCopy());
            }
        }
        return json.toString();
    }
}
