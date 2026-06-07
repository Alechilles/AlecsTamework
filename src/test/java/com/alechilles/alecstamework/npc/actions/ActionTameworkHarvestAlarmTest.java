package com.alechilles.alecstamework.npc.actions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionTameworkHarvestAlarmTest {
    private static final Path HARVEST_ALARM_ACTION = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "ActionTameworkHarvestAlarm.java"
    );

    @Test
    void scalesHarvestCooldownSecondsWithTalentMultiplier() {
        assertEquals(45.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.75), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.0), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, Double.NaN), 0.000001);
        assertEquals(0.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(-10.0, 0.75), 0.000001);
    }

    @Test
    void harvestCooldownUsesWorldTimeBasisEvenWhenEpochIsEarly() {
        Instant worldTime = Instant.parse("0001-01-01T00:00:00Z");

        Instant until = HarvestAlarmTimeBasis.resolveCooldownUntil(worldTime, 90.0);

        assertEquals(Instant.parse("0001-01-01T00:01:30Z"), until);
    }

    @Test
    void harvestTimeoutSupportsVanillaTemporalRangeStrings() {
        double seconds = HarvestAlarmTimeBasis.resolveTemporalRangeSeconds(new String[] {"P1D", "P1D"}, () -> 0.0);

        assertEquals(86400.0, seconds, 0.000001);
    }

    @Test
    void consumesCooldownSkipBeforeSettingHarvestAlarm() throws Exception {
        String content = Files.readString(HARVEST_ALARM_ACTION, StandardCharsets.UTF_8);

        int skipCheck = content.indexOf("CompanionHarvestBonusService.consumeCooldownSkip");
        int setAlarm = content.indexOf("alarm.set");

        assertTrue(skipCheck >= 0, "Harvest alarm should consume cooldown-preserve skip tokens.");
        assertTrue(setAlarm > skipCheck, "Cooldown skip must happen before the harvest alarm is set.");
    }

    @Test
    void stateHarvestAlarmSkipsWhenOptimizedHarvestAlreadyHandledCooldown() throws Exception {
        String content = Files.readString(HARVEST_ALARM_ACTION, StandardCharsets.UTF_8);

        int handledCheck = content.indexOf("CompanionHarvestBonusService.consumeCooldownHandled");
        int skipCheck = content.indexOf("CompanionHarvestBonusService.consumeCooldownSkip");
        int setAlarm = content.indexOf("alarm.set");

        assertTrue(handledCheck >= 0, "State harvest alarm should honor optimized harvest cooldown handling.");
        assertTrue(skipCheck > handledCheck, "Handled cooldown marker must be checked before cooldown-preserve skip.");
        assertTrue(setAlarm > skipCheck, "State action should only write the alarm after both handoff checks.");
    }

    @Test
    void guardedHarvestCooldownRequiresReadyAlarmBeforeWriting() throws Exception {
        String content = Files.readString(HARVEST_ALARM_ACTION, StandardCharsets.UTF_8);

        int readyCheck = content.indexOf("if (requireReady && !isAlarmReady(alarm, store))");
        int setAlarm = content.indexOf("alarm.set", readyCheck);

        assertTrue(readyCheck >= 0, "Guarded optimized harvest cooldown should reject active alarms.");
        assertTrue(setAlarm > readyCheck, "Guarded optimized harvest cooldown must check readiness before writing.");
    }
}
