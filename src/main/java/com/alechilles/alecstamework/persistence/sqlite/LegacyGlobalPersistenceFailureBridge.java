package com.alechilles.alecstamework.persistence.sqlite;

import javax.annotation.Nonnull;

/**
 * Isolates compatibility-only global degradation used by legacy constructors and tests.
 * Production composition supplies the v7 incident reporter and never reaches this bridge.
 */
public final class LegacyGlobalPersistenceFailureBridge {
    private LegacyGlobalPersistenceFailureBridge() {
    }

    public static boolean markDegraded(@Nonnull PersistenceHealthService health,
                                       @Nonnull String reason) {
        return health.markDegraded(reason);
    }
}
