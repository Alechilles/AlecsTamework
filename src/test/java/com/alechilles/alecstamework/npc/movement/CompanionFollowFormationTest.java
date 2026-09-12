package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CompanionFollowFormationTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void stationaryLeaderTurningDoesNotRotateAssignedTarget() {
        CompanionFollowFormation formation = new CompanionFollowFormation();
        Vector3d first = formation.update(
                new Vector3d(10.0, 20.0, -4.0), 0.0f, 0, 3.0, 2.0, 0.05, new Vector3d());

        Vector3d second = formation.update(
                new Vector3d(10.0, 20.0, -4.0), (float) (Math.PI / 2.0), 0, 3.0, 2.0, 0.05,
                new Vector3d());

        assertEquals(first, second,
                "Changing the master's camera heading while stationary must not rotate the group.");
    }

    @Test
    void movingLeaderReversalTurnsTheTargetGradually() {
        CompanionFollowFormation formation = new CompanionFollowFormation();
        formation.update(
                new Vector3d(0.0, 10.0, 0.0), (float) Math.PI, 0, 3.0, 0.0, 0.05, new Vector3d());

        formation.update(new Vector3d(0.0, 10.0, 0.2), 0.0f, 0, 3.0, 0.0, 0.05, new Vector3d());
        Vector3d firstReverse = formation.update(
                new Vector3d(0.0, 10.0, 0.0), 0.0f, 0, 3.0, 0.0, 0.05, new Vector3d());

        assertTrue(firstReverse.x < 0.0,
                "The first reverse frame should retain the old side of the trailing slot.");
        assertTrue(firstReverse.z < 0.0,
                "The target should not instantly jump to the opposite trailing side.");

        Vector3d settled = firstReverse;
        for (int frame = 0; frame < 80; frame++) {
            settled = formation.update(
                    new Vector3d(0.0, 10.0, -0.2 * (frame + 1)), 0.0f, 0, 3.0, 0.0, 0.05,
                    settled);
        }
        assertTrue(settled.z - (-16.0) > 0.0,
                "Continued reverse travel should eventually move the trailing slot behind the new heading.");
    }

    @Test
    void slotsRemainDistinctWhileAltitudeIsControlledByTheCaller() {
        Vector3d leader = new Vector3d(5.0, 20.0, -2.0);
        Vector3d first = new CompanionFollowFormation().update(
                leader, 0.0f, 0, 3.0, 4.0, 0.05, new Vector3d());
        Vector3d second = new CompanionFollowFormation().update(
                leader, 0.0f, 1, 3.0, -2.0, 0.05, new Vector3d());

        assertEquals(4.0, first.y, EPSILON);
        assertEquals(-2.0, second.y, EPSILON);
        assertTrue(first.distance(second) > 1.0,
                "Separate stable slots must not collapse onto the same target.");
        assertTrue(isFinite(first) && isFinite(second));
    }

    @Test
    void teleportResetsSlotSmoothingAtTheNewLeaderPosition() {
        CompanionFollowFormation formation = new CompanionFollowFormation();
        formation.update(new Vector3d(0.0, 10.0, 0.0), 0.0f, 0, 3.0, 1.0, 0.05, new Vector3d());

        Vector3d teleported = formation.update(
                new Vector3d(100.0, 40.0, -20.0), 0.0f, 0, 3.0, 1.0, 0.05, new Vector3d());
        Vector3d expectedOffset = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                0, 3.0, 0.0, new Vector3d(),
                BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(0.0f, new Vector3d()),
                new Vector3d());

        assertEquals(100.0 + expectedOffset.x, teleported.x, EPSILON);
        assertEquals(1.0, teleported.y, EPSILON);
        assertEquals(-20.0 + expectedOffset.z, teleported.z, EPSILON);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
