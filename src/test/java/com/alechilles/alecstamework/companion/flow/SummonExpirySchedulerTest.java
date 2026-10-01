package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.StoredReason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummonExpirySchedulerTest {
    private final SummonExpiryScheduler scheduler = new SummonExpiryScheduler(id -> { });

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
    void anUndoneStoreComesDueAgainOnlyAfterTheRetryDelay() {
        UUID id = UUID.randomUUID();
        CompanionRecord timed = live(id, 1_000L);
        scheduler.onChange(null, timed);
        assertEquals(List.of(id), scheduler.due(1_000L));

        // A failed store reverts the record, which the listener sees as a change.
        scheduler.onChange(timed, timed);

        assertEquals(List.of(), scheduler.due(1_001L));
        assertEquals(List.of(id), scheduler.due(1_000L + SummonExpiryScheduler.RETRY_DELAY_MS));
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
