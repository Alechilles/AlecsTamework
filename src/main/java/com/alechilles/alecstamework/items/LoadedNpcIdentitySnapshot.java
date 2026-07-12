package com.alechilles.alecstamework.items;

import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable linearization point for loaded identity completeness and detailed observations. */
public record LoadedNpcIdentitySnapshot(
        long mutationRevision,
        boolean initializationComplete,
        @Nonnull List<LoadedNpcIdentityIndex.LoadedNpcObservation> observations) {

    public LoadedNpcIdentitySnapshot {
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
    }
}
