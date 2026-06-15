package com.alechilles.alecstamework.npc.movement;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyMotionTameworkNeedsResourceApproachTest {

    @Test
    void approachTranslationMovesHorizontallyTowardTarget() {
        Vector3d translation = BodyMotionTameworkNeedsResourceApproach.resolveApproachTranslation(
                new Vector3d(0.0, 64.0, 0.0),
                new Vector3d(3.0, 66.0, 4.0),
                1.0
        );

        assertTrue(translation.x > 0.0);
        assertEquals(0.0, translation.y, 0.0001);
        assertTrue(translation.z > 0.0);
        assertEquals(1.0, translation.length(), 0.0001);
    }

    @Test
    void approachTranslationStopsInsideConsumeDistance() {
        Vector3d translation = BodyMotionTameworkNeedsResourceApproach.resolveApproachTranslation(
                new Vector3d(1.0, 64.0, 1.0),
                new Vector3d(2.0, 70.0, 1.0),
                1.25
        );

        assertEquals(0.0, translation.length(), 0.0001);
    }

    @Test
    void validTargetRequiresFiniteHorizontalSeparation() {
        assertFalse(BodyMotionTameworkNeedsResourceApproach.hasUsableApproachTarget(
                new Vector3d(1.0, 64.0, 1.0),
                new Vector3d(1.0, 70.0, 1.0),
                1.0
        ));
        assertTrue(BodyMotionTameworkNeedsResourceApproach.hasUsableApproachTarget(
                new Vector3d(1.0, 64.0, 1.0),
                new Vector3d(4.0, 64.0, 1.0),
                1.0
        ));
    }
}
