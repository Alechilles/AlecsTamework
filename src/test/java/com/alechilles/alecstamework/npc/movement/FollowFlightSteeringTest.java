package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FollowFlightSteeringTest {
    @Test
    void settledBirdIgnoresSmallDriftButResumesAfterLargerDisplacement() {
        var steering = new FollowFlightSteering();
        var output = new Vector3d();
        var target = new Vector3d(0, 5, 0);
        steering.resolve(new Vector3d(0, 5.2, 0), target, .65, 3, 1, .5, output);
        assertEquals(new Vector3d(), output);
        steering.resolve(new Vector3d(.5, 4.5, 0), target, .65, 3, 1, .5, output);
        assertEquals(new Vector3d(), output);
        steering.resolve(new Vector3d(2, 5, 0), target, .65, 3, 1, .5, output);
        assertTrue(output.x < 0);
        steering.resolve(new Vector3d(.7, 5, 0), target, .65, 3, 1, .5, output);
        assertTrue(output.x < 0, "An approaching bird must continue into the inner settling area");
    }

    @Test
    void altitudeApproachSlowsBeforeArrivalAndDoesNotBounceAcrossTarget() {
        var steering = new FollowFlightSteering();
        var output = new Vector3d();
        var target = new Vector3d(0, 5, 0);
        var position = new Vector3d(0, 8, 0);
        double previousSpeed = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 300; tick++) {
            steering.resolve(position, target, .65, 3, 1, .5, output);
            assertTrue(output.y <= 0, "No alternating climb/sink near a stationary target");
            assertTrue(Math.abs(output.y) <= previousSpeed + 1e-9);
            previousSpeed = Math.abs(output.y);
            position.fma(.05 * 10, output);
        }
        assertTrue(position.distance(target) < .5);
        assertEquals(new Vector3d(), output);
    }
}
