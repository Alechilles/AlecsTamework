package com.alechilles.alecstamework.ownership;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable key for a global or per-world owner population bucket. */
public record OwnerPopulationScopeKey(OwnerPopulationLimitScope scope,
                                      UUID ownerId,
                                      String worldName) {

    public OwnerPopulationScopeKey {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ownerId, "ownerId");
        worldName = normalizeWorldName(worldName);
        if (scope == OwnerPopulationLimitScope.GLOBAL && worldName != null) {
            throw new IllegalArgumentException("Global owner keys cannot include a world name.");
        }
        if (scope == OwnerPopulationLimitScope.PER_WORLD && worldName == null) {
            throw new IllegalArgumentException("Per-world owner keys require a world name.");
        }
    }

    public static OwnerPopulationScopeKey global(UUID ownerId) {
        return new OwnerPopulationScopeKey(OwnerPopulationLimitScope.GLOBAL, ownerId, null);
    }

    public static OwnerPopulationScopeKey perWorld(UUID ownerId, String worldName) {
        return new OwnerPopulationScopeKey(OwnerPopulationLimitScope.PER_WORLD, ownerId, worldName);
    }

    static String normalizeWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        return worldName.trim().toLowerCase(Locale.ROOT);
    }
}
