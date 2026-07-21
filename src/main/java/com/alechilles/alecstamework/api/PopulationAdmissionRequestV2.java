package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Role-aware population admission request. Tamework resolves authoritative group membership from
 * {@code targetRoleId}; callers never provide a group set.
 */
public record PopulationAdmissionRequestV2(@Nonnull PopulationAdmissionRequest request,
                                           @Nonnull String targetRoleId,
                                           @Nonnull String ownershipWorldName) {
    public PopulationAdmissionRequestV2 {
        request = Objects.requireNonNull(request, "request");
        targetRoleId = requireText(targetRoleId, "targetRoleId");
        ownershipWorldName = requireText(ownershipWorldName, "ownershipWorldName");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }
}
