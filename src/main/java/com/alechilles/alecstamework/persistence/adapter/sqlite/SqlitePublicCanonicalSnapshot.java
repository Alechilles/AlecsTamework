package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.ScopeQuarantine;
import java.util.List;
import javax.annotation.Nonnull;

/** Complete startup evidence for canonical profile/lifecycle and containment state. */
public record SqlitePublicCanonicalSnapshot(
        long profileCount,
        long lifecycleCount,
        @Nonnull List<ScopeQuarantine> activeQuarantines
) {
    public SqlitePublicCanonicalSnapshot {
        if (profileCount < 0 || lifecycleCount < 0
                || profileCount != lifecycleCount
                || activeQuarantines == null
                || activeQuarantines.stream().anyMatch(
                java.util.Objects::isNull
        )) {
            throw new IllegalArgumentException(
                    "Consistent canonical startup snapshot is required"
            );
        }
        activeQuarantines = List.copyOf(activeQuarantines);
    }
}
