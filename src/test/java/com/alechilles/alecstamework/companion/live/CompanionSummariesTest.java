package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionSummary;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionSummariesTest {
    private static CompanionSummaries.Inputs inputs(float health, float max, double hunger) {
        return new CompanionSummaries.Inputs("Wooly", "server.npcs.sheep.name", "Sheep", "Icon_Sheep",
                health, max, "TwHappinessDefault", 50.0, "TwNeedsDefault", hunger, 20.0,
                true, -3000L, -9000L, 6000L, -1500L, "TwLevelingDefault", 3, 5.0, 100.0, 1,
                Map.of("speed", 1.1), "Adult", 1.0, null, 0L);
    }

    @Test
    void nonFiniteAndNegativeValuesBecomeSafeNumbers() {
        CompanionSummary summary = CompanionSummaries.build(inputs(Float.NaN, -5f, Double.POSITIVE_INFINITY), 123L);

        assertEquals(0f, summary.healthCurrent());
        assertEquals(0f, summary.healthMax());
        assertEquals(0.0, summary.hunger());
        assertEquals(123L, summary.observedAtMs());
    }

    @Test
    void healthNeverExceedsItsMaximum() {
        CompanionSummary summary = CompanionSummaries.build(inputs(30f, 20f, 10.0), 1L);

        assertEquals(20f, summary.healthCurrent());
        assertEquals(20f, summary.healthMax());
    }

    @Test
    void worldTimeCooldownsKeepTheirSignAndTraitsAreCopied() {
        CompanionSummary summary = CompanionSummaries.build(inputs(10f, 20f, 10.0), 1L);

        assertEquals(-3000L, summary.breedingCooldownUntilMs());
        assertEquals(-9000L, summary.breedingCooldownStartedAtMs());
        assertEquals(-1500L, summary.harvestAlarmUntilMs());
        assertEquals(Map.of("speed", 1.1), summary.traits());
        assertTrue(summary.breedingEnabled());
    }
}
