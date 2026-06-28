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
    void awardsFeedXpWhenWaterRefillApplies() {
        assertTrue(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(0, true, true, false));
    }

    @Test
    void awardsFeedXpWhenStoredConsumptionOnlyChangesHappiness() {
        assertTrue(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(1, false, false, true));
    }

    @Test
    void doesNotAwardFeedXpWhenNoFoodOrWaterRefillApplies() {
        assertFalse(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(0, false, true, false));
    }

    @Test
    void doesNotAwardFeedXpWhenStoredConsumptionDoesNotApply() {
        assertFalse(CompanionNeedsConsumeService.shouldAwardFeedXpForResourceConsume(1, true, false, false));
    }

    @Test
    void consumeOriginWithFiniteCoordinatesCanUseTargetFirstProbe() {
        assertTrue(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
                new org.joml.Vector3d(1.5, 64.0, 2.5)
        ));
    }

    @Test
    void consumeOriginWithNaNCoordinateSkipsTargetFirstProbe() {
        assertFalse(CompanionNeedsConsumeService.canUseTargetFirstConsumeProbeForTests(
                new org.joml.Vector3d(Double.NaN, 64.0, 2.5)
        ));
    }
}
