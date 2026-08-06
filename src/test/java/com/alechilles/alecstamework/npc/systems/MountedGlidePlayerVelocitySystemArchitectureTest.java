package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import org.junit.jupiter.api.Test;

class MountedGlidePlayerVelocitySystemArchitectureTest {
    @Test
    void groundModeDoesNotApplyGlideVelocityUntilJumpLaunchesFlight() {
        TameworkMountedGlideComponent mount = new TameworkMountedGlideComponent();

        mount.setJumpHeld(false);
        assertFalse(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, true));

        mount.captureControls(true, false, false, 10L);
        assertTrue(MountedGlidePlayerVelocitySystem.shouldActivateFlight(mount, true));

        mount.setFlightActive(true);
        assertFalse(MountedGlidePlayerVelocitySystem.shouldReturnToGroundMode(mount, true));

        mount.consumeFlapRequest();
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
