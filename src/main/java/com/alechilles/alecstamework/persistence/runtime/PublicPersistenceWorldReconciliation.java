package com.alechilles.alecstamework.persistence.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * World-facing startup evidence and reconciliation boundary.
 *
 * <p>Deferral means evidence is not complete yet. It never means absence or a
 * successful reconciliation.</p>
 */
public interface PublicPersistenceWorldReconciliation {
    enum Result {
        COMPLETE,
        DEFERRED
    }

    /** Waits for sealed evidence required to make world-sensitive decisions. */
    @Nonnull
    CompletionStage<Result> awaitEvidence();

    /** Reconciles canonical state only from complete sealed world evidence. */
    @Nonnull
    CompletionStage<Result> reconcile();

    /** Prevents new callbacks from submitting persistence work during shutdown. */
    void quiesce();

    /** Empty-world implementation for tests and worlds with no reconciliation work. */
    static PublicPersistenceWorldReconciliation alreadyComplete() {
        return new PublicPersistenceWorldReconciliation() {
            @Override
            public CompletionStage<Result> awaitEvidence() {
                return CompletableFuture.completedFuture(Result.COMPLETE);
            }

            @Override
            public CompletionStage<Result> reconcile() {
                return CompletableFuture.completedFuture(Result.COMPLETE);
            }

            @Override
            public void quiesce() {
            }
        };
    }
}
