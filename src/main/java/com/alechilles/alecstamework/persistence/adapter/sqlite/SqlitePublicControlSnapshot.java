package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuit;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import java.util.Map;
import javax.annotation.Nonnull;

/** Exact descriptor-keyed control evidence loaded during public startup. */
public record SqlitePublicControlSnapshot(
        @Nonnull Map<PersistenceFeatureId, PersistenceFeatureCircuit> circuits
) {
    public SqlitePublicControlSnapshot {
        if (circuits == null || circuits.isEmpty()
                || circuits.values().stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Public control snapshot is required"
            );
        }
        circuits = Map.copyOf(circuits);
    }
}
