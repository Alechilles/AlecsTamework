package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Value-only request for the same non-mutating persistence gate used by Tamework entry points. */
public record PersistenceMutationAvailabilityRequest(
        @Nonnull PersistenceMutationDomain domain,
        @Nonnull String operationKind,
        @Nonnull List<PersistenceScopeReference> scopes,
        @Nonnull Set<String> requiredEvidenceDimensions,
        @Nonnull PersistenceMutationDirection direction,
        @Nullable String traceId,
        @Nullable String operationId,
        boolean sourceMayExist,
        boolean liveProjectionMayExist) {
    public PersistenceMutationAvailabilityRequest {
        if (domain == null || direction == null) throw new IllegalArgumentException("domain/direction");
        if (operationKind == null || operationKind.isBlank()) {
            throw new IllegalArgumentException("operationKind");
        }
        operationKind = operationKind.trim();
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        requiredEvidenceDimensions = normalizeDimensions(requiredEvidenceDimensions);
        traceId = normalize(traceId);
        operationId = normalize(operationId);
    }

    private static Set<String> normalizeDimensions(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("evidence dimension");
            normalized.add(value.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
