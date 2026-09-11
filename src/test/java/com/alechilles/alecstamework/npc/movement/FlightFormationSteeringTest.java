package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FlightFormationSteeringTest {
    private static final double EPSILON = 1.0E-9;
    private static final double YAW_EPSILON = 1.0E-6;

    @Test
    void outerChevronSlotChangesAreBoundedAndConvergeWithoutDelayingLeaderTravel() {
        Vector3d offset = new Vector3d(-12.0, 0.0, -12.0);
        Vector3d desired = new Vector3d(12.0, 0.0, -12.0);
        Vector3d before = new Vector3d(offset);
        FlightFormationSteering.smoothOffset(offset, desired, 2.0, 0.05, offset);
        assertTrue(offset.distance(before) <= 0.1 + EPSILON,
                "A sharp leader turn must not sweep an outer slot across the flock in one frame.");
        for (int frame = 0; frame < 400; frame++) {
            FlightFormationSteering.smoothOffset(offset, desired, 2.0, 0.05, offset);
        }
        assertTrue(offset.distance(desired) < 0.001);

        Vector3d movingLeader = new Vector3d(0.0, 40.0, 10.0);
        Vector3d target = new Vector3d(movingLeader).add(offset);
        movingLeader.z += 0.2;
        FlightFormationSteering.smoothOffset(offset, desired, 2.0, 0.05, offset);
        Vector3d nextTarget = new Vector3d(movingLeader).add(offset);
        assertEquals(0.2, nextTarget.z - target.z, EPSILON);
    }

    @Test
    void looseFormationStaggersAboveAndBelowLeaderThroughoutItsDrift() {
        Vector3d leader = new Vector3d(0.0, 40.0, 0.0);
        Vector3d heading = new Vector3d(0.0, 0.0, 1.0);
        Vector3d target = new Vector3d();
        for (int second = 0; second < 60; second++) {
            boolean above = false;
            boolean below = false;
            for (int slot = 0; slot < 6; slot++) {
                FlightFormationSteering.resolveTarget(
                        BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                        slot, 3.0, second, leader, heading, target);
                above |= target.y > leader.y + 0.3;
                below |= target.y < leader.y - 0.3;
                assertTrue(Math.abs(target.y - leader.y) < 1.5,
                        "Height staggering should stay modest at normal spacing.");
            }
            assertTrue(above && below, "The flock needs depth above and below its leader.");
        }
    }

    @Test
    void formationTurnsSmoothlyWhenLeaderReversesDirection() {
        Vector3d heading = new Vector3d(0.0, 0.0, 1.0);
        Vector3d desired = new Vector3d(0.0, 0.0, -1.0);

        FlightFormationSteering.smoothHeading(heading, desired, 0.05, heading);
        assertTrue(Math.abs(heading.x) > 0.1);
        assertTrue(heading.z > 0.0, "The first frame should turn gradually rather than flip.");

        for (int frame = 0; frame < 40; frame++) {
            FlightFormationSteering.smoothHeading(heading, desired, 0.05, heading);
        }
        assertTrue(heading.z < -0.99, "The formation must converge on the reversed travel heading.");
    }

    @Test
    void chevronSlotsRotateWithLeaderTravelHeading() {
        Vector3d target = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.CHEVRON,
                0, 3.0, 0.0,
                new Vector3d(10.0, 40.0, -3.0), new Vector3d(1.0, 0.0, 0.0), new Vector3d());

        assertEquals(7.0, target.x, EPSILON);
        assertEquals(40.0, target.y, EPSILON);
        assertEquals(0.0, target.z, EPSILON);
    }

    @Test
    void looseSlotsUseACompactExpandingFootprintWhileDriftingAsOneMovingGroup() {
        Vector3d first = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                0, 3.0, 4.0,
                new Vector3d(), new Vector3d(0.0, 0.0, 1.0), new Vector3d());
        Vector3d second = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE,
                1, 3.0, 4.0,
                new Vector3d(), new Vector3d(0.0, 0.0, 1.0), new Vector3d());
        Vector3d translation = FlightFormationSteering.resolveTranslation(
                new Vector3d(0.0, 0.0, -3.0), first, new Vector3d(0.0, 0.0, 4.0),
                8.0, 0.8, 0.6, 0.05, new Vector3d());

        assertNotEquals(first.x, second.x, EPSILON);
        assertTrue(translation.z > 0.0);
        assertTrue(translation.length() <= 1.0 + EPSILON);
    }

    @Test
    void yawFallbackUsesTheEngineForwardDirectionForRotatedChevronSlots() {
        Vector3d heading = BodyMotionTameworkFlightFormation.resolveHeadingFromYaw(
                (float) (Math.PI / 2.0), new Vector3d());
        Vector3d target = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.CHEVRON,
                0, 3.0, 0.0, new Vector3d(), heading, new Vector3d());

        assertEquals(-1.0, heading.x, YAW_EPSILON);
        assertEquals(0.0, heading.z, YAW_EPSILON);
        assertEquals(3.0, target.x, YAW_EPSILON);
        assertEquals(-3.0, target.z, YAW_EPSILON);
    }

    @Test
    void positionCorrectionIsBoundedAndDoesNotOvershootTheAssignedSlot() {
        Vector3d translation = FlightFormationSteering.resolveTranslation(
                new Vector3d(), new Vector3d(1.0, 0.0, 0.0), new Vector3d(),
                10.0, 0.8, 1.0, 2.0, new Vector3d());

        assertEquals(0.05, translation.x, EPSILON);
        assertEquals(0.0, translation.y, EPSILON);
        assertEquals(0.0, translation.z, EPSILON);
    }

    @Test
    void leaderVelocityIsPreservedWhenTheFollowerAlreadyHoldsItsSlot() {
        Vector3d translation = FlightFormationSteering.resolveTranslation(
                new Vector3d(8.0, 3.0, -2.0), new Vector3d(8.0, 3.0, -2.0),
                new Vector3d(3.0, 0.5, -4.0), 10.0, 0.8, 0.6, 0.05, new Vector3d());

        assertEquals(0.3, translation.x, EPSILON);
        assertEquals(0.05, translation.y, EPSILON);
        assertEquals(-0.4, translation.z, EPSILON);
    }
}
