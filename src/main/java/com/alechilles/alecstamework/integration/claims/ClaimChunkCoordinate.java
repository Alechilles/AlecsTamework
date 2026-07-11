package com.alechilles.alecstamework.integration.claims;

import javax.annotation.Nonnull;

/**
 * Provider-neutral identity for one claimed chunk in one world.
 */
public record ClaimChunkCoordinate(@Nonnull String worldName, int chunkX, int chunkZ) {
    public ClaimChunkCoordinate {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName cannot be blank");
        }
        worldName = worldName.trim();
    }
}
