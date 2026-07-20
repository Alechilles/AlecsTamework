package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PopulationPersistenceTransition;
import com.alechilles.alecstamework.persistence.sqlite.ProfileOwnerMutation;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds crash-recovery journal records from one immutable owner-population admission plan. */
final class OwnerPopulationPersistenceRecords {
    private OwnerPopulationPersistenceRecords() {
    }

    @Nonnull
    static CompanionPopulationOperationRecord prepared(@Nonnull UUID operationId,
                                                       @Nonnull OwnerPopulationAdmissionPlan plan) {
        long now = System.currentTimeMillis();
        return new CompanionPopulationOperationRecord(
                operationId.toString(), plan.transition().profileId(),
                plan.transition().operation().name(),
                CompanionPopulationOperationRecord.State.PREPARED,
                plan.baselineState().revision(),
                recoveryStateJson(
                        plan.oldStateJson(), plan.baselineState().ownerUuid(),
                        plan.baselineState().lifecycleState(),
                        plan.baselineState().ownershipWorldName()),
                recoveryStateJson(
                        plan.newStateJson(), plan.transition().newOwnerId(),
                        plan.transition().lifecycleState().name(),
                        plan.transition().destinationWorldName()),
                plan.targetContextJson(), now, now, 0L, null);
    }

    @Nonnull
    static PopulationPersistenceTransition.Commit commit(
            @Nonnull UUID operationId,
            @Nonnull OwnerPopulationAdmissionPlan plan) {
        OwnerPopulationTransitionRequest transition = plan.transition();
        return new PopulationPersistenceTransition.Commit(
                operationId.toString(), transition.profileId(), plan.baselineState().revision(),
                ownerMutation(transition.expectedOwnerId(), transition.newOwnerId()),
                plan.finalNpcUuid(), transition.destinationWorldName(),
                transition.lifecycleState().name(), plan.finalPhysicalWorldName(),
                plan.finalPhysicalChunkX(), plan.finalPhysicalChunkZ(), plan.source());
    }

    @Nonnull
    private static String recoveryStateJson(@Nonnull String original,
                                            @Nullable UUID ownerUuid,
                                            @Nonnull String lifecycleState,
                                            @Nullable String ownershipWorldName) {
        JsonObject json = JsonParser.parseString(original).getAsJsonObject();
        if (ownerUuid == null) json.add("ownerUuid", JsonNull.INSTANCE);
        else json.addProperty("ownerUuid", ownerUuid.toString());
        json.addProperty("lifecycleState", lifecycleState);
        if (ownershipWorldName == null || ownershipWorldName.isBlank()) {
            json.add("ownershipWorldName", JsonNull.INSTANCE);
        } else {
            json.addProperty("ownershipWorldName", ownershipWorldName.trim());
        }
        return json.toString();
    }

    @Nonnull
    private static ProfileOwnerMutation ownerMutation(@Nullable UUID oldOwner,
                                                       @Nullable UUID newOwner) {
        if (Objects.equals(oldOwner, newOwner)) return ProfileOwnerMutation.unchanged();
        return newOwner == null ? ProfileOwnerMutation.clear() : ProfileOwnerMutation.set(newOwner);
    }
}
