package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable weighted claim describing one named population-capacity domain. */
public record PopulationDomainClaim(
        @Nonnull String domainId,
        int weight,
        boolean owned,
        boolean deployable
) {
    public PopulationDomainClaim {
        domainId = requireText(domainId, "domainId");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
