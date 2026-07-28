package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Save-and-resume boundary for one hidden bonded-payment escrow. */
interface BondedCompanionEscrowDurability {
    CompletionStage<HytalePlayerDurabilityBarrier.SaveResult> saveActor();

    <T> CompletionStage<T> resumeOnWorldThread(
            Supplier<CompletionStage<T>> continuation,
            Supplier<T> unavailable);
}
