package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionNeedsConsumeServiceTest {
    @Test
    void awardsFeedXpWhenStoredFoodConsumptionApplies() {
        assertTrue(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(1, false, true, false));
    }

    @Test
    void awardsFeedXpWhenStoredWaterConsumptionApplies() {
        assertTrue(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(0, true, true, false));
    }

    @Test
    void awardsFeedXpWhenStoredConsumptionOnlyChangesHappiness() {
        assertTrue(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(1, false, false, true));
    }

    @Test
    void doesNotAwardFeedXpForWorldWaterOnly() {
        assertFalse(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(0, false, true, false));
    }

    @Test
    void doesNotAwardFeedXpWhenStoredConsumptionDoesNotApply() {
        assertFalse(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(1, true, false, false));
    }
}
