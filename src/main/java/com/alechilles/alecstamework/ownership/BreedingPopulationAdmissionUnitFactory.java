package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationStateRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Translates one immutable breeding plan into owner and claim admission units.
 * Keeping translation separate leaves the public service focused on lifecycle orchestration.
 */
final class BreedingPopulationAdmissionUnitFactory {

    @Nonnull
    PreparedUnits build(
            @Nonnull BreedingPopulationAdmissionRequest request,
            int boundedCount,
            @Nonnull ClaimChunkCoordinate destination,
            @Nonnull CompanionAdmissionPolicyResolver.Policy policy
    ) {
        List<CompanionPopulationAdmissionUnit> units = new ArrayList<>(boundedCount);
        List<PreparedBreedingPopulationBatch.ReservedChild> children = new ArrayList<>(boundedCount);
        for (int index = 0; index < boundedCount; index++) {
            BreedingPopulationAdmissionRequest.PlannedChild child = request.plannedChildren().get(index);
            PreparedBreedingPopulationBatch.ReservedChild reservedChild = reservedChild(request, child);
            children.add(reservedChild);
            units.add(unit(request, reservedChild, destination, policy));
        }
        return new PreparedUnits(List.copyOf(units), List.copyOf(children));
    }

    @Nonnull
    private static PreparedBreedingPopulationBatch.ReservedChild reservedChild(
            @Nonnull BreedingPopulationAdmissionRequest request,
            @Nonnull BreedingPopulationAdmissionRequest.PlannedChild child
    ) {
        String profileId = BreedingAdmissionIdentity.profileId(
                request.idempotencyKey(), child.childKey()
        );
        UUID plannedNpcUuid = BreedingAdmissionIdentity.npcUuid(
                request.idempotencyKey(), child.childKey()
        );
        return new PreparedBreedingPopulationBatch.ReservedChild(
                child.childKey(),
                profileId,
                plannedNpcUuid,
                child.ownerId(),
                child.ownerName()
        );
    }

    @Nonnull
    private static CompanionPopulationAdmissionUnit unit(
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
        OwnerPopulationAdmissionPlan ownerPlan = new OwnerPopulationAdmissionPlan(
                transition,
                baseline(request, child, destination),
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
        json.addProperty(
                "operation", OwnerPopulationOperation.BREEDING.name().toLowerCase(Locale.ROOT)
        );
        json.addProperty("idempotencyKey", request.idempotencyKey());
        json.addProperty("childKey", child.childKey());
        json.addProperty("world", destination.worldName());
        json.addProperty("chunkX", destination.chunkX());
        json.addProperty("chunkZ", destination.chunkZ());
        json.addProperty("plannedNpcUuid", child.plannedNpcUuid().toString());
        if (request.hasCanonicalParentPair()) {
            JsonArray parentProfileIds = new JsonArray();
            for (String profileId : request.parentProfileIds()) {
                parentProfileIds.add(profileId);
            }
            json.add("parentProfileIds", parentProfileIds);
        }
        json.add("birthPlan", BreedingBirthPlanSnapshotJsonCodec.encode(request.birthPlan()));
        return json.toString();
    }

    record PreparedUnits(
            @Nonnull List<CompanionPopulationAdmissionUnit> units,
            @Nonnull List<PreparedBreedingPopulationBatch.ReservedChild> children
    ) {
    }
}
