package com.alechilles.alecstamework.ownership.reconciliation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for synchronous final-reload pipeline construction failures. */
class CanonicalReloadCompletionTest {
    @Test
    void synchronousWorkRejectionStillReleasesCanonicalReloadGate() {
        AtomicInteger cleanups = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                CanonicalReloadCompletion.<String>run(
                        () -> {
                            throw new RejectedExecutionException("flush scheduling rejected");
                        },
                        cleanups::incrementAndGet).join());

        assertEquals(1, cleanups.get());
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

    @Test
    void synchronousWorkAndCleanupFailuresAreBothPreserved() {
        AtomicInteger cleanups = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                CanonicalReloadCompletion.<String>run(
                        () -> {
                            throw new RejectedExecutionException("flush scheduling rejected");
                        },
                        () -> {
                            cleanups.incrementAndGet();
                            throw new IllegalStateException("reload cleanup failed");
                        }).join());

        assertEquals(1, cleanups.get());
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertInstanceOf(
                IllegalStateException.class,
                failure.getCause().getSuppressed()[0]);
    }

    @Test
    void asynchronousFailureAlsoRunsCleanupExactlyOnce() {
        AtomicInteger cleanups = new AtomicInteger();

        assertThrows(CompletionException.class, () ->
                CanonicalReloadCompletion.run(
                        () -> CompletableFuture.failedFuture(
                                new IllegalArgumentException("flush failed")),
                        cleanups::incrementAndGet).join());

        assertEquals(1, cleanups.get());
    }
}
