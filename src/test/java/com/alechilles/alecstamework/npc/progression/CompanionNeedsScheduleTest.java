package com.alechilles.alecstamework.npc.progression;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for the indexed companion needs due queue. */
class CompanionNeedsScheduleTest {
    @Test
    void returnsNothingWhenNoNpcIsDue() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID npc = new UUID(1L, 2L);
        schedule.register(npc, 1_000L, 2_000L);

        assertNull(schedule.pollDue(2_999L));
        assertEquals(3_000L, schedule.nextDueAtMs());
    }

    @Test
    void removesNpcBeforeItsDueTime() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID npc = new UUID(3L, 4L);
        schedule.register(npc, 1_000L, 0L);
        schedule.remove(npc);

        assertNull(schedule.pollDue(1_000L));
        assertEquals(0, schedule.size());
    }

    @Test
    void preservesOldestFirstOrderAcrossReschedule() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID first = new UUID(0L, 1L);
        UUID second = new UUID(0L, 2L);
        schedule.register(second, 2_000L, 0L);
        schedule.register(first, 1_000L, 0L);

        assertEquals(first, schedule.pollDue(2_000L));
        schedule.reschedule(first, 4_000L);
        assertEquals(second, schedule.pollDue(2_000L));
    }

    @Test
    void ignoresOldRegistrationAfterRefresh() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID npc = new UUID(5L, 6L);
        schedule.register(npc, 1_000L, 0L);
        schedule.register(npc, 2_000L, 0L);

        assertNull(schedule.pollDue(1_000L));
        assertEquals(npc, schedule.pollDue(2_000L));
    }

    @Test
    void usesUuidAsStableTieBreakerForEqualDueTimes() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID lower = new UUID(0L, 1L);
        UUID higher = new UUID(0L, 2L);
        schedule.register(higher, 1_000L, 0L);
        schedule.register(lower, 1_000L, 0L);

        assertEquals(lower, schedule.pollDue(1_000L));
        assertEquals(higher, schedule.pollDue(1_000L));
    }

    @Test
    void saturatesInitialDueTimeWhenAdditionOverflows() {
        CompanionNeedsSchedule schedule = new CompanionNeedsSchedule();
        UUID npc = new UUID(7L, 8L);
        schedule.register(npc, Long.MAX_VALUE - 1L, 2L);

        assertEquals(Long.MAX_VALUE, schedule.nextDueAtMs());
        assertEquals(npc, schedule.pollDue(Long.MAX_VALUE));
    }
}
