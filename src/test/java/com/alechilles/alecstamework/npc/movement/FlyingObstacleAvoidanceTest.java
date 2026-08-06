package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class FlyingObstacleAvoidanceTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void lookaheadClampsSlowAndFastFlight() {
        assertEquals(4.0, FlyingObstacleAvoidance.lookaheadDistance(0.1, 1.0, 0.0), EPSILON);
        assertEquals(12.0, FlyingObstacleAvoidance.lookaheadDistance(2.0, 20.0, 8.0), EPSILON);
    }

    @Test
    void clearRouteUsesOneProbeAndPreservesDesiredTranslation() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(0.5, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                Vector3d::length,
                new Vector3d());

        assertEquals(new Vector3d(0.5, 0.0, 0.0), result);
        assertEquals(1, avoidance.getProbesThisUpdate());
    }

    @Test
    void blockedRouteClimbsWhenClimbCandidateIsClear() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.y > 0.0 && Math.abs(direction.z) < EPSILON
                        ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(result.x > 0.0);
        assertTrue(result.y > 0.0);
    }

    @Test
    void blockedRouteCanChooseRightDiagonal() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.y > 0.0 && direction.z > 0.0
                        ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(result.x > 0.0);
        assertTrue(result.y > 0.0);
        assertTrue(result.z > 0.0);
    }

    @Test
    void blockedDecisionNeverExceedsSixProbes() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> 0.0,
                new Vector3d());

        assertEquals(6, avoidance.getProbesThisUpdate());
    }

    @Test
    void bestPartialClearanceReducesSpeed() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.y > 0.0 && Math.abs(direction.z) < EPSILON
                        ? direction.length() * 0.5 : direction.length() * 0.1,
                new Vector3d());

        assertEquals(0.5, result.length(), EPSILON);
    }

    @Test
    void fullyTrappedRouteStopsTranslation() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.length() * 0.2,
                new Vector3d());

        assertEquals(0.0, result.lengthSquared(), EPSILON);
    }

    @Test
    void fullyTrappedStopPersistsInsideCadenceWindow() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.length() * 0.2,
                new Vector3d());

        avoidance.beginUpdate(0.05);
        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> {
                    throw new AssertionError("blocked cadence tick must not probe");
                },
                new Vector3d());

        assertEquals(0.0, result.lengthSquared(), EPSILON);
        assertEquals(0, avoidance.getProbesThisUpdate());
    }

    @Test
    void descendingObstacleUsesLevelOrUpwardAlternative() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(0.0, -1.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.y > 0.0 ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(result.y > 0.0);
    }

    @Test
    void selectedSideIsHeldWithoutAnotherProbeInsideCadenceWindow() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        Vector3d first = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.z > 0.0 && direction.y == 0.0
                        ? direction.length() : 0.0,
                new Vector3d());
        assertTrue(first.z > 0.0);

        avoidance.beginUpdate(0.05);
        Vector3d held = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> {
                    throw new AssertionError("hold should not probe before cadence expires");
                },
                new Vector3d());

        assertTrue(held.z > 0.0);
        assertEquals(0, avoidance.getProbesThisUpdate());
    }

    @Test
    void heldRouteClosureRemainsStoppedInsideNextCadenceWindow() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.z > 0.0 && direction.y == 0.0
                        ? direction.length() : 0.0,
                new Vector3d());

        avoidance.beginUpdate(FlyingObstacleAvoidance.PROBE_INTERVAL_SECONDS);
        Vector3d stopped = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> 0.0,
                new Vector3d());
        assertEquals(0.0, stopped.lengthSquared(), EPSILON);

        avoidance.beginUpdate(0.05);
        Vector3d stillStopped = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> {
                    throw new AssertionError("known-blocked held route must not resume");
                },
                new Vector3d());
        assertEquals(0.0, stillStopped.lengthSquared(), EPSILON);
    }

    @Test
    void clearHeldRouteExtendsHoldWithOnlyTwoProbes() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.z > 0.0 && direction.y == 0.0
                        ? direction.length() : 0.0,
                new Vector3d());

        avoidance.beginUpdate(FlyingObstacleAvoidance.HOLD_SECONDS);
        Vector3d result = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.z > 0.0 && direction.y == 0.0
                        ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(result.z > 0.0);
        assertEquals(2, avoidance.getProbesThisUpdate());
    }

    @Test
    void upwardCeilingCanChooseLevelLateralEscape() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);

        Vector3d result = avoidance.adjust(
                new Vector3d(0.0, 1.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> Math.abs(direction.y) < EPSILON
                        ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(Math.hypot(result.x, result.z) > 0.0);
        assertEquals(0.0, result.y, EPSILON);
    }

    @Test
    void rememberedSideBreaksEqualClearanceTieAfterHoldExpires() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        Vector3d first = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.z > 0.0 && direction.y == 0.0
                        ? direction.length() : 0.0,
                new Vector3d());
        assertTrue(first.z > 0.0);

        avoidance.beginUpdate(FlyingObstacleAvoidance.HOLD_SECONDS);
        Vector3d tied = avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> direction.y == 0.0 && Math.abs(direction.z) > EPSILON
                        ? direction.length() : 0.0,
                new Vector3d());

        assertTrue(tied.z > 0.0);
    }

    @Test
    void waypointProbesShareTheSixProbeUpdateBudget() {
        FlyingObstacleAvoidance avoidance = new FlyingObstacleAvoidance();
        avoidance.beginUpdate(0.1);
        for (int i = 0; i < 3; i++) {
            avoidance.probeWaypoint(
                    new Vector3d(12.0, 0.0, 0.0),
                    12.0,
                    0.0,
                    direction -> 0.0);
        }

        avoidance.adjust(
                new Vector3d(1.0, 0.0, 0.0),
                new Vector3d(1.0, 0.0, 0.0),
                12.0,
                0.0,
                direction -> 0.0,
                new Vector3d());

        assertEquals(6, avoidance.getProbesThisUpdate());
    }
}
