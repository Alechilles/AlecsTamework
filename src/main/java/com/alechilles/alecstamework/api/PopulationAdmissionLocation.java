package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Exact world/chunk location captured by a mutation-bound population request. */
public record PopulationAdmissionLocation(@Nonnull String worldName, int chunkX, int chunkZ) {
    public PopulationAdmissionLocation {
        worldName = Objects.requireNonNull(worldName, "worldName").trim();
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("Population admission world name is required.");
        }
    }
}
