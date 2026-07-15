package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
