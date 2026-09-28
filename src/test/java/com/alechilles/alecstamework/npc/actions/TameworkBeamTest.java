package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Protects terrain occlusion, player-bound intersection, and bounded beam pulse cadence. */
class TameworkBeamTest {
    @Test
    void beamHitsExpandedPlayerBoundsButCannotReachBehindTerrain() {
        Vector3d origin = new Vector3d(0, 1.5, 0);
        Vector3d direction = new Vector3d(0, 0, 1);
        Vector3d min = new Vector3d(0.1, 0, 8);
        Vector3d max = new Vector3d(0.8, 2, 9);
        assertTrue(ActionTameworkBeam.intersectsSegment(origin, direction, 30, min, max, 0.2));
        assertFalse(ActionTameworkBeam.intersectsSegment(origin, direction, 7, min, max, 0.2));
        assertFalse(ActionTameworkBeam.intersectsSegment(origin, direction, 30, min, max, 0));
    }

    @Test
    void beamMissesPlayersBehindItsOriginOrOutsideItsHeight() {
        Vector3d origin = new Vector3d(0, 1.5, 0);
        Vector3d direction = new Vector3d(0, 0, 1);
        assertFalse(ActionTameworkBeam.intersectsSegment(origin, direction, 30,
                new Vector3d(-0.5, 0, -4), new Vector3d(0.5, 2, -3), 0.2));
        assertFalse(ActionTameworkBeam.intersectsSegment(origin, direction, 30,
                new Vector3d(-0.5, 3, 4), new Vector3d(0.5, 4, 5), 0.2));
    }

    @Test
    void rayCanHitFromInsideBoundsAndAlongNegativeDirection() {
        assertTrue(ActionTameworkBeam.intersectsSegment(new Vector3d(0, 1, 0),
                new Vector3d(-1, 0, 0), 10,
                new Vector3d(-0.5, 0, -0.5), new Vector3d(0.5, 2, 0.5), 0));
        assertTrue(ActionTameworkBeam.intersectsSegment(new Vector3d(0, 1, 0),
                new Vector3d(-1, 0, 0), 10,
                new Vector3d(-5, 0, -0.5), new Vector3d(-4, 2, 0.5), 0));
    }

    @Test
    void stalledTickProducesOnePulseAndDropsCatchup() {
        ActionTameworkBeam.Cadence cadence = new ActionTameworkBeam.Cadence(0.25);
        assertTrue(cadence.advance(0));
        assertFalse(cadence.advance(0.1));
        assertTrue(cadence.advance(2));
        assertFalse(cadence.advance(0));
        assertFalse(cadence.advance(0.2));
        assertTrue(cadence.advance(0.05));
        cadence.reset();
        assertTrue(cadence.advance(0));
    }
}
