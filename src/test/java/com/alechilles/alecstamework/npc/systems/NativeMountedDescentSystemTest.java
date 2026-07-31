package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.movement.NativeMountedDescentPhysics;
import org.junit.jupiter.api.Test;

class NativeMountedDescentSystemTest {
    private static final NativeMountedDescentPhysics.Settings SETTINGS =
            new NativeMountedDescentPhysics.Settings(4.5, 0.55);
    private static final double EPSILON = 1.0e-9;

    @Test
    void activatesOnlyForAnAirborneDescendingConfiguredNativeMount() {
        assertTrue(NativeMountedDescentSystem.shouldApply(false, false, -1.0, false, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(true, false, -1.0, false, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(false, true, -1.0, false, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(false, false, 0.0, false, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(false, false, 1.0, false, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(false, false, -1.0, true, SETTINGS));
        assertFalse(NativeMountedDescentSystem.shouldApply(
                false, false, -1.0, false, new NativeMountedDescentPhysics.Settings(0.0, 0.55)));
    }

    @Test
    void usesTheLastConstrainedVelocityForTheNextFallStep() {
        double next = NativeMountedDescentSystem.nextConstrainedVelocity(-2.0, -3.5, SETTINGS, 0.25);

        assertEquals(-4.5, next, EPSILON);
    }

    @Test
    void reseedsVelocityWhenTheObservedFallHasResetAfterDismount() {
        assertTrue(NativeMountedDescentSystem.shouldReseed(-4.5, -0.4));
        assertFalse(NativeMountedDescentSystem.shouldReseed(-2.0, -3.0));
    }
}
