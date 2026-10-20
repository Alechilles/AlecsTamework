package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class BodyMotionTameworkLeapTest {
    @Test
    void landsAtCapturedPositionAcrossDistancesElevationsAndFrameLengths() {
        for (Vector3d destination : new Vector3d[] {
                new Vector3d(1, 0, 2), new Vector3d(20, 5, -12), new Vector3d(-8, -3, 6)}) {
            for (double dt : new double[] {1.0 / 60, 1.0 / 30, 0.37}) {
                var arc = new BodyMotionTameworkLeap.Arc(1.2, 4);
                Vector3d captured = new Vector3d(destination);
                Vector3d liveTarget = new Vector3d(destination);
                assertTrue(arc.start(new Vector3d(0, 0, 0), liveTarget));
                liveTarget.add(10, 0, 10);
                Vector3d position = new Vector3d();
                double highest = 0;
                while (!arc.finished()) {
                    arc.advance(dt, position);
                    highest = Math.max(highest, position.y);
                }
                assertEquals(captured.x, position.x, 1e-9);
                assertEquals(captured.y, position.y, 1e-9);
                assertEquals(captured.z, position.z, 1e-9);
                assertTrue(highest > Math.max(0, captured.y), "The boss must visibly arc above both endpoints");
            }
        }
    }

    @Test
    void stopDistanceLandsShortOfTheTargetAndNeverBehindTakeoff() {
        var arc = new BodyMotionTameworkLeap.Arc(1, 1, 1.5);
        arc.start(new Vector3d(0, 0, 0), new Vector3d(3, 2, 4));
        Vector3d position = arc.advance(1, new Vector3d());
        assertEquals(2.1, position.x, 1e-9);
        assertEquals(2, position.y, 1e-9);
        assertEquals(2.8, position.z, 1e-9);

        arc.start(new Vector3d(5, 0, 5), new Vector3d(5.6, 0, 5.8));
        arc.advance(1, position);
        assertEquals(5, position.x, 1e-9);
        assertEquals(5, position.z, 1e-9);
    }

    @Test
    void interruptedLeapCanStartAgainWithoutOldTargetOrElapsedTime() {
        var arc = new BodyMotionTameworkLeap.Arc(1.2, 4);
        arc.start(new Vector3d(), new Vector3d(12, 0, 0));
        arc.advance(0.3, new Vector3d());
        arc.stop();
        assertTrue(arc.finished());
        assertTrue(arc.start(new Vector3d(4, 0, 3), new Vector3d(-5, 2, 6)));
        Vector3d position = arc.advance(0.6, new Vector3d());
        assertEquals(-0.5, position.x, 1e-9);
        assertEquals(5, position.y, 1e-9);
        assertEquals(4.5, position.z, 1e-9);
        assertFalse(arc.finished());
    }
}
