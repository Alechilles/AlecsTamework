package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudServiceTest {
    @Test
    void refreshesWhenTargetChanges() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-b",
                1_000L,
                1_100L,
                500L
        ));
    }

    @Test
    void throttlesSameTargetUntilIntervalElapses() {
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-a",
                1_000L,
                1_250L,
                500L
        ));
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                "npc-a",
                1_000L,
                1_500L,
                500L
        ));
    }

    @Test
    void refreshesWhenHudNeedsClearing() {
        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                "npc-a",
                null,
                1_000L,
                1_100L,
                500L
        ));
        Assertions.assertFalse(CommandTargetHudService.shouldRefreshForTests(
                null,
                null,
                1_000L,
                1_100L,
                500L
        ));
    }

    @Test
    void parsesTranquilizerRequirementSeconds() {
        double seconds = CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "{\"EffectId\":\"Tw_Status_Tranquilized\",\"MinRemainingSeconds\":90}"
        );

        Assertions.assertEquals(90.0, seconds, 0.001);
    }

    @Test
    void ignoresNonTranquilizerRequirementPayloads() {
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "{\"EffectId\":\"Other\",\"MinRemainingSeconds\":90}"
        ));
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "OtherRequirement",
                "{\"EffectId\":\"Tw_Status_Tranquilized\",\"MinRemainingSeconds\":90}"
        ));
        Assertions.assertEquals(0.0, CommandTargetHudService.resolveRequiredTranquilizerSecondsForTests(
                "TameworkEffectActive",
                "not-json"
        ));
    }
}
