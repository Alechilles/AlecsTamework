package com.alechilles.alecstamework.ownership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public static final class ReservedSpawn {
        private final CompanionSpawnAdmissionRequest request;
        private final String profileId;
        private final UUID plannedNpcUuid;
        @Nullable
        private final UUID previousNpcUuid;
        private final OwnerPopulationOperation effectiveOperation;
        private final boolean legacyAdoption;
        private final AtomicBoolean identityTerminal = new AtomicBoolean(false);

        public ReservedSpawn(
                @Nonnull CompanionSpawnAdmissionRequest request,
                @Nonnull String profileId,
                @Nonnull UUID plannedNpcUuid,
                @Nullable UUID previousNpcUuid,
                @Nonnull OwnerPopulationOperation effectiveOperation,
                boolean legacyAdoption
        ) {
            this.request = Objects.requireNonNull(request, "request");
            this.profileId = OwnerPopulationEntry.normalizeProfileId(profileId);
            this.plannedNpcUuid = Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
            this.previousNpcUuid = previousNpcUuid;
            this.effectiveOperation = Objects.requireNonNull(
                    effectiveOperation, "effectiveOperation"
            );
            this.legacyAdoption = legacyAdoption;
        }

        @Nonnull
        public CompanionSpawnAdmissionRequest request() {
            return request;
        }

        @Nonnull
        public String profileId() {
            return profileId;
        }

        @Nonnull
        public UUID plannedNpcUuid() {
            return plannedNpcUuid;
        }

        @Nullable
        public UUID previousNpcUuid() {
            return previousNpcUuid;
        }

        @Nonnull
        public OwnerPopulationOperation effectiveOperation() {
            return effectiveOperation;
        }

        public boolean legacyAdoption() {
            return legacyAdoption;
        }

        boolean claimIdentityTerminal() {
            return identityTerminal.compareAndSet(false, true);
        }
    }
}
