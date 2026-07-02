package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.npc.movement.MountedGlidePhysicsState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkMountedGlideComponentTest {

    @Test
    void inputSnapshotPreservesHeldJumpRequestAndSignedTimestamp() {
        TameworkMountedGlideComponent component = new TameworkMountedGlideComponent(" rider-uuid ");

        component.captureMovementIntent(2.0, -2.0, -1250L);
        component.captureLookRotation(45.0f, -30.0f, 0.0f, -1250L);
        component.captureControls(true, true, false, -1250L);

        assertEquals("rider-uuid", component.getRiderUuid());
        assertEquals(-1250L, component.getLastInputAtMs());
        assertEquals(1.0, component.getForwardIntent(), 0.0001);
        assertEquals(-1.0, component.getStrafeIntent(), 0.0001);
        assertEquals(-30.0f, component.getLookPitchDegrees(), 0.0001f);
        assertTrue(component.isJumpHeld());
        assertTrue(component.isSprinting());
        assertTrue(component.shouldRequestFlap());

        component.setFlapCooldownRemainingSeconds(0.25);
        assertFalse(component.shouldRequestFlap());
    }

    @Test
    void physicsStateRoundTripsAndCloneCopiesValues() {
        TwMountedGlideConfig config = new TwMountedGlideConfig();
        TameworkMountedGlideComponent component = new TameworkMountedGlideComponent();
        component.initializePhysicsState(config);
        component.setFlightActive(true);
        component.setVerticalVelocity(3.25);
        component.setFlapCooldownRemainingSeconds(0.5);
        component.setBoostRemainingSeconds(0.2);

        MountedGlidePhysicsState state = component.toPhysicsState();

        assertEquals(config.getGlide().getBaseSpeed(), state.getGlideSpeed(), 0.0001);
        assertEquals(3.25, state.getVerticalVelocity(), 0.0001);
        assertEquals(0.5, state.getFlapCooldownRemainingSeconds(), 0.0001);
        assertEquals(0.2, state.getBoostRemainingSeconds(), 0.0001);

        state.setGlideSpeed(18.0);
        state.setVerticalVelocity(-1.5);
        component.applyPhysicsState(state);

        TameworkMountedGlideComponent clone = component.clone();

        assertNotSame(component, clone);
        assertTrue(clone.isFlightActive());
        assertEquals(18.0, clone.getGlideSpeed(), 0.0001);
        assertEquals(-1.5, clone.getVerticalVelocity(), 0.0001);
        assertEquals(0.5, clone.getFlapCooldownRemainingSeconds(), 0.0001);
        assertEquals(0.2, clone.getBoostRemainingSeconds(), 0.0001);
    }

    @Test
    void clearInputSnapshotDoesNotResetPhysicsState() {
        TameworkMountedGlideComponent component = new TameworkMountedGlideComponent();
        component.captureMovementIntent(1.0, 0.5, 25L);
        component.captureLookRotation(10.0f, 20.0f, 0.0f, 25L);
        component.captureControls(true, false, true, 25L);
        component.setGlideSpeed(14.0);
        component.setVerticalVelocity(2.0);
        component.setFlapCooldownRemainingSeconds(0.75);
        component.captureAuthoritativePose(1.0, 2.0, 3.0, 0.4f, 0.5f, 0.6f);

        component.clearInputSnapshot();

        assertFalse(component.hasMovementIntent());
        assertFalse(component.hasLookRotation());
        assertFalse(component.isJumpHeld());
        assertFalse(component.isCrouching());
        assertEquals(14.0, component.getGlideSpeed(), 0.0001);
        assertEquals(2.0, component.getVerticalVelocity(), 0.0001);
        assertEquals(0.75, component.getFlapCooldownRemainingSeconds(), 0.0001);
        assertTrue(component.hasAuthoritativePose());
        assertEquals(1.0, component.getAuthoritativeX(), 0.0001);
    }

    @Test
    void flightActiveStartsFalseAndCanResetToGroundMode() {
        TameworkMountedGlideComponent component = new TameworkMountedGlideComponent();

        assertFalse(component.isFlightActive());

        component.setFlightActive(true);
        assertTrue(component.isFlightActive());

        component.setFlightActive(false);
        assertFalse(component.isFlightActive());
    }

    @Test
    void authoritativePoseClampsInvalidValuesAndClones() {
        TameworkMountedGlideComponent component = new TameworkMountedGlideComponent();

        component.captureAuthoritativePose(
                Double.NaN,
                12.5,
                Double.POSITIVE_INFINITY,
                Float.NaN,
                0.75f,
                Float.NEGATIVE_INFINITY
        );

        assertTrue(component.hasAuthoritativePose());
        assertEquals(0.0, component.getAuthoritativeX(), 0.0001);
        assertEquals(12.5, component.getAuthoritativeY(), 0.0001);
        assertEquals(0.0, component.getAuthoritativeZ(), 0.0001);
        assertEquals(0.0f, component.getAuthoritativeYaw(), 0.0001f);
        assertEquals(0.75f, component.getAuthoritativePitch(), 0.0001f);
        assertEquals(0.0f, component.getAuthoritativeRoll(), 0.0001f);

        TameworkMountedGlideComponent clone = component.clone();

        assertTrue(clone.hasAuthoritativePose());
        assertEquals(12.5, clone.getAuthoritativeY(), 0.0001);

        component.clearAuthoritativePose();

        assertFalse(component.hasAuthoritativePose());
        assertEquals(0.0, component.getAuthoritativeY(), 0.0001);
    }

    @Test
    void riderMarkerSanitizesMountUuidAndClones() {
        TameworkMountedGlideRiderComponent rider = new TameworkMountedGlideRiderComponent(" mount-uuid ");

        assertEquals("mount-uuid", rider.getMountUuid());

        rider.setMountUuid("  ");
        assertEquals("", rider.getMountUuid());

        rider.setMountUuid("other-mount");
        TameworkMountedGlideRiderComponent clone = rider.clone();

        assertNotSame(rider, clone);
        assertEquals("other-mount", clone.getMountUuid());
    }
}
