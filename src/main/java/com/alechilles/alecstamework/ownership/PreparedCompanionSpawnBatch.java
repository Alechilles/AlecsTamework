package com.alechilles.alecstamework.ownership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable admitted prefix whose identities are fixed before any NPC is spawned. */
public record PreparedCompanionSpawnBatch(
        @Nonnull PreparedCompanionPopulationBatch populationBatch,
        @Nonnull List<ReservedSpawn> spawns
) {
    public PreparedCompanionSpawnBatch {
        Objects.requireNonNull(populationBatch, "populationBatch");
        spawns = List.copyOf(spawns);
        if (spawns.size() != populationBatch.admittedCount()) {
            throw new IllegalArgumentException("Spawn identities must match the admitted population prefix.");
        }
    }

    @Nonnull
    public ReservedSpawn spawn(int unitIndex) {
        return spawns.get(unitIndex);
    }

    /** One canonical source-to-planned-identity mapping. */
    public record ReservedSpawn(
            @Nonnull CompanionSpawnAdmissionRequest request,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid,
            @Nullable UUID previousNpcUuid,
            @Nonnull OwnerPopulationOperation effectiveOperation,
            boolean legacyAdoption
    ) {
        public ReservedSpawn {
            Objects.requireNonNull(request, "request");
            profileId = OwnerPopulationEntry.normalizeProfileId(profileId);
            Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
            Objects.requireNonNull(effectiveOperation, "effectiveOperation");
        }
    }
}
