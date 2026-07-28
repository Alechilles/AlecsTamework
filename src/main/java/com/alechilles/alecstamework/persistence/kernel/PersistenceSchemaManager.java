package com.alechilles.alecstamework.persistence.kernel;

import javax.annotation.Nonnull;

/** Driver-neutral startup boundary for creating and verifying one persistence schema lineage. */
public interface PersistenceSchemaManager {
    /** Returns the only schema version this manager opens for mutation. */
    int targetVersion();

    /** Creates an empty target or verifies/upgrades a supported target. */
    @Nonnull
    PersistenceTransactionResult<PersistenceSchemaStatus> initialize();

    /** Verifies an existing target without changing schema state. */
    @Nonnull
    PersistenceReadResult<PersistenceSchemaStatus> verify();
}
