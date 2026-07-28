package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistence;
import com.alechilles.alecstamework.companion.capture.runtime.CaptureReleaseWorldAttempt.ReceiptPersistenceStatus;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Exact chunk-save completion must be followed by a successful saver flush. */
class HytaleCaptureReleaseDurabilityBarrierTest {

    @Test
    void chunkFlushRunsOnlyAfterSaveCompletion() throws Exception {
        CompletableFuture<Void> save = new CompletableFuture<>();
        AtomicInteger flushes = new AtomicInteger();
        CompletableFuture<ReceiptPersistence> result =
                HytaleCaptureReleaseDurabilityBarrier.mapChunkSave(
                        save,
                        flushes::incrementAndGet,
                        41L
                ).toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(0, flushes.get());
        save.complete(null);

        assertEquals(
                ReceiptPersistenceStatus.SAVED,
                result.get(5, TimeUnit.SECONDS).status()
        );
        assertEquals(1, flushes.get());
        assertEquals(41L, result.get().targetChunkIndex());
    }

    @Test
    void failedChunkSaveNeverFlushesOrConfirms() throws Exception {
        CompletableFuture<Void> save = new CompletableFuture<>();
        AtomicInteger flushes = new AtomicInteger();
        CompletableFuture<ReceiptPersistence> result =
                HytaleCaptureReleaseDurabilityBarrier.mapChunkSave(
                        save,
                        flushes::incrementAndGet,
                        42L
                ).toCompletableFuture();

        save.completeExceptionally(
                new IllegalStateException("save failed")
        );

        assertEquals(
                ReceiptPersistenceStatus.RETRYABLE,
                result.get(5, TimeUnit.SECONDS).status()
        );
        assertEquals(0, flushes.get());
    }

    @Test
    void failedChunkFlushCannotConfirm() throws Exception {
        CompletableFuture<ReceiptPersistence> result =
                HytaleCaptureReleaseDurabilityBarrier.mapChunkSave(
                        CompletableFuture.completedFuture(null),
                        () -> {
                            throw new IOException("flush failed");
                        },
                        43L
                ).toCompletableFuture();

        assertEquals(
                ReceiptPersistenceStatus.RETRYABLE,
                result.get(5, TimeUnit.SECONDS).status()
        );
    }

    @Test
    void completedSaveStillFlushesOffTheCallerThread() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "capture-release-flush-test"
            );
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<Thread> flushThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        try {
            ReceiptPersistence result =
                    HytaleCaptureReleaseDurabilityBarrier.mapChunkSave(
                            CompletableFuture.completedFuture(null),
                            () -> flushThread.set(Thread.currentThread()),
                            44L,
                            io
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(ReceiptPersistenceStatus.SAVED, result.status());
            assertNotEquals(caller, flushThread.get());
        } finally {
            io.shutdownNow();
        }
    }
}
