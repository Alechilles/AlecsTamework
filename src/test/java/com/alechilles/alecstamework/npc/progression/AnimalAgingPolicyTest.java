package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.config.assets.AnimalAgingSettings;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class AnimalAgingPolicyTest {
    private static final long MINUTE_MS = 60_000L;

    @Test
    void disabledAgingPreservesAgeAndGivesFullAdultYield() {
        AnimalAgingSettings settings = AnimalAgingSettings.CODEC.decode(org.bson.BsonDocument.parse(
                "{\"Enabled\":true,\"Mode\":\"Off\"}"));
        AnimalAgingPolicy.Progress progress = AnimalAgingPolicy.advance(
                settings, 10 * MINUTE_MS, 500 * MINUTE_MS, true, 0, 0);
        assertEquals(10 * MINUTE_MS, progress.getAgeProgressMs());
        assertEquals(1.0, progress.getYieldMultiplier());
        assertFalse(progress.isDead());
    }

    @Test
    void carePenaltyStartsBelowHalfAndUsesOnlyTheLowerNeed() throws Exception {
        AnimalAgingSettings settings = fullLifecycle();
        assertEquals(10 * MINUTE_MS, AnimalAgingPolicy.advance(
                settings, 0, 10 * MINUTE_MS, true, 50, 50).getAgeProgressMs());
        double oneLowNeed = AnimalAgingPolicy.advance(
                settings, 0, 10 * MINUTE_MS, true, 25, 100).getAgeProgressMs();
        assertEquals(5 * MINUTE_MS, oneLowNeed);
        assertEquals(oneLowNeed, AnimalAgingPolicy.advance(
                settings, 0, 10 * MINUTE_MS, true, 25, 25).getAgeProgressMs());
    }

    @Test
    void appliesCareRateOnEachSideOfPrimeCrossing() throws Exception {
        AnimalAgingSettings settings = fullLifecycle();

        AnimalAgingPolicy.Progress progress = AnimalAgingPolicy.advance(
                settings,
                59.0 * MINUTE_MS,
                10L * MINUTE_MS,
                true,
                0.0,
                0.0
        );

        // Four real minutes complete the last pre-prime minute at 25%; the
        // remaining six advance at 175% because prime/senior poor care ages faster.
        assertEquals(70.5 * MINUTE_MS, progress.getAgeProgressMs(), 0.001);
        assertEquals(AnimalAgingPolicy.Stage.PRIME, progress.getStage());
    }

    @Test
    void unloadedProgressIgnoresUnchangedLowNeeds() throws Exception {
        AnimalAgingSettings settings = fullLifecycle();

        AnimalAgingPolicy.Progress progress = AnimalAgingPolicy.advance(
                settings,
                0.0,
                10L * MINUTE_MS,
                false,
                0.0,
                0.0
        );

        assertEquals(10.0 * MINUTE_MS, progress.getAgeProgressMs(), 0.001);
    }

    @Test
    void freezeAtPrimeClampsAndFullLifecycleCanReachOldAgeDeath() throws Exception {
        AnimalAgingSettings frozen = fullLifecycle();
        setField(frozen, "mode", AnimalAgingSettings.LifecycleMode.FREEZE_AT_PRIME);

        AnimalAgingPolicy.Progress atPrime = AnimalAgingPolicy.advance(
                frozen, 59.0 * MINUTE_MS, 2L * MINUTE_MS, true, 100.0, 100.0
        );

        assertEquals(60.0 * MINUTE_MS, atPrime.getAgeProgressMs(), 0.001);
        assertEquals(AnimalAgingPolicy.Stage.PRIME, atPrime.getStage());
        assertFalse(atPrime.isDead());

        AnimalAgingSettings full = fullLifecycle();
        setField(full, "adultToPrimeMinutes", 1);
        setField(full, "primeMinutes", 1);
        setField(full, "seniorMinutes", 1);
        setField(full, "oldAgeDeathEnabled", true);
        AnimalAgingPolicy.Progress dead = AnimalAgingPolicy.advance(
                full, 0.0, 3L * MINUTE_MS, true, 100.0, 100.0
        );

        assertEquals(AnimalAgingPolicy.Stage.SENIOR, dead.getStage());
        assertTrue(dead.isDead());
        assertEquals(0L, dead.getRemainingStageMs());
    }

    private static AnimalAgingSettings fullLifecycle() throws Exception {
        AnimalAgingSettings settings = new AnimalAgingSettings();
        setField(settings, "enabled", true);
        setField(settings, "mode", AnimalAgingSettings.LifecycleMode.FULL);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
