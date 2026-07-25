package com.alechilles.alecstamework.companion.revival.runtime;

import com.alechilles.alecstamework.companion.revival.PaidRevivalLiveResult;
import com.alechilles.alecstamework.companion.revival.PaidRevivalRequest;
import com.hypixel.hytale.math.util.ChunkUtil;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Frozen target-world/chunk boundary behavior before any ECS access. */
class HytalePaidRevivalBoundaryTest {

    @Test
    void targetChunkIsDerivedFromFrozenPlacement() {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);

        assertEquals(
                ChunkUtil.indexChunk(
                        ChunkUtil.chunkCoordinate(
                                request.placement().x()
                        ),
                        ChunkUtil.chunkCoordinate(
                                request.placement().z()
                        )
                ),
                HytalePaidRevivalBoundary.chunkIndex(request)
        );
    }

    @Test
    void unavailableTargetWorldIsRetryableWithoutGatewayAccess()
            throws Exception {
        PaidRevivalRequest request =
                PaidRevivalWorldTestFixture.request(false);
        AtomicBoolean invoked = new AtomicBoolean();
        HytalePaidRevivalBoundary boundary =
                new HytalePaidRevivalBoundary(
                        (world, store, ignoredRequest, operation) -> {
                            invoked.set(true);
                            throw new AssertionError(
                                    "Unavailable world cannot enter gateway"
                            );
                        },
                        ignored -> null
                );

        PaidRevivalLiveResult result = boundary.applyOrResolve(
                request,
                PaidRevivalWorldTestFixture.operation(request)
        ).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(PaidRevivalLiveResult.Status.RETRYABLE, result.status());
        assertEquals("paid_revival_world_unavailable", result.code());
        assertFalse(invoked.get());
    }
}
