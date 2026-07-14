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
