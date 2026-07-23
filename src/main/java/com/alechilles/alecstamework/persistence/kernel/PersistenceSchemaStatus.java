package com.alechilles.alecstamework.persistence.kernel;

/**
 * Verified replacement schema state.
 *
 * @param version current schema version
 * @param integrityVerified whether adapter integrity checks passed
 */
public record PersistenceSchemaStatus(int version, boolean integrityVerified) {
    public PersistenceSchemaStatus {
        if (version < 0) {
            throw new IllegalArgumentException("Schema version cannot be negative");
        }
    }
}
