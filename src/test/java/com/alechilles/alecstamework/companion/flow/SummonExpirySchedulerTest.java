package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummonExpirySchedulerTest {
    private final SummonExpiryScheduler scheduler =
            new SummonExpiryScheduler(id -> CompletableFuture.completedFuture(StoreFlow.Result.STORED));

    private static CompanionRecord live(UUID id, long summonedUntilMs) {
        return CompanionRecord.builder(id, "Tamed_Sheep", CompanionLocation.live("default", 0, 0, 0))
                .ownerUuid(UUID.randomUUID())
                .summonedUntilMs(summonedUntilMs)
                .build();
    }

    @Test
    void dueReturnsOnlyTheExpiredIdsEarliestFirstAndRemovesThem() {
        UUID late = UUID.randomUUID();
        UUID early = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        // Negative wall-clock values are not expected, but the order must not depend on the sign.
        scheduler.onChange(null, live(late, 2_000L));
        scheduler.onChange(null, live(early, -500L));
        scheduler.onChange(null, live(future, 9_000L));

        assertEquals(List.of(early, late), scheduler.due(2_000L));
        assertEquals(List.of(), scheduler.due(2_000L));
        assertEquals(List.of(future), scheduler.due(9_000L));
    }

    @Test
    void aRecordThatLeavesLiveOrLosesItsTimerBeforeExpiryIsNeverReturned() {
        UUID stored = UUID.randomUUID();
        UUID untimed = UUID.randomUUID();
        UUID extended = UUID.randomUUID();
        CompanionRecord storedBefore = live(stored, 1_000L);
        CompanionRecord untimedBefore = live(untimed, 1_000L);
        CompanionRecord extendedBefore = live(extended, 1_000L);
        scheduler.onChange(null, storedBefore);
        scheduler.onChange(null, untimedBefore);
        scheduler.onChange(null, extendedBefore);

        scheduler.onChange(storedBefore, storedBefore.toBuilder()
                .location(CompanionLocation.stored(StoredReason.TIMED)).summonedUntilMs(0L).build());
        scheduler.onChange(untimedBefore, untimedBefore.toBuilder().summonedUntilMs(0L).build());
        scheduler.onChange(extendedBefore, extendedBefore.toBuilder().summonedUntilMs(5_000L).build());

        assertEquals(List.of(), scheduler.due(4_999L));
        assertEquals(List.of(extended), scheduler.due(5_000L));
    }

    @Test
    void aStoreThatFailsWithoutChangingTheRecordIsRetriedOnlyAfterTheDelay() {
        UUID id = UUID.randomUUID();
        List<StoreFlow.Result> results = new ArrayList<>(
                List.of(StoreFlow.Result.COMMIT_FAILED, StoreFlow.Result.STORED));
        List<UUID> attempts = new ArrayList<>();
        SummonExpiryScheduler retrying = new SummonExpiryScheduler(profileId -> {
            attempts.add(profileId);
            return CompletableFuture.completedFuture(results.remove(0));
        });
        retrying.onChange(null, live(id, 1_000L));

        retrying.poll(1_000L);
        retrying.poll(1_000L + SummonExpiryScheduler.RETRY_DELAY_MS - 1L);
        assertEquals(List.of(id), attempts);

        retrying.poll(1_000L + SummonExpiryScheduler.RETRY_DELAY_MS);
        retrying.poll(1_000L + 10 * SummonExpiryScheduler.RETRY_DELAY_MS);
        assertEquals(List.of(id, id), attempts);
    }

    @Test
    void rebuildPicksUpEveryLiveTimedRecord() {
        CompanionIndex index = new CompanionIndex(() -> 0L, (b, a) -> { });
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID storedTimer = UUID.randomUUID();
        index.load(List.of(
                live(first, 300L),
                live(second, 100L),
                live(UUID.randomUUID(), 0L),
                live(storedTimer, 50L).toBuilder().location(CompanionLocation.stored(StoredReason.TIMED)).build()));

        scheduler.rebuild(index);

        assertEquals(List.of(second, first), scheduler.due(1_000L));
    }
}
