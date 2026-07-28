package com.alechilles.alecstamework.persistence.diagnostics;

import java.util.Objects;
import javax.annotation.Nonnull;

/** Redacted aggregate-only diagnostic view of the isolated bonded authority. */
public record BondedCompanionDiagnosticSnapshot(
        @Nonnull String readiness,
        int schemaVersion,
        long storedProfiles,
        long activeProfiles,
        long deadProfiles,
        long activeLeases,
        long pendingBoundedCleanups,
        @Nonnull FailureCategory lastFailureCategory
) {
    /** Fixed categories prevent storage or world details entering exports. */
    public enum FailureCategory {
        NONE, STARTUP, SCHEMA, STORAGE, WORLD_CONTEXT, WORLD_EFFECT,
        LISTENER, DIAGNOSTIC, CLOSED
    }

    public BondedCompanionDiagnosticSnapshot {
        readiness = Objects.requireNonNull(readiness, "readiness");
        lastFailureCategory = Objects.requireNonNull(
                lastFailureCategory, "lastFailureCategory"
        );
        if (schemaVersion < 0 || storedProfiles < 0L || activeProfiles < 0L
                || deadProfiles < 0L || activeLeases < 0L
                || pendingBoundedCleanups < 0L) {
            throw new IllegalArgumentException(
                    "Bonded diagnostic values cannot be negative"
            );
        }
    }
}
