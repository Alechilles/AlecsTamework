package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/** World-thread reconciliation and spawn boundary for one durable litter. */
@FunctionalInterface
public interface BreedingLitterLiveBoundary {
    @Nonnull
    CompletionStage<LiveOperationResult> reconcileAndSpawn(
            @Nonnull BreedingLitterOperation litter,
            @Nonnull OperationEnvelope operation
    );
}
