package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Protects the spawn-physics reset that prevents invalid immediate falls. */
class CommandCompanionSpawnPhysicsResetServiceTest {
    @Test
    void clearsFallDistanceVelocityAndPendingInstructions() {
        NPCEntity npc = new NPCEntity();
        npc.setCurrentFallDistance(4_381_562.0D);
        Velocity velocity = new Velocity(
                new Vector3d(8.0D, -64.0D, 3.0D));
        velocity.setClient(4.0D, -32.0D, 1.5D);
        velocity.addInstruction(
                new Vector3d(1.0D, 2.0D, 3.0D),
                new VelocityConfig(),
                ChangeVelocityType.Add
        );

        String diagnostic = CommandCompanionSpawnPhysicsResetService
                .resetSpawnedCompanionPhysics(npc, velocity);

        assertEquals(0.0D, npc.getCurrentFallDistance());
        assertEquals(new Vector3d(), velocity.getVelocity());
        assertEquals(new Vector3d(), velocity.getClientVelocity());
        assertTrue(velocity.getInstructions().isEmpty());
        assertTrue(diagnostic.contains("fallDistanceBefore=4381562.000"));
        assertTrue(diagnostic.contains("clearedVelocityInstructions=1"));
    }
}
