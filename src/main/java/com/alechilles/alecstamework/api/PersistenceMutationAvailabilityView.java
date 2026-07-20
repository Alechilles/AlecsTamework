package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable non-mutating gate result; reason codes are technical API values, not player text. */
public record PersistenceMutationAvailabilityView(@Nonnull String status,
                                                  @Nonnull String reasonCode,
                                                  @Nullable String incidentId) {
    @Nonnull
    public static PersistenceMutationAvailabilityView unavailable() {
        return new PersistenceMutationAvailabilityView(
                "GLOBAL_READ_ONLY", "persistence_resilience_api_unavailable", null);
    }

    public boolean allowed() {
        return "ALLOW".equals(status);
    }
}
