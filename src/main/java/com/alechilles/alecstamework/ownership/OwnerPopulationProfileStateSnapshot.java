package com.alechilles.alecstamework.ownership;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Lock-consistent canonical state used by lifecycle-sensitive runtime guards.
 *
 * <p>The snapshot keeps readiness and reload/transition fences coupled to the exact profile entry,
 * preventing callers from accepting a profile whose state changed between independent reads.</p>
 */
public record OwnerPopulationProfileStateSnapshot(
        @Nonnull OwnerPopulationReadiness readiness,
        boolean canonicalReloadInProgress,
        boolean transitionPending,
        @Nonnull Optional<OwnerPopulationEntry> entry) {
    public OwnerPopulationProfileStateSnapshot {
        Objects.requireNonNull(readiness, "readiness");
        entry = Objects.requireNonNull(entry, "entry");
    }
}
