package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds the immutable owner-and-claim durability plan captured by an owner mutation. */
final class OwnerMutationAdmissionPlanFactory {
    private OwnerMutationAdmissionPlanFactory() {
    }

    @Nonnull
    static Plan create(@Nonnull OwnerMutationSnapshotResolver.Snapshot snapshot,
                       @Nullable OwnerPopulationEntry current,
                       @Nullable UUID newOwnerId,
                       @Nonnull CompanionLifecycleState lifecycleState,
                       @Nonnull OwnerPopulationOperation operation,
                       boolean force,
                       boolean permanentRelease,
                       @Nullable String durableContextJson,
                       @Nonnull CompanionAdmissionPolicyResolver policyResolver) {
        ClaimOccupancyTransition claimTransition = policyResolver.transition(
                snapshot, newOwnerId, lifecycleState
        );
        CompanionAdmissionPolicyResolver.Policy policy = policyResolver.resolve(
                operation, !claimTransition.isKnownNonPositiveAtSameLocation()
        );
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition(snapshot, current, newOwnerId, lifecycleState, operation, policy, force),
                baseline(snapshot, current),
                snapshot.npcUuid(),
                snapshot.worldName(),
                snapshot.chunkX(),
                snapshot.chunkZ(),
                operation.name().toLowerCase(Locale.ROOT),
                ownerJson(current == null ? snapshot.liveOwnerId() : current.ownerId()),
                ownerJson(newOwnerId),
                contextJson(snapshot, permanentRelease, durableContextJson),
                policy.settingsRevision(),
                policy.claimContext().providerGeneration(),
                snapshot.roleId() == null ? null
                        : PopulationGroupRoleContext.unchanged(snapshot.roleId())
        );
        ClaimAdmissionRequest claimRequest = policyResolver.request(
                snapshot, newOwnerId, lifecycleState, operation, claimTransition, policy, force
        );
        return new Plan(ownerPlan, claimRequest, policy);
    }

    private static OwnerPopulationTransitionRequest transition(
            OwnerMutationSnapshotResolver.Snapshot snapshot,
            @Nullable OwnerPopulationEntry current,
            @Nullable UUID newOwnerId,
            CompanionLifecycleState lifecycleState,
            OwnerPopulationOperation operation,
            CompanionAdmissionPolicyResolver.Policy policy,
            boolean force
    ) {
        return new OwnerPopulationTransitionRequest(
                snapshot.profileId(),
                current == null ? OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION : current.revision(),
                current == null ? null : current.ownerId(),
                current == null ? null : current.ownershipWorldName(),
                newOwnerId,
                snapshot.worldName(),
                lifecycleState,
                operation,
                policy.scope(),
                policy.limit(),
                force
        );
    }

    private static CompanionPopulationStateRecord baseline(
            OwnerMutationSnapshotResolver.Snapshot snapshot,
            @Nullable OwnerPopulationEntry current
    ) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationStateRecord(
                snapshot.profileId(), snapshot.baselineNpcUuid(),
                current == null ? null : current.ownerId(), snapshot.worldName(),
                current == null ? snapshot.worldName() : current.ownershipWorldName(),
                current == null ? CompanionLifecycleState.ACTIVE.name() : current.lifecycleState().name(),
                snapshot.worldName(), snapshot.chunkX(), snapshot.chunkZ(),
                current == null ? 0L : current.revision(), "owner_mutation_snapshot", now, now
        );
    }

    private static String ownerJson(@Nullable UUID ownerId) {
        JsonObject json = new JsonObject();
        if (ownerId == null) {
            json.add("ownerUuid", null);
        } else {
            json.addProperty("ownerUuid", ownerId.toString());
        }
        return json.toString();
    }

    private static String contextJson(OwnerMutationSnapshotResolver.Snapshot snapshot,
                                      boolean permanentRelease,
                                      @Nullable String durableContextJson) {
        JsonObject json = new JsonObject();
        json.addProperty("world", snapshot.worldName());
        json.addProperty("chunkX", snapshot.chunkX());
        json.addProperty("chunkZ", snapshot.chunkZ());
        json.addProperty("npcUuid", snapshot.npcUuid().toString());
        if (permanentRelease) {
            json.addProperty("permanentRelease", true);
        }
        if (durableContextJson != null && !durableContextJson.isBlank()) {
            JsonObject extension = JsonParser.parseString(durableContextJson).getAsJsonObject();
            for (String key : extension.keySet()) {
                if (json.has(key)) {
                    throw new IllegalArgumentException("Durable context overrides reserved key: " + key);
                }
                json.add(key, extension.get(key));
            }
        }
        return json.toString();
    }

    record Plan(@Nonnull OwnerPopulationAdmissionPlan ownerPlan,
                @Nonnull ClaimAdmissionRequest claimRequest,
                @Nonnull CompanionAdmissionPolicyResolver.Policy policy) {
    }
}
