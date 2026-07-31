package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NativeMountedDescentPhysicsTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void advancesFallingSpeedUsingConfiguredAccelerationMultiplier() {
        NativeMountedDescentPhysics.Settings settings = new NativeMountedDescentPhysics.Settings(20.0, 0.55);

        double nextVelocity = NativeMountedDescentPhysics.advanceDescending(-2.0, settings, 0.25);

        assertEquals(-6.4, nextVelocity, EPSILON);
    }

    @Test
    void capsFallingSpeedAtConfiguredMaximum() {
        NativeMountedDescentPhysics.Settings settings = new NativeMountedDescentPhysics.Settings(4.5, 0.55);

        double nextVelocity = NativeMountedDescentPhysics.advanceDescending(-4.0, settings, 0.25);

        assertEquals(-4.5, nextVelocity, EPSILON);
    }

    @Test
    void keepsNonDescendingVelocityUntouched() {
        NativeMountedDescentPhysics.Settings settings = new NativeMountedDescentPhysics.Settings(4.5, 0.55);

        assertEquals(0.0, NativeMountedDescentPhysics.advanceDescending(0.0, settings, 0.25), EPSILON);
        assertEquals(2.0, NativeMountedDescentPhysics.advanceDescending(2.0, settings, 0.25), EPSILON);
    }

    @Test
    void keepsVelocityUntouchedForInvalidSettingsOrElapsedTime() {
        NativeMountedDescentPhysics.Settings valid = new NativeMountedDescentPhysics.Settings(4.5, 0.55);

        assertEquals(-2.0, NativeMountedDescentPhysics.advanceDescending(-2.0, valid, 0.0), EPSILON);
        assertEquals(-2.0, NativeMountedDescentPhysics.advanceDescending(-2.0, valid, -0.25), EPSILON);
        assertEquals(-2.0, NativeMountedDescentPhysics.advanceDescending(
                -2.0, new NativeMountedDescentPhysics.Settings(0.0, 0.55), 0.25), EPSILON);
        assertEquals(-2.0, NativeMountedDescentPhysics.advanceDescending(
                -2.0, new NativeMountedDescentPhysics.Settings(4.5, 0.0), 0.25), EPSILON);
    }
}
