package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

/** Async persistence boundary used by the live-observation coalescer. */
@FunctionalInterface
public interface CompanionPopulationObservationPersistence {
    @Nonnull
    CompletableFuture<CompanionPopulationObservationPersistResult> persistAsync(
            @Nonnull CompanionPopulationObservation observation
    );
}
