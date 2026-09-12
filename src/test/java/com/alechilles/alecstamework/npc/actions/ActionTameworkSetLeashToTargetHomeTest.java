package com.alechilles.alecstamework.npc.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ActionTameworkSetLeashToTargetHomeTest {
    private static final double EPSILON = 1.0E-9;

    @Test
    void copiesTargetHomePointAndOrientationInsteadOfItsLiveTransform() {
        NPCEntity follower = new NPCEntity();
        NPCEntity leader = new NPCEntity();
        leader.setLeashPoint(new Vector3d(24.5, 86.0, -13.25));
        leader.setLeashHeading(1.2F);
        leader.setLeashPitch(-0.35F);

        assertTrue(ActionTameworkSetLeashToTargetHome.copyTargetHome(follower, leader));

        assertEquals(24.5, follower.getLeashPoint().x, EPSILON);
        assertEquals(86.0, follower.getLeashPoint().y, EPSILON);
        assertEquals(-13.25, follower.getLeashPoint().z, EPSILON);
        assertEquals(1.2F, follower.getLeashHeading(), EPSILON);
        assertEquals(-0.35F, follower.getLeashPitch(), EPSILON);

        leader.getLeashPoint().set(99.0, 99.0, 99.0);
        assertEquals(24.5, follower.getLeashPoint().x, EPSILON);
    }

    @Test
    void refusesMissingNpcComponents() {
        assertFalse(ActionTameworkSetLeashToTargetHome.copyTargetHome(null, new NPCEntity()));
        assertFalse(ActionTameworkSetLeashToTargetHome.copyTargetHome(new NPCEntity(), null));
    }
}
