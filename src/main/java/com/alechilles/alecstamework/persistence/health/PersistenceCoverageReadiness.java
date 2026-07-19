package com.alechilles.alecstamework.persistence.health;

import java.util.Set;
import javax.annotation.Nonnull;

/** Adapter for dimension-specific evidence readiness. */
@FunctionalInterface
public interface PersistenceCoverageReadiness {
    boolean areReady(@Nonnull Set<String> requiredDimensions);
}
