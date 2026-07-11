package com.alechilles.alecstamework.npc.alarms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for durable alarm deadlines on signed world timelines. */
class TameworkAlarmServiceSignedTimeTest {
    @Test
    void realAlarmNeverCollidesWithZeroUnsetSentinel() {
        assertEquals(1L, TameworkAlarmService.deadlineAfter(-1_000L, 1_000L));
        assertEquals(0L, TameworkAlarmService.deadlineAfter(-1_000L, 0L));
    }

    @Test
    void alarmDeadlineSaturatesInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, TameworkAlarmService.deadlineAfter(Long.MAX_VALUE - 5L, 10L));
    }
}
