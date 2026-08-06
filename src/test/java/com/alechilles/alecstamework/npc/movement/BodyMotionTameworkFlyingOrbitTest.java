package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BodyMotionTameworkFlyingOrbitTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void orbitAtPreferredRadiusMovesTangentiallyAtConfiguredSpeed() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveOrbitTranslation(
                20.0, 0.0, 0.0, 0.0, 20.0, 4.0, 1, 0.36, new Vector3d());

        assertEquals(0.0, result.x, EPSILON);
        assertEquals(0.36, result.z, EPSILON);
        assertEquals(0.36, result.length(), EPSILON);
    }

    @Test
    void orbitCorrectsInwardWhenOutsideRadiusBand() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveOrbitTranslation(
                30.0, 0.0, 0.0, 0.0, 20.0, 4.0, 1, 0.36, new Vector3d());

        assertTrue(result.x < 0.0);
        assertTrue(result.z > 0.0);
        assertEquals(0.36, result.length(), EPSILON);
    }

    @Test
    void orbitCorrectsOutwardWhenInsideRadiusBand() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveOrbitTranslation(
                10.0, 0.0, 0.0, 0.0, 20.0, 4.0, 1, 0.36, new Vector3d());

        assertTrue(result.x > 0.0);
        assertTrue(result.z > 0.0);
        assertEquals(0.36, result.length(), EPSILON);
    }

    @Test
    void approachMovesTowardTargetAndStopsAtConfiguredDistance() {
        Vector3d moving = BodyMotionTameworkFlyingOrbit.resolveApproachTranslation(
                20.0, 0.0, 0.0, 0.0, 6.0, 14.0, 0.36, new Vector3d());
        Vector3d stopped = BodyMotionTameworkFlyingOrbit.resolveApproachTranslation(
                6.0, 0.0, 0.0, 0.0, 6.0, 14.0, 0.36, new Vector3d());

        assertTrue(moving.x < 0.0);
        assertEquals(0.36, moving.length(), EPSILON);
        assertEquals(0.0, stopped.lengthSquared(), EPSILON);
    }

    @Test
    void explicitModesDoNotInheritTheCyclePhase() {
        assertTrue(BodyMotionTameworkFlyingOrbit.usesApproachSteering(
                BuilderBodyMotionTameworkFlyingOrbit.Mode.APPROACH,
                BodyMotionTameworkFlyingOrbit.Phase.ORBIT));
        assertFalse(BodyMotionTameworkFlyingOrbit.usesApproachSteering(
                BuilderBodyMotionTameworkFlyingOrbit.Mode.ORBIT,
                BodyMotionTameworkFlyingOrbit.Phase.APPROACH));
        assertFalse(BodyMotionTameworkFlyingOrbit.usesApproachSteering(
                BuilderBodyMotionTameworkFlyingOrbit.Mode.PASS_THROUGH_TARGET,
                BodyMotionTameworkFlyingOrbit.Phase.APPROACH));
    }

    @Test
    void faceTargetModeTurnsWithoutHorizontalMovementMode() {
        assertTrue(BodyMotionTameworkFlyingOrbit.facesTarget(
                BuilderBodyMotionTameworkFlyingOrbit.Mode.FACE_TARGET,
                BodyMotionTameworkFlyingOrbit.Phase.ORBIT));
        assertFalse(BodyMotionTameworkFlyingOrbit.usesApproachSteering(
                BuilderBodyMotionTameworkFlyingOrbit.Mode.FACE_TARGET,
                BodyMotionTameworkFlyingOrbit.Phase.ORBIT));
    }

    @Test
    void altitudeCorrectionUsesIndependentClimbAndSinkSpeeds() {
        assertEquals(1.0, BodyMotionTameworkFlyingOrbit.resolveAltitudeCorrection(
                3.0, 12.0, 18.0, 1.0, 0.4), EPSILON);
        assertEquals(-0.4, BodyMotionTameworkFlyingOrbit.resolveAltitudeCorrection(
                20.0, 12.0, 18.0, 1.0, 0.4), EPSILON);
        assertEquals(0.0, BodyMotionTameworkFlyingOrbit.resolveAltitudeCorrection(
                15.0, 12.0, 18.0, 1.0, 0.4), EPSILON);
    }

    @Test
    void targetRelativeAltitudeCorrectionTracksTargetElevation() {
        double[] altitudeRange = { 8.0, 12.0 };

        assertEquals(0.7, BodyMotionTameworkFlyingOrbit.resolveTargetRelativeAltitudeCorrection(
                17.0, 10.0, altitudeRange, 0.7, 0.5), EPSILON);
        assertEquals(0.0, BodyMotionTameworkFlyingOrbit.resolveTargetRelativeAltitudeCorrection(
                20.0, 10.0, altitudeRange, 0.7, 0.5), EPSILON);
        assertEquals(-0.5, BodyMotionTameworkFlyingOrbit.resolveTargetRelativeAltitudeCorrection(
                23.0, 10.0, altitudeRange, 0.7, 0.5), EPSILON);
    }

    @Test
    void approachKeepsFacingTargetAfterTranslationStops() {
        Vector3d stopped = BodyMotionTameworkFlyingOrbit.resolveApproachTranslation(
                6.0, 0.0, 0.0, 0.0, 6.0, 14.0, 0.72, new Vector3d());
        Vector3d facing = BodyMotionTameworkFlyingOrbit.resolveTargetDirection(
                6.0, 0.0, 0.0, 0.0, new Vector3d());

        assertEquals(0.0, stopped.lengthSquared(), EPSILON);
        assertTrue(facing.x < 0.0);
        assertTrue(facing.lengthSquared() > 0.0);
    }

    @Test
    void waypointTranslationFliesInThreeDimensionsAtConfiguredSpeed() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveWaypointTranslation(
                0.0, 10.0, 0.0, 12.0, 15.0, 0.0, 2.0, 0.65, new Vector3d());

        assertTrue(result.x > 0.0);
        assertTrue(result.y > 0.0);
        assertEquals(0.65, result.length(), EPSILON);
    }

    @Test
    void waypointTranslationStopsInsideArrivalDistance() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveWaypointTranslation(
                0.0, 10.0, 0.0, 1.0, 11.0, 0.0, 2.0, 0.65, new Vector3d());

        assertEquals(0.0, result.lengthSquared(), EPSILON);
    }

    @Test
    void targetBandAllowsLoosePositionUntilTargetMovesBeyondBoundary() {
        double[] radiusRange = { 10.0, 22.0 };
        double[] altitudeRange = { 8.0, 12.0 };

        assertTrue(BodyMotionTameworkFlyingOrbit.isWithinTargetBand(
                16.0, 10.0, 0.0, 0.0, 0.0, 0.0, radiusRange, altitudeRange));
        assertFalse(BodyMotionTameworkFlyingOrbit.isWithinTargetBand(
                16.0, 10.0, 0.0, 8.0, 0.0, 0.0, radiusRange, altitudeRange));
        assertFalse(BodyMotionTameworkFlyingOrbit.isWithinTargetBand(
                16.0, 14.0, 0.0, 0.0, 0.0, 0.0, radiusRange, altitudeRange));
    }

    @Test
    void wanderSafetyEnvelopeAllowsTargetMovementWithoutImmediateRetargeting() {
        double[] radiusRange = { 18.0, 36.0 };
        double[] altitudeRange = { 8.0, 16.0 };

        assertFalse(BodyMotionTameworkFlyingOrbit.isOutsideTargetEnvelope(
                24.0, 12.0, 0.0, 8.0, 0.0, 0.0, radiusRange, altitudeRange));
        assertTrue(BodyMotionTameworkFlyingOrbit.isOutsideTargetEnvelope(
                80.0, 12.0, 0.0, 0.0, 0.0, 0.0, radiusRange, altitudeRange));
    }

    @Test
    void wanderReturnStartsOnlyBeyondMaximumRadius() {
        assertFalse(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                false, true, 18.0 * 18.0, 12.0, 18.0));
        assertTrue(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                false, true, 18.01 * 18.01, 12.0, 18.0));
    }

    @Test
    void wanderReturnStaysLatchedUntilMinimumRadius() {
        assertTrue(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                true, true, 15.0 * 15.0, 12.0, 18.0));
        assertFalse(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                true, true, 12.0 * 12.0, 12.0, 18.0));
        assertFalse(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                true, true, 11.99 * 11.99, 12.0, 18.0));
    }

    @Test
    void equalWanderRadiiUseOneStableBoundary() {
        assertFalse(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                false, true, 12.0 * 12.0, 12.0, 12.0));
        assertTrue(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                false, true, 12.01 * 12.01, 12.0, 12.0));
        assertFalse(BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                true, true, 12.0 * 12.0, 12.0, 12.0));
    }

    @Test
    void unavailableSteeringPreservesReturnLatchUntilMinimumRadius() {
        boolean returning = BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                false, true, 20.0 * 20.0, 12.0, 18.0);
        returning = BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                returning, false, 0.0, 12.0, 18.0);
        returning = BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                returning, true, 15.0 * 15.0, 12.0, 18.0);

        assertTrue(returning);

        returning = BodyMotionTameworkFlyingOrbit.updateWanderReturnState(
                returning, true, 12.0 * 12.0, 12.0, 18.0);
        assertFalse(returning);
    }

    @Test
    void returningWanderIgnoresStaleWaypointAndSeeksLiveTarget() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveWanderTranslation(
                true,
                new Vector3d(24.0, 5.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(30.0, 5.0, 0.0),
                12.0,
                3.0,
                0.5,
                new Vector3d());

        assertEquals(-0.5, result.x, EPSILON);
        assertEquals(0.0, result.y, EPSILON);
        assertEquals(0.0, result.z, EPSILON);
    }

    @Test
    void ordinaryWanderStillFliesTowardSelectedWaypoint() {
        Vector3d result = BodyMotionTameworkFlyingOrbit.resolveWanderTranslation(
                false,
                new Vector3d(24.0, 5.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(30.0, 5.0, 0.0),
                12.0,
                3.0,
                0.5,
                new Vector3d());

        assertEquals(0.5, result.x, EPSILON);
        assertEquals(0.0, result.y, EPSILON);
        assertEquals(0.0, result.z, EPSILON);
    }

    @Test
    void returningWanderUsesTargetRelativeAltitudeCorrection() {
        assertTrue(BodyMotionTameworkFlyingOrbit.shouldApplyTargetRelativeAltitude(true, false, true));
        assertFalse(BodyMotionTameworkFlyingOrbit.shouldApplyTargetRelativeAltitude(true, false, false));
        assertFalse(BodyMotionTameworkFlyingOrbit.shouldApplyTargetRelativeAltitude(true, true, true));
    }

    @Test
    void passThroughDestinationContinuesBeyondCapturedTarget() {
        Vector3d destination = BodyMotionTameworkFlyingOrbit.resolvePassThroughDestination(
                -8.0, 0.0, 0.0, 10.0, 0.0, 18.0, 3.0, new Vector3d());

        assertEquals(18.0, destination.x, EPSILON);
        assertEquals(13.0, destination.y, EPSILON);
        assertEquals(0.0, destination.z, EPSILON);
    }

    @Test
    void passThroughDestinationHasStableFallbackWhenDirectlyAboveTarget() {
        Vector3d destination = BodyMotionTameworkFlyingOrbit.resolvePassThroughDestination(
                4.0, 6.0, 4.0, 10.0, 6.0, 12.0, 2.0, new Vector3d());

        assertEquals(4.0, destination.x, EPSILON);
        assertEquals(12.0, destination.y, EPSILON);
        assertEquals(18.0, destination.z, EPSILON);
    }

    @Test
    void passThroughPreflightSelectsClearAlternateLane() {
        Vector3d[] candidates = {
                new Vector3d(18.0, 13.0, 0.0),
                new Vector3d(15.6, 13.0, -9.0),
                new Vector3d(15.6, 13.0, 9.0)
        };

        Vector3d selected = BodyMotionTameworkFlyingOrbit.selectWaypointDestination(
                candidates, new double[] { 0.2, 1.0, 0.7 }, 3, new Vector3d());

        assertEquals(candidates[1], selected);
    }

    @Test
    void passThroughCandidatesPreserveConfiguredDistanceAcrossAlternateLanes() {
        Vector3d[] candidates = { new Vector3d(), new Vector3d(), new Vector3d() };

        BodyMotionTameworkFlyingOrbit.resolvePassThroughCandidates(
                -8.0, 0.0, 0.0, 10.0, 0.0, 18.0, 3.0, candidates);

        for (Vector3d candidate : candidates) {
            assertEquals(18.0, Math.hypot(candidate.x, candidate.z), EPSILON);
            assertEquals(13.0, candidate.y, EPSILON);
        }
        assertTrue(candidates[1].z < 0.0);
        assertTrue(candidates[2].z > 0.0);
    }

    @Test
    void preflightTickDefersWaypointMovementForAFullLiveAvoidanceBudget() {
        Vector3d translation = new Vector3d(0.8, 0.2, -0.4);

        BodyMotionTameworkFlyingOrbit.deferPreflightedWaypointMovement(true, translation);

        assertEquals(0.0, translation.lengthSquared(), EPSILON);
    }

    @Test
    void wanderPreflightGuaranteesOneMovementTickBeforeAnotherRetarget() {
        BodyMotionTameworkFlyingOrbit.WaypointPreflightGate gate =
                new BodyMotionTameworkFlyingOrbit.WaypointPreflightGate();

        gate.markPreflighted();

        assertTrue(gate.consumeMovementPending());
        assertFalse(gate.consumeMovementPending());
    }

    @Test
    void anyMountedComponentDisablesAvoidance() {
        assertTrue(BodyMotionTameworkFlyingOrbit.isRiderControlled(
                new TameworkRideMountComponent(), null));
        assertTrue(BodyMotionTameworkFlyingOrbit.isRiderControlled(
                null, new NPCMountComponent()));
        assertFalse(BodyMotionTameworkFlyingOrbit.isRiderControlled(null, null));
    }

    @Test
    void avoidanceGateRequiresEnabledAutonomousTranslation() {
        assertTrue(BodyMotionTameworkFlyingOrbit.shouldApplyObstacleAvoidance(
                true, null, null, new Vector3d(1.0, 0.0, 0.0)));
        assertFalse(BodyMotionTameworkFlyingOrbit.shouldApplyObstacleAvoidance(
                false, null, null, new Vector3d(1.0, 0.0, 0.0)));
        assertFalse(BodyMotionTameworkFlyingOrbit.shouldApplyObstacleAvoidance(
                true, new TameworkRideMountComponent(), null, new Vector3d(1.0, 0.0, 0.0)));
        assertFalse(BodyMotionTameworkFlyingOrbit.shouldApplyObstacleAvoidance(
                true, null, null, new Vector3d()));
    }

    @Test
    void wanderCandidateSelectsFirstClearCorridor() {
        assertEquals(1, BodyMotionTameworkFlyingOrbit.selectWaypointCandidate(
                new double[] { 0.0, 1.0, 0.0 }, 2));
    }

    @Test
    void wanderCandidateFallsBackToGreatestPartialClearance() {
        assertEquals(1, BodyMotionTameworkFlyingOrbit.selectWaypointCandidate(
                new double[] { 0.4, 0.7, 0.6 }, 3));
    }
}
