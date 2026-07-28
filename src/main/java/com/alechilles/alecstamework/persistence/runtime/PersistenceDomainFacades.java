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
        @Nonnull PublicPersistenceQueries queries
) {
    public PersistenceDomainFacades {
        if (operations == null || queries == null) {
            throw new IllegalArgumentException(
                    "Complete persistence domain facades are required"
            );
        }
    }
}
