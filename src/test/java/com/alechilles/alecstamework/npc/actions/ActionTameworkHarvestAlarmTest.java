package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionTameworkHarvestAlarmTest {
    @Test
    void scalesHarvestCooldownSecondsWithTalentMultiplier() {
        assertEquals(45.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.75), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.0), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, Double.NaN), 0.000001);
        assertEquals(0.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(-10.0, 0.75), 0.000001);
    }
}
