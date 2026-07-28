package com.alechilles.alecstamework.persistence.bonded;

/** Aggregate-only bonded storage counts safe for operator diagnostics. */
public record BondedCompanionStoreDiagnostics(
        long storedProfiles,
        long activeProfiles,
        long deadProfiles,
        long activeLeases,
        long pendingBoundedCleanups
) {
    public BondedCompanionStoreDiagnostics {
        if (storedProfiles < 0L || activeProfiles < 0L || deadProfiles < 0L
                || activeLeases < 0L || pendingBoundedCleanups < 0L) {
            throw new IllegalArgumentException(
                    "Bonded diagnostic counts cannot be negative"
            );
        }
    }

    /** Returns an empty aggregate when bonded storage is not readable. */
    public static BondedCompanionStoreDiagnostics empty() {
        return new BondedCompanionStoreDiagnostics(0L, 0L, 0L, 0L, 0L);
    }
}
