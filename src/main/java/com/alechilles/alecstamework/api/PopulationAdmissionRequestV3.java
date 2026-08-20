package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Managed-profile population admission request with the V2 lifecycle context. */
public record PopulationAdmissionRequestV3(
        @Nonnull PopulationAdmissionRequestV2 request,
        @Nonnull String managedProfileId
) {
    public PopulationAdmissionRequestV3 {
        request = Objects.requireNonNull(request, "request");
        managedProfileId = requireText(managedProfileId, "managedProfileId");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
