package com.alechilles.alecstamework.vfx.projectile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class HomingVisualProjectileMotionTest {
    @Test
    void advancesWithoutOvershooting() {
        HomingVisualProjectileMotion.Step step = HomingVisualProjectileMotion.step(
                new Vector3d(), new Vector3d(10.0D, 0.0D, 0.0D), new Vector3d(),
                8.0D, 0.0D, 0.18D, 0.05D
        );

        assertTrue(step.valid());
        assertFalse(step.arrived());
        assertEquals(0.4D, step.position().x, 0.00001D);
        assertEquals(new Vector3d(1.0D, 0.0D, 0.0D), step.direction());
    }

    @Test
    void arrivesWhenNextStepWouldEnterArrivalRadius() {
        HomingVisualProjectileMotion.Step step = HomingVisualProjectileMotion.step(
                new Vector3d(), new Vector3d(0.5D, 0.0D, 0.0D), new Vector3d(),
                8.0D, 0.0D, 0.18D, 0.05D
        );

        assertTrue(step.valid());
        assertTrue(step.arrived());
        assertEquals(new Vector3d(0.5D, 0.0D, 0.0D), step.position());
    }

    @Test
    void samplesMovingDestinationEveryStep() {
        HomingVisualProjectileMotion.Step first = HomingVisualProjectileMotion.step(
                new Vector3d(), new Vector3d(10.0D, 0.0D, 0.0D), new Vector3d(),
                8.0D, 0.0D, 0.18D, 0.05D
        );
        HomingVisualProjectileMotion.Step second = HomingVisualProjectileMotion.step(
                first.position(), new Vector3d(0.0D, 0.0D, 10.0D), first.direction(),
                8.0D, 0.0D, 0.18D, 0.05D
        );

        assertTrue(second.direction().z > 0.99D);
        assertTrue(second.direction().x < 0.0D);
    }

    @Test
    void boundedTurnDoesNotInstantlyReverse() {
        Vector3d direction = HomingVisualProjectileMotion.limitedDirection(
                new Vector3d(1.0D, 0.0D, 0.0D),
                new Vector3d(-1.0D, 0.0D, 0.0D),
                90.0D,
                0.5D
        );

        assertEquals(Math.sqrt(0.5D), direction.x, 0.00001D);
        assertEquals(Math.sqrt(0.5D), Math.abs(direction.y), 0.00001D);
    }

    @Test
    void invalidInputsFailClosed() {
        assertFalse(HomingVisualProjectileMotion.step(
                new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D), new Vector3d(),
                0.0D, 0.0D, 0.18D, 0.05D
        ).valid());
        assertFalse(HomingVisualProjectileMotion.step(
                new Vector3d(Double.NaN, 0.0D, 0.0D), new Vector3d(), new Vector3d(),
                8.0D, 0.0D, 0.18D, 0.05D
        ).valid());
        assertFalse(HomingVisualProjectileMotion.step(
                new Vector3d(), new Vector3d(1.0D, 0.0D, 0.0D), new Vector3d(),
                8.0D, 0.0D, 0.18D, -1.0D
        ).valid());
    }
}
