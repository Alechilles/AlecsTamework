package com.alechilles.alecstamework.ownership;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Opaque batch capability containing individually consumable combined population reservations.
 */
public final class PreparedCompanionPopulationBatch {
    private final UUID batchId;
    private final int requestedCount;
    private final List<PreparedCompanionPopulationAdmission> admissions;

    PreparedCompanionPopulationBatch(
            @Nonnull UUID batchId,
            int requestedCount,
            @Nonnull List<PreparedCompanionPopulationAdmission> admissions
    ) {
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        if (requestedCount <= 0) {
            throw new IllegalArgumentException("A batch must request at least one unit.");
        }
        this.requestedCount = requestedCount;
        this.admissions = List.copyOf(Objects.requireNonNull(admissions, "admissions"));
        if (this.admissions.isEmpty() || this.admissions.size() > requestedCount) {
            throw new IllegalArgumentException("Prepared batch size is outside the requested range.");
        }
    }

    @Nonnull
    public UUID batchId() {
        return batchId;
    }

    public int requestedCount() {
        return requestedCount;
    }

    public int admittedCount() {
        return admissions.size();
    }

    @Nonnull
    public List<PreparedCompanionPopulationAdmission> admissions() {
        return admissions;
    }

    @Nonnull
    public PreparedCompanionPopulationAdmission admission(int unitIndex) {
        return admissions.get(unitIndex);
    }
}
