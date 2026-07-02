package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MountedGlidePlayerVelocitySystemArchitectureTest {
    @Test
    void mountedGlideAppliesVelocityToRiderNotNpcMotionController() throws IOException {
        Path path = Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        );
        assertTrue(Files.exists(path), "MountedGlidePlayerVelocitySystem.java should exist");
        String source = Files.readString(path);

        assertTrue(source.contains("NPCMountComponent"));
        assertTrue(source.contains("MovementStatesComponent"));
        assertTrue(source.contains("Velocity.getComponentType()"));
        assertTrue(source.contains("Velocity.addInstruction"));
        assertTrue(source.contains("ChangeVelocityType.Set"));
        assertTrue(source.contains("MountedGlidePhysics.update"));
    }

    @Test
    void mountedGlideVelocitySystemUsesBaseGameVelocityOrderingMarker() throws IOException {
        Path path = Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        );
        String source = Files.readString(path);

        assertTrue(source.contains(
                "import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;"
        ));
        assertTrue(source.contains("implements IVelocityModifyingSystem"));
        assertTrue(source.contains("Order.AFTER, MountedGlideInputCaptureSystem.class"));
        assertTrue(source.contains("velocity.addInstruction"));
    }

    @Test
    void mountedGlideReturnToGroundUsesMountGroundStateInsteadOfAnchoredRider() throws IOException {
        Path path = Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        );
        String source = Files.readString(path);

        assertTrue(source.contains("shouldReturnToGroundMode(mount, mountOnGround)"));
        assertFalse(source.contains("shouldReturnToGroundMode(mount, riderOnGround)"));
    }

    @Test
    void groundModeDoesNotApplyGlideVelocityUntilJumpLaunchesFlight() {
        TameworkMountedGlideComponent mount = new TameworkMountedGlideComponent();

        mount.setJumpHeld(false);
        assertFalse(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, true));

        mount.setJumpHeld(true);
        assertTrue(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, true));

        mount.setFlightActive(true);
        assertFalse(MountedGlidePlayerVelocitySystem.shouldReturnToGroundMode(mount, true));

        mount.setJumpHeld(false);
        assertTrue(MountedGlidePlayerVelocitySystem.shouldReturnToGroundMode(mount, true));
    }

    @Test
    void midAirMountingStartsFlightWithoutWaitingForJump() {
        TameworkMountedGlideComponent mount = new TameworkMountedGlideComponent();

        mount.setJumpHeld(false);

        assertTrue(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, false));
    }

    @Test
    void groundedMountDoesNotStartFlightJustBecauseAnchoredRiderIsAirborne() {
        TameworkMountedGlideComponent mount = new TameworkMountedGlideComponent();

        mount.setJumpHeld(false);

        assertFalse(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, true));
    }

    @Test
    void missingMountGroundStateDoesNotStartFlightFromAnchoredRiderState() {
        TameworkMountedGlideComponent mount = new TameworkMountedGlideComponent();

        mount.setJumpHeld(false);

        boolean mountOnGround = MountedGlidePlayerVelocitySystem.resolveMountOnGroundForActivation(null);

        assertTrue(mountOnGround);
        assertFalse(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, mountOnGround));
    }
}
