package com.alechilles.alecstamework.persistence.runtime;

import javax.annotation.Nonnull;

/**
 * High-level persistence capabilities supplied to gameplay composition.
 *
 * <p>This bundle intentionally exposes no SQLite adapter, connection, queue,
 * store, or repository.</p>
 */
public record PersistenceDomainFacades(
        @Nonnull PublicPersistenceOperations operations,
        @Nonnull PublicPersistenceQueries queries,
        @Nonnull PersistenceThroughputMetrics throughputMetrics
) {
    /** Preserves the original facade pair with passive metrics disabled. */
    public PersistenceDomainFacades(
            @Nonnull PublicPersistenceOperations operations,
            @Nonnull PublicPersistenceQueries queries
    ) {
        this(operations, queries, PersistenceThroughputMetrics.NO_OP);
    }

    public PersistenceDomainFacades {
        if (operations == null || queries == null || throughputMetrics == null) {
            throw new IllegalArgumentException(
                    "Complete persistence domain facades are required"
            );
        }
    }
}
