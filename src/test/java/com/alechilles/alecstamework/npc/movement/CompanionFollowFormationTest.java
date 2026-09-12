package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CompanionFollowFormationTest {
    @Test
    void turningAndTakingASmallReverseStepLeavesTargetInPlace() {
        var formation = new CompanionFollowFormation();
        var first = formation.update(new Vector3d(), 0, 0, 4, 5, 0.05, new Vector3d());
        var afterStep = formation.update(new Vector3d(0, 0, -0.2), (float) Math.PI,
                0, 4, 5, 0.05, new Vector3d());
        for (int i = 0; i < 100; i++) {
            formation.update(new Vector3d(0, 0, -0.2), (float) Math.PI,
                    0, 4, 5, 0.05, afterStep);
        }
        assertEquals(first, afterStep, "A small reversal must not send companions around the player.");
    }

    @Test
    void sustainedTravelMovesTargetTowardPlayerWithoutRotatingItsOffset() {
        var formation = new CompanionFollowFormation();
        var initial = formation.update(new Vector3d(), 0, 0, 4, 0, 0.05, new Vector3d());
        var moved = formation.update(new Vector3d(0, 0, 12), 1, 0, 4, 0, 0.05, new Vector3d());
        assertEquals(initial.x, moved.x, 1e-9);
        assertTrue(moved.z > initial.z + 8, "Followers must catch up during sustained travel.");
        assertTrue(moved.distance(new Vector3d(0, 0, 12)) <= 6);
    }

    @Test
    void slotsSpreadAroundPlayerWithClearanceAndCallerAltitude() {
        var targets = new Vector3d[6];
        for (int slot = 0; slot < targets.length; slot++) {
            targets[slot] = new CompanionFollowFormation().update(
                    new Vector3d(), 0, slot, 4, 7, 0.05, new Vector3d());
            assertEquals(7, targets[slot].y, 1e-9);
            for (int previous = 0; previous < slot; previous++) {
                assertTrue(targets[slot].distance(targets[previous]) >= 4 - 1e-9);
            }
        }
        assertTrue(java.util.Arrays.stream(targets).anyMatch(target -> target.z > 1));
        assertTrue(java.util.Arrays.stream(targets).anyMatch(target -> target.z < -1));
    }

    @Test
    void teleportReanchorsNearThePlayerWithoutUsingFacing() {
        var formation = new CompanionFollowFormation();
        var initial = formation.update(new Vector3d(), 0, 0, 4, 1, 0.05, new Vector3d());
        var moved = formation.update(new Vector3d(100, 40, -20), (float) Math.PI,
                0, 4, 41, 0.05, new Vector3d());
        assertEquals(initial.x + 100, moved.x, 1e-9);
        assertEquals(initial.z - 20, moved.z, 1e-9);
        assertEquals(41, moved.y, 1e-9);
    }
}