package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Informational, owner-only population-cap preflight with the context missing from the legacy API. */
public record OwnerPopulationCapRequestV2(@Nonnull UUID ownerUuid,
                                         @Nullable String worldName,
                                         int requestedSlots) {
    public OwnerPopulationCapRequestV2 {
        ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
        worldName = normalizeWorldName(worldName);
        if (requestedSlots <= 0) {
            throw new IllegalArgumentException("Requested owner population slots must be positive.");
        }
    }

    @Nullable
    static String normalizeWorldName(@Nullable String worldName) {
        if (worldName == null) {
            return null;
        }
        String normalized = worldName.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
