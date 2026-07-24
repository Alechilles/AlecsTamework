package com.alechilles.alecstamework.persistence.runtime;

import javax.annotation.Nonnull;

/**
 * Creates the world reconciliation boundary after replacement domain facades exist.
 *
 * <p>This keeps Hytale world evidence outside storage composition without a mutable
 * late-binding promise or access to SQLite adapters.</p>
 */
@FunctionalInterface
public interface PublicPersistenceWorldReconciliationFactory {
    /** Creates one runtime-owned reconciliation participant. */
    @Nonnull
    PublicPersistenceWorldReconciliation create(
            @Nonnull PersistenceDomainFacades facades
    );

    /** Wraps an already constructed participant, primarily for focused tests. */
    @Nonnull
    static PublicPersistenceWorldReconciliationFactory fixed(
            @Nonnull PublicPersistenceWorldReconciliation reconciliation
    ) {
        if (reconciliation == null) {
            throw new IllegalArgumentException(
                    "World reconciliation participant is required"
            );
        }
        return ignored -> reconciliation;
    }

    /** Empty-world factory for tests that have no canonical reconciliation work. */
    @Nonnull
    static PublicPersistenceWorldReconciliationFactory alreadyComplete() {
        return fixed(PublicPersistenceWorldReconciliation.alreadyComplete());
    }
}
