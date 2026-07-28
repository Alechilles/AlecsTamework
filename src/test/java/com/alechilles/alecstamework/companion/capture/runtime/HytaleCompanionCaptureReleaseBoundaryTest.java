package com.alechilles.alecstamework.companion.capture.runtime;

import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Known receipt placement must select the exact chunk loaded before replay probes absence. */
class HytaleCompanionCaptureReleaseBoundaryTest {

    @Test
    void receiptChunkUsesFrozenSpawnPlacement() {
        CompanionSpawnPlacement placement =
                new CompanionSpawnPlacement(
                        "world",
                        -0.25,
                        70.0,
                        64.01,
                        0,
                        0,
                        0
                );

        assertEquals(
                ChunkUtil.indexChunk(
                        ChunkUtil.chunkCoordinate(placement.x()),
                        ChunkUtil.chunkCoordinate(placement.z())
                ),
                HytaleCompanionCaptureReleaseBoundary.receiptChunkIndex(
                        placement
                )
        );
    }

    @Test
    void holdReleaseRetriesOnTheOwningScheduler() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger scheduled = new AtomicInteger();

        HytaleCompanionCaptureReleaseBoundary.retryOnScheduler(
                () -> attempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("transient")
                        )
                        : CompletableFuture.completedFuture(null),
                runnable -> {
                    scheduled.incrementAndGet();
                    runnable.run();
                },
                3
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(2, attempts.get());
        assertEquals(2, scheduled.get());
    }
}
