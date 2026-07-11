package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ensures a rejected world enqueue cannot leave startup reconciliation waiting forever. */
class HytaleOnlinePlayerInventoryEvidenceSourceTest {
    @Test
    void synchronousDispatchFailureCompletesEvidenceFutureExceptionally() {
        CompletableFuture<Object> result = new CompletableFuture<>();

        HytaleOnlinePlayerInventoryEvidenceSource.executeOrFail(
                task -> {
                    throw new IllegalStateException("world stopped");
                },
                () -> {
                },
                result
        );

        assertThrows(CompletionException.class, result::join);
    }
}
