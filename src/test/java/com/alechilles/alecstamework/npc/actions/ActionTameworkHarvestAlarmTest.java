package com.alechilles.alecstamework.npc.actions;

import java.time.Instant;
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

}
