package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.protocol.MovementStates;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the reported shoulder companion AI, interaction, and damage regression. */
class ShoulderRideNpcStateArchitectureTest {
    @Test
    void mountLifecycleFreezesAndRestoresTheNpcWithoutExposingIt() throws Exception {
        String action = source("items", "BondedCompanionShoulderRideActionService.java");
        String state = source("npc", "systems", "ShoulderRideNpcStateSystem.java");
        String marker = source("npc", "components", "TameworkShoulderRideComponent.java");

        assertTrue(action.contains("Frozen.getComponentType()"));
        assertTrue(action.contains("Intangible.getComponentType()"));
        assertTrue(action.contains("Invulnerable.getComponentType()"));
        assertTrue(action.contains("Interactable.getComponentType()"));
        assertFalse(action.contains("RoleChangeSystem.requestRoleChange"));
        assertFalse(action.contains("Empty_Role"));
        assertTrue(state.contains("commands.ensureComponent(npcRef, frozenType)"));
        assertTrue(state.contains("if (!marker.wasFrozen())"));
        assertTrue(state.contains("if (!marker.hasCapturedState())"));
        assertTrue(state.contains("commands.tryRemoveComponent(npcRef, markerType)"));
        assertFalse(state.contains("RoleChangeSystem.requestRoleChange"));
        assertTrue(marker.contains("\"WasInteractable\""));
        assertTrue(marker.contains("\"WasIntangible\""));
        assertTrue(marker.contains("\"WasInvulnerable\""));
        assertTrue(marker.contains("\"WasFrozen\""));
        assertTrue(marker.contains("\"StateCaptured\""));
    }

    @Test
    void followFailureLeavesTheMarkerForStateRestoration() throws Exception {
        String follow = source("npc", "systems", "ShoulderRideNpcFollowSystem.java");
        assertFalse(follow.contains("commands.tryRemoveComponent(npcRef, markerType)"));
    }

    @Test
    void mountedNpcRotationTracksThePlayer() throws Exception {
        String follow = source("npc", "systems", "ShoulderRideNpcFollowSystem.java");
        assertTrue(follow.contains("npcTransform.getRotation().setYaw"));
        assertTrue(follow.contains("npcTransform.getRotation().setPitch"));
        assertTrue(follow.contains("npcTransform.getRotation().setRoll"));
    }

    @Test
    void followUsesTheCurrentPlayerPoseAndUpdatesCrouchOffsetOnlyOnChange()
            throws Exception {
        String follow = source("npc", "systems", "ShoulderRideNpcFollowSystem.java");
        assertTrue(follow.contains("Order.AFTER"));
        assertTrue(follow.contains("PlayerSystems.ProcessPlayerInput.class"));
        assertTrue(follow.contains("states.crouching || states.forcedCrouching"));
        assertTrue(follow.contains("Math.abs(current.y() - desiredY)"));
        assertTrue(follow.contains("commands.putComponent(npcRef, mountedType"));
    }

    @Test
    void mountedNpcDropsAStaleRunningStateAndThenRemainsStable() {
        MovementStates states = new MovementStates();
        states.running = true;
        states.falling = true;

        assertTrue(ShoulderRideNpcStateSystem
                .normalizeMountedMovementStates(states));
        assertTrue(states.idle);
        assertTrue(states.horizontalIdle);
        assertFalse(states.running);
        assertFalse(states.falling);
        assertFalse(ShoulderRideNpcStateSystem
                .normalizeMountedMovementStates(states));
    }

    @Test
    void queuedToggleResolvesTheCurrentMountStateOnTheWorldThread()
            throws Exception {
        String action = source("items", "BondedCompanionShoulderRideActionService.java");
        assertTrue(action.contains("MountedComponent mounted = liveStore.getComponent"));
        assertFalse(action.contains("boolean detach"));
    }

    private static String source(String... segments) throws Exception {
        Path path = Path.of("src", "main", "java", "com", "alechilles",
                "alecstamework");
        for (String segment : segments) path = path.resolve(segment);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
