package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable provider input with the managed profile, group, gate, and revision context. */
public record PopulationAdmissionProviderRequest(
        @Nonnull String providerId,
        int contractVersion,
        @Nonnull PopulationAdmissionRequestV3 admission,
        @Nonnull String familyGroupId,
        @Nonnull Set<String> groupIds,
        @Nonnull String gateKey,
        int weight,
        long managedConfigRevision
) {
    public PopulationAdmissionProviderRequest {
        providerId = requireText(providerId, "providerId");
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("contractVersion must be positive.");
        }
        admission = Objects.requireNonNull(admission, "admission");
        familyGroupId = requireText(familyGroupId, "familyGroupId");
        groupIds = immutableTextSet(groupIds);
        gateKey = requireText(gateKey, "gateKey");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive.");
        }
        if (managedConfigRevision < 0L) {
            throw new IllegalArgumentException("managedConfigRevision cannot be negative.");
        }
    }

    private static Set<String> immutableTextSet(Set<String> values) {
        Set<String> copy = Set.copyOf(Objects.requireNonNull(values, "groupIds"));
        for (String value : copy) {
            requireText(value, "groupIds entry");
        }
        return copy;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
