package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionFeedXpCooldownTest {
    @Test
    void feedXpCooldownAllowsFirstAwardAndBlocksUntilElapsed() {
        long firstAwardMs = 10_000L;

        assertTrue(CompanionLevelingService.isFeedXpCooldownReady(0L, firstAwardMs, 900));
        assertFalse(CompanionLevelingService.isFeedXpCooldownReady(firstAwardMs, firstAwardMs + 899_999L, 900));
        assertTrue(CompanionLevelingService.isFeedXpCooldownReady(firstAwardMs, firstAwardMs + 900_000L, 900));
    }

    @Test
    void disabledFeedXpCooldownIsAlwaysReady() {
        assertTrue(CompanionLevelingService.isFeedXpCooldownReady(10_000L, 10_001L, 0));
        assertTrue(CompanionLevelingService.isFeedXpCooldownReady(10_000L, 10_001L, -1));
    }

    @Test
    void feedXpCooldownUntilSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, CompanionLevelingService.resolveFeedXpCooldownUntilMs(
                Long.MAX_VALUE - 500L,
                1
        ));
    }
}
