package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionProjectionValidator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Immutable result of one bounded world-thread bonded-marker scan. */
record BondedCompanionRecoveryScanResult(
        List<BondedCompanionProjectionValidator.Projection> projections,
        boolean complete
) {
    BondedCompanionRecoveryScanResult {
        projections = List.copyOf(projections);
    }

    static BondedCompanionRecoveryScanResult incomplete() {
        return new BondedCompanionRecoveryScanResult(List.of(), false);
    }
}

/** One independently scheduled recovery scan paired with the world it conclusively represents. */
record BondedCompanionScheduledRecoveryScan(
        String worldKey,
        CompletableFuture<BondedCompanionRecoveryScanResult> future
) { }
