package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

final class AvatarFlightSourceFollowServiceTest {
    @Test
    void parkedSourceFollowsRiderTransform() {
        TransformComponent rider = new TransformComponent(
                new Vector3d(48.5, 91.0, -17.25), new Rotation3f(0.2f, 1.3f, -0.1f));
        TransformComponent source = new TransformComponent(
                new Vector3d(2.0, 3.0, 4.0), new Rotation3f());

        assertTrue(new AvatarFlightSourceFollowService().sync(rider, source));

        assertEquals(rider.getPosition(), source.getPosition());
        assertEquals(rider.getRotation().yaw(), source.getRotation().yaw());
        assertEquals(rider.getRotation().pitch(), source.getRotation().pitch());
        assertEquals(rider.getRotation().roll(), source.getRotation().roll());
    }

    /** Protects parked source NPCs from Hytale's destructive Y=320 entity removal. */
    @Test
    void buildCeilingKeepsRiderAndParkedSourceInNpcHeightRange() {
        TransformComponent rider = new TransformComponent(
                new Vector3d(-214.09, 320.1873, 578.67), new Rotation3f());
        TransformComponent source = new TransformComponent(
                new Vector3d(2.0, 3.0, 4.0), new Rotation3f());

        assertTrue(new AvatarFlightSourceFollowService().sync(rider, source));

        assertEquals(319.0, rider.getPosition().y);
        assertEquals(rider.getPosition(), source.getPosition());
    }
}
