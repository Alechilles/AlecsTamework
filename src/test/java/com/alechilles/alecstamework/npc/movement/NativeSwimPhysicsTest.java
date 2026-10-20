package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NativeSwimPhysicsTest {
    private static final double EPSILON = 1.0e-9;
    private static final NativeSwimPhysics.Settings SETTINGS = NativeSwimPhysics.Settings.defaults();

    @Test
    void acceleratesToCruiseAndHoldsTheCruiseCap() {
        NativeSwimPhysics.Step first = NativeSwimPhysics.advance(0.0, 0.0, 1.0, false, 0.1, SETTINGS);
        NativeSwimPhysics.Step capped = NativeSwimPhysics.advance(7.9, 0.0, 1.0, false, 0.1, SETTINGS);

        assertEquals(0.4, first.speed(), EPSILON);
        assertEquals(8.0, capped.speed(), EPSILON);
    }

    @Test
    void coastsAndBrakesAtDifferentRatesAndNeverReverses() {
        NativeSwimPhysics.Step coasting = NativeSwimPhysics.advance(1.0, 0.0, 0.0, false, 0.1, SETTINGS);
        NativeSwimPhysics.Step braking = NativeSwimPhysics.advance(1.0, 0.0, -1.0, false, 0.1, SETTINGS);
        NativeSwimPhysics.Step stopped = NativeSwimPhysics.advance(0.2, 0.0, -1.0, false, 0.1, SETTINGS);

        assertEquals(0.8, coasting.speed(), EPSILON);
        assertEquals(0.2, braking.speed(), EPSILON);
        assertEquals(0.0, stopped.speed(), EPSILON);
    }

    @Test
    void boostIsCappedRecoversTowardCruiseAndCannotStackDuringCooldown() {
        NativeSwimPhysics.Step boosted = NativeSwimPhysics.advance(8.0, 0.0, 1.0, true, 0.1, SETTINGS);
        NativeSwimPhysics.Step recovering = NativeSwimPhysics.advance(
                boosted.speed(), boosted.boostCooldownSeconds(), 1.0, true, 0.1, SETTINGS);

        assertEquals(13.0, boosted.speed(), EPSILON);
        assertEquals(2.0, boosted.boostCooldownSeconds(), EPSILON);
        assertEquals(12.7, recovering.speed(), EPSILON);
        assertEquals(1.9, recovering.boostCooldownSeconds(), EPSILON);

        NativeSwimPhysics.Step cappedBoost = NativeSwimPhysics.advance(13.0, 0.0, 0.0, true, 0.1, SETTINGS);
        assertEquals(13.0, cappedBoost.speed(), EPSILON);
    }

    @Test
    void clampsLargeTimeStepsAndContainsInvalidInputs() {
        NativeSwimPhysics.Step stalled = NativeSwimPhysics.advance(0.0, 0.0, 1.0, false, 10.0, SETTINGS);
        NativeSwimPhysics.Step invalid = NativeSwimPhysics.advance(
                Double.NaN, Double.NaN, 1.0, true, 0.1, SETTINGS);

        assertEquals(0.4, stalled.speed(), EPSILON);
        assertEquals(0.0, invalid.speed(), EPSILON);
        assertEquals(0.0, invalid.boostCooldownSeconds(), EPSILON);
    }
}
