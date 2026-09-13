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
    void matchingSlotMotionReducesTurnLagWithoutLockingFollowersToTheirSlots() {
        double dt = 0.05;
        double maximumSpeed = 10.0;
        Vector3d leader = new Vector3d();
        Vector3d leaderVelocity = new Vector3d(0.0, 0.0, 4.0);
        double coordinatedError = 0.0;
        double reactiveError = 0.0;
        for (int slot = 0; slot < 2; slot++) {
            leader.zero();
            Vector3d offset = FlightFormationSteering.resolveTarget(
                    BuilderBodyMotionTameworkFlightFormation.Formation.CHEVRON,
                    slot, 3.0, 0.0, leader, new Vector3d(0, 0, 1), new Vector3d());
            Vector3d coordinated = new Vector3d(offset);
            Vector3d reactive = new Vector3d(offset);
            Vector3d previous = new Vector3d();
            Vector3d desired = new Vector3d();
            Vector3d target = new Vector3d();
            Vector3d velocity = new Vector3d();
            Vector3d steering = new Vector3d();
            for (int frame = 1; frame <= 100; frame++) {
                double angle = frame * dt * 0.15;
                Vector3d heading = new Vector3d(Math.sin(angle), 0, Math.cos(angle));
                leaderVelocity.set(heading).mul(4.0);
                leader.fma(dt, leaderVelocity);
                FlightFormationSteering.resolveTarget(
                        BuilderBodyMotionTameworkFlightFormation.Formation.CHEVRON,
                        slot, 3.0, 0.0, new Vector3d(), heading, desired);
                previous.set(offset);
                FlightFormationSteering.smoothOffset(offset, desired, 2.25, dt, offset);
                target.set(leader).add(offset);
                // Both birds already share the leader's translation; measure only their turn correction.
                coordinated.fma(dt, leaderVelocity);
                reactive.fma(dt, leaderVelocity);
                FlightFormationSteering.resolveSlotVelocity(previous, offset, leaderVelocity, dt, velocity);
                FlightFormationSteering.resolveTranslation(
                        coordinated, target, velocity, maximumSpeed, 0.8, 0.6, dt, steering);
                coordinated.fma(dt * maximumSpeed, steering).fma(-dt, leaderVelocity);
                FlightFormationSteering.resolveTranslation(
                        reactive, target, leaderVelocity, maximumSpeed, 0.8, 0.6, dt, steering);
                reactive.fma(dt * maximumSpeed, steering).fma(-dt, leaderVelocity);
                coordinatedError += coordinated.distance(target);
                reactiveError += reactive.distance(target);
            }
        }
        assertTrue(coordinatedError < reactiveError * 0.5,
                "Turn movement should reduce the slot lag on both arms of the chevron.");
        assertTrue(coordinatedError > 0.01,
                "Followers should retain some elastic drift rather than snap to their slots.");
    }

    @Test
    void slotMotionPreservesCruiseAndRespectsFlightSpeedDuringSharpTurns() {
        Vector3d leaderVelocity = new Vector3d(0, 0, 4);
        Vector3d offset = new Vector3d(-3, 0, -3);
        Vector3d velocity = FlightFormationSteering.resolveSlotVelocity(
                offset, offset, leaderVelocity, 0.05, new Vector3d());
        assertEquals(leaderVelocity, velocity);
        FlightFormationSteering.resolveSlotVelocity(offset, new Vector3d(3, 0, -3),
                leaderVelocity, 0.05, velocity);
        Vector3d steering = FlightFormationSteering.resolveTranslation(
                new Vector3d(), new Vector3d(), velocity, 8, 0.8, 0.6, 0.05, new Vector3d());
        assertTrue(steering.x > 0, "The bird should move with its turning slot even without position error.");
        assertTrue(steering.length() <= 1.0 + EPSILON);
        FlightFormationSteering.resolveSlotVelocity(offset, new Vector3d(), leaderVelocity, 0, velocity);
        assertEquals(leaderVelocity, velocity, "A paused update must not produce a turn impulse.");
    }

    @Test
    void groundSlotsStaySpreadHorizontallyAndUseTheFollowersTerrainHeight() {
        Vector3d leader = new Vector3d(0, 40, 0);
        Vector3d heading = new Vector3d(0, 0, 1);
        Vector3d first = BodyMotionTameworkGroundFormation.resolveGroundTarget(
                0, 5, 0, leader, heading, 12, new Vector3d());
        Vector3d second = BodyMotionTameworkGroundFormation.resolveGroundTarget(
                1, 5, 0, leader, heading, 12, new Vector3d());
        assertEquals(12, first.y, EPSILON);
        assertEquals(12, second.y, EPSILON);
        assertTrue(first.x * second.x < 0, "Followers should occupy both sides of the travel direction.");
        assertTrue(first.distance(second) > 3, "Distinct ground slots must not collapse into one trail.");
    }

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
                assertTrue(Math.abs(target.y - leader.y) < 3.2,
                        "Loose height variation should remain bounded at normal spacing.");
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
    void clusterSlotsStayCompactSeparatedAndGentlyMobile() {
        Vector3d leader = new Vector3d(100.0, 80.0, -50.0);
        Vector3d heading = new Vector3d(3.0, 0.0, 4.0);
        Vector3d[] slots = new Vector3d[12];
        for (int second = 0; second <= 30; second += 5) {
            for (int slot = 0; slot < slots.length; slot++) {
                Vector3d target = FlightFormationSteering.resolveTarget(
                        BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER,
                        slot, 3.0, second, leader, heading, new Vector3d());
                assertTrue(Double.isFinite(target.x) && Double.isFinite(target.y) && Double.isFinite(target.z));
                assertTrue(target.distance(leader) < 9.0,
                        "A cluster follower should remain near its flock leader.");
                for (int other = 0; other < slot; other++) {
                    assertTrue(target.distance(slots[other]) > 1.15,
                            "Cluster followers must retain enough separation to avoid overlapping.");
                }
                slots[slot] = target;
            }
        }

        Vector3d initial = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER,
                4, 3.0, 0.0, leader, heading, new Vector3d());
        Vector3d later = FlightFormationSteering.resolveTarget(
                BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER,
                4, 3.0, 20.0, leader, heading, new Vector3d());
        double driftDistance = initial.distance(later);
        assertTrue(driftDistance > 0.05 && driftDistance < 1.25,
                "Cluster movement should be visible without breaking its compact spacing.");
    }

    @Test
    void clusterAndLooseUseDeterministicThreeDimensionalSlots() {
        Vector3d leader = new Vector3d(100.0, 80.0, -50.0);
        Vector3d heading = new Vector3d(3.0, 0.0, 4.0);
        for (BuilderBodyMotionTameworkFlightFormation.Formation formation : new BuilderBodyMotionTameworkFlightFormation.Formation[] {
                BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER,
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE }) {
            Vector3d first = FlightFormationSteering.resolveTarget(
                    formation, 437, 3.0, 23.5, leader, heading, new Vector3d());
            Vector3d repeat = FlightFormationSteering.resolveTarget(
                    formation, 437, 3.0, 23.5, leader, heading, new Vector3d());
            assertEquals(first, repeat, "A slot must not depend on which bird or tick evaluated it.");

            double minimumY = Double.POSITIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            double minimumSideways = Double.POSITIVE_INFINITY;
            double maximumSideways = Double.NEGATIVE_INFINITY;
            for (int slot = 0; slot < 1_000; slot++) {
                Vector3d target = FlightFormationSteering.resolveTarget(
                        formation, slot, 3.0, 23.5, leader, heading, new Vector3d());
                double sideways = (target.x - leader.x) * -0.8 + (target.z - leader.z) * 0.6;
                minimumY = Math.min(minimumY, target.y);
                maximumY = Math.max(maximumY, target.y);
                minimumSideways = Math.min(minimumSideways, sideways);
                maximumSideways = Math.max(maximumSideways, sideways);
            }
            assertTrue(maximumY - minimumY > 2.0,
                    "Large formations should occupy a volume instead of a few repeated height bands.");
            assertTrue(maximumSideways - minimumSideways > 20.0,
                    "Large formations should remain laterally spread.");
        }
    }

    @Test
    void formationDeformationChangesShapeSlowlyWithoutARigidGroupShift() {
        Vector3d leader = new Vector3d();
        Vector3d heading = new Vector3d(0.0, 0.0, 1.0);
        for (BuilderBodyMotionTameworkFlightFormation.Formation formation : new BuilderBodyMotionTameworkFlightFormation.Formation[] {
                BuilderBodyMotionTameworkFlightFormation.Formation.CLUSTER,
                BuilderBodyMotionTameworkFlightFormation.Formation.LOOSE }) {
            Vector3d slotA = FlightFormationSteering.resolveTarget(
                    formation, 137, 3.0, 30.0, leader, heading, new Vector3d());
            Vector3d slotANext = FlightFormationSteering.resolveTarget(
                    formation, 137, 3.0, 30.05, leader, heading, new Vector3d());
            Vector3d slotB = FlightFormationSteering.resolveTarget(
                    formation, 781, 3.0, 30.0, leader, heading, new Vector3d());
            Vector3d slotBNext = FlightFormationSteering.resolveTarget(
                    formation, 781, 3.0, 30.05, leader, heading, new Vector3d());
            Vector3d slotALater = FlightFormationSteering.resolveTarget(
                    formation, 137, 3.0, 120.0, leader, heading, new Vector3d());

            assertTrue(slotA.distance(slotANext) < 0.015,
                    "Slow slot deformation must remain within the formation steering speed budget.");
            assertTrue(Math.abs(slotA.distance(slotB) - slotANext.distance(slotBNext)) > 1.0E-6,
                    "Individual phases must alter the flock's internal shape.");
            assertTrue(Math.abs(slotA.distance(slotB) - slotANext.distance(slotBNext)) < 0.03,
                    "Adjacent shape changes should remain smooth.");
            assertTrue(slotA.distance(slotALater) > 0.1,
                    "Each slot should visibly evolve over a longer interval.");
        }
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
