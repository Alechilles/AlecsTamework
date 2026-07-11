package com.alechilles.alecstamework.ownership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Population batch plus the immutable planned identity and exact owner for every admitted child. */
public final class PreparedBreedingPopulationBatch {
    private final int requestedCount;
    private final String attemptKey;
    private final BreedingBirthPlanSnapshot birthPlan;
    private final PreparedCompanionPopulationBatch populationBatch;
    private final List<ReservedChild> children;

    PreparedBreedingPopulationBatch(
            int requestedCount,
            @Nonnull String attemptKey,
            @Nonnull BreedingBirthPlanSnapshot birthPlan,
            @Nonnull PreparedCompanionPopulationBatch populationBatch,
            @Nonnull List<ReservedChild> children
    ) {
        if (requestedCount <= 0) {
            throw new IllegalArgumentException("requestedCount must be positive.");
        }
        this.requestedCount = requestedCount;
        this.attemptKey = Objects.requireNonNull(attemptKey, "attemptKey");
        this.birthPlan = Objects.requireNonNull(birthPlan, "birthPlan");
        this.populationBatch = Objects.requireNonNull(populationBatch, "populationBatch");
        this.children = List.copyOf(Objects.requireNonNull(children, "children"));
        if (this.children.size() != populationBatch.admittedCount()) {
            throw new IllegalArgumentException("Reserved child identities must match admitted units.");
        }
    }

    public int requestedCount() {
        return requestedCount;
    }

    public int admittedCount() {
        return children.size();
    }

    @Nonnull
    public String attemptKey() {
        return attemptKey;
    }

    @Nonnull
    public BreedingBirthPlanSnapshot birthPlan() {
        return birthPlan;
    }

    @Nonnull
    public PreparedCompanionPopulationBatch populationBatch() {
        return populationBatch;
    }

    @Nonnull
    public List<ReservedChild> children() {
        return children;
    }

    @Nonnull
    public ReservedChild child(int unitIndex) {
        return children.get(unitIndex);
    }

    /** One preallocated canonical profile and deterministic spawn UUID. */
    public record ReservedChild(
            @Nonnull String childKey,
            @Nonnull String profileId,
            @Nonnull UUID plannedNpcUuid,
            @Nullable UUID ownerId,
            @Nullable String ownerName
    ) {
        public ReservedChild {
            Objects.requireNonNull(childKey, "childKey");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(plannedNpcUuid, "plannedNpcUuid");
        }
    }
}
