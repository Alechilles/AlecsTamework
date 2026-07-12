package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ensures a rejected world enqueue cannot leave startup reconciliation waiting forever. */
class HytaleOnlinePlayerInventoryEvidenceSourceTest {
    @Test
    void synchronousDispatchFailureCompletesEvidenceFutureExceptionally() {
        CompletableFuture<Object> result = new CompletableFuture<>();

        HytaleOnlinePlayerInventoryEvidenceSource.executeOrFail(
                (task, rejected) -> {
                    throw new IllegalStateException("world stopped");
                },
                () -> {
                },
                result
        );

        assertThrows(CompletionException.class, result::join);
    }

    @Test
    void acceptedButNeverStartedDispatchCompletesEvidenceFutureExceptionally() {
        CompletableFuture<Object> result = new CompletableFuture<>();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Runnable> rejected = new AtomicReference<>();

        HytaleOnlinePlayerInventoryEvidenceSource.executeOrFail(
                (task, rejection) -> {
                    queued.set(task);
                    rejected.set(rejection);
                },
                () -> result.complete(new Object()),
                result
        );

        rejected.get().run();
        assertThrows(CompletionException.class, result::join);
        queued.get().run();
        assertTrue(result.isCompletedExceptionally());
    }
}
