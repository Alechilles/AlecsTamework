package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementIntentProjectorTest {

    @Test
    void projectsForwardRelativeToYaw() {
        MovementIntentProjector.AxisProjection projection =
                MovementIntentProjector.project(0.0, -1.0, new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0));

        assertEquals("forward", projection.label());
        assertTrue(projection.forward() > 0.99);
        assertEquals(0.0, projection.strafe(), 0.00001);
    }

    @Test
    void projectsBackwardRelativeToYaw() {
        MovementIntentProjector.AxisProjection projection =
                MovementIntentProjector.project(0.0, 1.0, new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0));

        assertEquals("back", projection.label());
        assertTrue(projection.forward() < -0.99);
    }

    @Test
    void projectsLeftAndRightRelativeToYaw() {
        MovementIntentProjector.DirectionSnapshot basis = new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0);

        assertEquals("right", MovementIntentProjector.project(1.0, 0.0, basis).label());
        assertEquals("left", MovementIntentProjector.project(-1.0, 0.0, basis).label());
    }

    @Test
    void nearZeroMovementIsNeutral() {
        MovementIntentProjector.AxisProjection projection =
                MovementIntentProjector.project(0.0, 0.0, new MovementIntentProjector.DirectionSnapshot(0.0, 0.0, 0.0));

        assertEquals("idle", projection.label());
        assertEquals(0.0, projection.forward(), 0.00001);
        assertEquals(0.0, projection.strafe(), 0.00001);
    }
}
