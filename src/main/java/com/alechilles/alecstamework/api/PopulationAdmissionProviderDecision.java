package com.alechilles.alecstamework.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;

/** Immutable provider decision with claims, limits, and the revisions used to evaluate it. */
public record PopulationAdmissionProviderDecision(
        @Nonnull PopulationAdmissionProviderStatus status,
        @Nonnull String messageKey,
        @Nonnull Set<PopulationDomainClaim> claims,
        @Nonnull Map<String, Integer> domainLimits,
        long snapshotRevision,
        long configRevision
) {
    public PopulationAdmissionProviderDecision {
        status = Objects.requireNonNull(status, "status");
        messageKey = requireText(messageKey, "messageKey");
        claims = Set.copyOf(Objects.requireNonNull(claims, "claims"));
        domainLimits = immutableLimits(domainLimits);
        if (snapshotRevision < 0L) {
            throw new IllegalArgumentException("snapshotRevision cannot be negative.");
        }
        if (configRevision < 0L) {
            throw new IllegalArgumentException("configRevision cannot be negative.");
        }
    }

    /** Creates the stable fail-closed decision used when provider state is unavailable. */
    @Nonnull
    public static PopulationAdmissionProviderDecision unavailable(@Nonnull String messageKey) {
        return new PopulationAdmissionProviderDecision(
                PopulationAdmissionProviderStatus.UNAVAILABLE,
                messageKey,
                Set.of(),
                Map.of(),
                0L,
                0L
        );
    }

    private static Map<String, Integer> immutableLimits(Map<String, Integer> values) {
        Map<String, Integer> source = Objects.requireNonNull(values, "domainLimits");
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String canonical = requireText(entry.getKey(), "domainLimits key");
            Integer limit = Objects.requireNonNull(entry.getValue(), "domainLimits value");
            if (limit < 0) {
                throw new IllegalArgumentException("domainLimits values cannot be negative.");
            }
            if (normalized.put(canonical, limit) != null) {
                throw new IllegalArgumentException(
                        "domainLimits contains duplicate canonical identifier: " + canonical
                );
            }
        }
        return Map.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
