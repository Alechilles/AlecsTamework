package com.alechilles.alecstamework.persistence.health;

import com.alechilles.alecstamework.persistence.incidents.PersistenceScope;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

/** Adapter for dimension-specific evidence readiness. */
@FunctionalInterface
public interface PersistenceCoverageReadiness {
    boolean areReady(@Nonnull Set<String> requiredDimensions);

    /** Additive exact-scope query; legacy adapters remain conservatively dimension-wide. */
    default boolean areReady(@Nonnull Set<String> requiredDimensions,
                             @Nonnull List<PersistenceScope> scopes) {
        return areReady(requiredDimensions);
    }
}
