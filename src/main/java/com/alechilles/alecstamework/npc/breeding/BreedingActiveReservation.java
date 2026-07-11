package com.alechilles.alecstamework.npc.breeding;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Deterministic immutable view of one active job's outstanding population reservation. */
public record BreedingActiveReservation(
        @Nonnull UUID jobId,
        @Nonnull String worldId,
        @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
        @Nonnull BreedingBirthAnchor anchor,
        @Nonnull BreedingBirthReservation reservation) implements Comparable<BreedingActiveReservation> {
    public BreedingActiveReservation {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(worldId, "worldId");
        worldId = worldId.trim();
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(reservation, "reservation");
    }

    @Override
    public int compareTo(@Nonnull BreedingActiveReservation other) {
        Objects.requireNonNull(other, "other");
        return jobId.compareTo(other.jobId);
    }
}
