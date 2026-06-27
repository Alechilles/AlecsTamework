package com.alechilles.alecstamework.items;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudServiceTest {
    @Test
    void sweepsFastEnoughForNearInstantTargetChanges() {
        Assertions.assertTrue(
                CommandTargetHudService.sweepIntervalMsForTests() <= 25L,
                "Command target HUD should react on the next practical tick when targeting changes."
        );
    }

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
    void refreshesWhenHeldCommandItemChangesForSameTarget() {
        UUID npcUuid = UUID.fromString("2ef48f27-68c3-47e8-9629-b9e1c9cf2ddf");
        String whistleTarget = CommandTargetHudService.buildTargetKeyForTests(npcUuid, "Tamework:CommandWhistle");
        String fluteTarget = CommandTargetHudService.buildTargetKeyForTests(npcUuid, "Tamework:CommandFlute");

        Assertions.assertTrue(CommandTargetHudService.shouldRefreshForTests(
                whistleTarget,
                fluteTarget,
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
