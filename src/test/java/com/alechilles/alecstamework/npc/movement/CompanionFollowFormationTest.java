package com.alechilles.alecstamework.npc.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class CompanionFollowFormationTest {
    @Test
    void playerApproachingGroupDoesNotMoveOrRotateFormation() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), 4, 4, 40);
        var before = group.target(new Vector3d(2, 0, 0), 0, new Vector3d());
        var after = group.target(new Vector3d(2.2, 0, 0), 0, new Vector3d());
        assertEquals(before, after);
        assertTrue(after.x > 5, "The group stays beside its own center, not around the player.");
    }

    @Test
    void travellingAwayTranslatesWholeFormationWithoutChangingOffsets() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), 4, 4, 40);
        var a = group.target(new Vector3d(), 0, new Vector3d());
        var b = group.target(new Vector3d(), 3, new Vector3d());
        var movedA = group.target(new Vector3d(-10, 0, 0), 0, new Vector3d());
        var movedB = group.target(new Vector3d(-10, 0, 0), 3, new Vector3d());
        assertTrue(movedA.x < a.x - 5);
        assertEquals(new Vector3d(b).sub(a), new Vector3d(movedB).sub(movedA));
    }

    @Test
    void membershipRefreshDoesNotDragRestingGroupToPlayer() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(10, 0, 0), 4, 4, 40);
        var before = group.target(new Vector3d(2, 0, 0), 0, new Vector3d());
        group.configure(new Vector3d(8, 0, 0), 4, 4, 40);
        assertEquals(before, group.target(new Vector3d(2, 0, 0), 0, new Vector3d()));
    }

    @Test
    void compactSlotsKeepClearanceAndStayWithinRecoveryEnvelope() {
        var group = new CompanionFollowFormation();
        group.configure(new Vector3d(30, 0, 0), 6, 4, 40);
        var targets = new Vector3d[6];
        for (int i=0; i<6; i++) {
            targets[i] = group.target(new Vector3d(), i, new Vector3d());
            assertTrue(targets[i].length() < 40);
            for (int j=0; j<i; j++) assertTrue(targets[i].distance(targets[j]) >= 4 - 1e-9);
        }
    }
}