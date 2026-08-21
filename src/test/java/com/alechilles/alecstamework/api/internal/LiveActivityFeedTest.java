package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ActivityConsumeResult;
import com.alechilles.alecstamework.api.ActivityFeedSubscription;
import com.alechilles.alecstamework.api.SuccessfulActivityView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior checks for the process-local successful-activity feed. */
class LiveActivityFeedTest {
    @Test
    void publishesSequencedViewsAndTracksActiveDelivery() {
        LiveActivityFeed feed = new LiveActivityFeed();
        List<Long> sequences = new ArrayList<>();
        ActivityFeedSubscription subscription = feed.subscribe(
                "  husbandry  ",
                activity -> {
                    sequences.add(activity.globalSequence());
                    return CompletableFuture.completedFuture(
                            ActivityConsumeResult.APPLIED
                    );
                }
        );

        feed.publish(activity());
        feed.publish(activity());

        assertEquals(List.of(1L, 2L), sequences);
        assertEquals(2L, feed.status("husbandry").checkpointSequence());
        assertEquals("husbandry", subscription.consumerId());

        subscription.close();
        feed.publish(activity());
        assertFalse(feed.status("husbandry").subscribed());
        assertEquals(List.of(1L, 2L), sequences);
        feed.close();
    }

    @Test
    void isolatesThrowingNullAndExceptionalConsumers() {
        LiveActivityFeed feed = new LiveActivityFeed();
        AtomicInteger healthyCalls = new AtomicInteger();
        feed.subscribe("throws", ignored -> {
            throw new IllegalStateException("consumer failure");
        });
        feed.subscribe("null-stage", ignored -> null);
        feed.subscribe("exceptional-stage", ignored ->
                CompletableFuture.failedFuture(
                        new IllegalStateException("async failure")
                )
        );
        feed.subscribe("healthy", ignored -> {
            healthyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    ActivityConsumeResult.DUPLICATE
            );
        });

        assertDoesNotThrow(() -> feed.publish(activity()));
        assertEquals(1, healthyCalls.get());
        feed.close();
    }

    @Test
    void rejectsDuplicateSubscriptionAndStopsAfterClose() {
        LiveActivityFeed feed = new LiveActivityFeed();
        AtomicInteger calls = new AtomicInteger();
        ActivityFeedSubscription first = feed.subscribe(
                "husbandry",
                ignored -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ActivityConsumeResult.APPLIED
                    );
                }
        );

        assertThrows(
                IllegalStateException.class,
                () -> feed.subscribe(
                        " husbandry ",
                        ignored -> CompletableFuture.completedFuture(
                                ActivityConsumeResult.APPLIED
                        )
                )
        );
        first.close();
        feed.subscribe(
                "husbandry",
                ignored -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            ActivityConsumeResult.APPLIED
                    );
                }
        );
        feed.close();

        assertDoesNotThrow(() -> feed.publish(activity()));
        assertFalse(feed.status("husbandry").available());
        assertThrows(
                IllegalStateException.class,
                () -> feed.subscribe(
                        "new-consumer",
                        ignored -> CompletableFuture.completedFuture(
                                ActivityConsumeResult.APPLIED
                        )
                )
        );
        assertEquals(0, calls.get());
    }

    private static SuccessfulActivityView activity() {
        return new SuccessfulActivityView(
                UUID.randomUUID(),
                0L,
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                "role:cow",
                java.util.Set.of("family:cow"),
                "profile:test",
                "activity:milk",
                Map.of("Item_Milk", 1),
                Instant.EPOCH
        );
    }
}
