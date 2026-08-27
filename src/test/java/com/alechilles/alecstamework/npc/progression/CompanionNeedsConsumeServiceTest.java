package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionNeedsConsumeServiceTest {
    @Test
    void resolvesCommittedContainerFoodWithActualItemAndValues() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        10.0,
                        35.0,
                        20.0,
                        20.0,
                        Map.of("Food_Wheat", 1, "Food_Apple", 1),
                        false,
                        null
                );

        assertEquals(List.of(new NeedsSatisfactionOutcome(
                "hunger", "container", "Food_Apple", 10.0, 35.0, 25.0
        )), outcomes);
    }

    @Test
    void resolvesCommittedTroughWaterWithExactValues() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        30.0,
                        30.0,
                        5.0,
                        45.0,
                        Map.of(),
                        false,
                        "water"
                );

        assertEquals(List.of(new NeedsSatisfactionOutcome(
                "thirst", "water", "water", 5.0, 45.0, 40.0
        )), outcomes);
    }

    @Test
    void combinedConsumeProducesOneFoodAndOneWaterOutcome() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        10.0,
                        20.0,
                        15.0,
                        40.0,
                        Map.of("Food_Wheat", 1),
                        false,
                        "water"
                );

        assertEquals(2, outcomes.size());
        assertEquals("hunger", outcomes.get(0).needType());
        assertEquals("thirst", outcomes.get(1).needType());
        assertEquals("water", outcomes.get(1).resourceSource());
    }

    @Test
    void consumedFoodThatOnlyChangesHappinessStillProducesCareOutcome() {
        List<NeedsSatisfactionOutcome> outcomes =
                NeedsSatisfactionOutcome.resolveCommitted(
                        100.0,
                        100.0,
                        50.0,
                        50.0,
                        Map.of("Food_Wheat", 1),
                        true,
                        null
                );

        assertEquals(1, outcomes.size());
        assertEquals(0.0, outcomes.get(0).restoredAmount());
    }

    @Test
    void missingResourceAndNoStateChangeProduceNoOutcome() {
        assertTrue(NeedsSatisfactionOutcome.resolveCommitted(
                10.0, 10.0, 20.0, 20.0, Map.of(), false, null
        ).isEmpty());
        assertTrue(NeedsSatisfactionOutcome.resolveCommitted(
                10.0, 10.0, 20.0, 20.0,
                Map.of("Food_Wheat", 1), false, "water"
        ).isEmpty());
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

    @Test
    void careMultiplierIncreasesRestorationBeforeNeedsClamping() {
        assertEquals(
                12.5,
                CompanionNeedsConsumeService.scaleRestoration(10.0, 1.25),
                0.000001
        );
        assertEquals(
                5.0,
                Math.min(5.0, CompanionNeedsConsumeService.scaleRestoration(10.0, 1.25)),
                0.000001
        );
    }
}
