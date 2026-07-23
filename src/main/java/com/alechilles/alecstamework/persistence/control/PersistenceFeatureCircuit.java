package com.alechilles.alecstamework.persistence.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact durable circuit evidence for one registered persistence feature. */
public record PersistenceFeatureCircuit(
        @Nonnull PersistenceFeatureId featureId,
        @Nonnull PersistenceFeatureCircuitState state,
        int failureCount,
        @Nullable String reasonCode,
        @Nullable Long openedAtMs,
        long updatedAtMs
) {
    public PersistenceFeatureCircuit {
        if (featureId == null || state == null || failureCount < 0) {
            throw new IllegalArgumentException(
                    "Complete feature circuit evidence is required"
            );
        }
        reasonCode = normalize(reasonCode);
        boolean closed = state == PersistenceFeatureCircuitState.CLOSED;
        if (closed != (reasonCode == null && openedAtMs == null)
                || !closed && failureCount == 0) {
            throw new IllegalArgumentException(
                    "Feature circuit state and failure evidence disagree"
            );
        }
    }

    /** Creates the default closed evidence for one registered feature. */
    @Nonnull
    public static PersistenceFeatureCircuit closed(
            @Nonnull PersistenceFeatureId featureId,
            long updatedAtMs
    ) {
        return new PersistenceFeatureCircuit(
                featureId,
                PersistenceFeatureCircuitState.CLOSED,
                0,
                null,
                null,
                updatedAtMs
        );
    }

    public boolean blocksMutation() {
        return state != PersistenceFeatureCircuitState.CLOSED;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
