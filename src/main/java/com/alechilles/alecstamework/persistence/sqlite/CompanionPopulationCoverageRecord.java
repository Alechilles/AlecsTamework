package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resumable coverage cursor for upgrade and startup population reconciliation.
 */
public record CompanionPopulationCoverageRecord(
        @Nonnull String coverageKey,
        @Nonnull Dimension dimension,
        @Nullable String worldOrSaveId,
        @Nonnull String scanGeneration,
        @Nonnull State state,
        @Nullable String cursorJson,
        long scannedCount,
        long estimatedTotal,
        long startedAtMs,
        long updatedAtMs,
        long completedAtMs,
        @Nullable String lastError
) {
    public enum Dimension {
        GLOBAL_OWNER,
        PER_WORLD_OWNER,
        WORLD_ENTITIES,
        PLAYER_SAVES,
        BASE_CONTAINER_BLOCKS,
        CUSTOM_CONTAINERS
    }

    public enum State {
        LOADING,
        RECONCILING,
        READY,
        DEGRADED
    }

    public CompanionPopulationCoverageRecord {
        coverageKey = requireText(coverageKey, "coverageKey");
        Objects.requireNonNull(dimension, "dimension");
        scanGeneration = requireText(scanGeneration, "scanGeneration");
        Objects.requireNonNull(state, "state");
        if (scannedCount < 0L) {
            throw new IllegalArgumentException("scannedCount must be non-negative.");
        }
        if (estimatedTotal < -1L) {
            throw new IllegalArgumentException("estimatedTotal must be -1 or non-negative.");
        }
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }
}
