package com.alechilles.alecstamework.npc.components;

import com.hypixel.hytale.protocol.MovementStates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests mount-side Tamework ride component state and input snapshot behavior. */
class TameworkRideMountComponentTest {

    @Test
    void sanitizesBlankStateFieldsAndKeepsRideDefaults() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();

        component.setRiderUuid(null);
        component.setPreviousState(" ");
        component.setPreviousSubState(null);
        component.setPreviousMotionController("\t");
        component.setGroundController(" ");
        component.setFlightController(null);
        component.setRideState("\n");

        assertEquals("", component.getRiderUuid());
        assertEquals("", component.getPreviousState());
        assertEquals("", component.getPreviousSubState());
        assertEquals("", component.getPreviousMotionController());
        assertEquals("Walk", component.getGroundController());
        assertEquals("TameworkFly", component.getFlightController());
        assertEquals("Ridden", component.getRideState());
    }

    @Test
    void captureMethodsPopulateMountedFlightInputSnapshot() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        MovementStates states = new MovementStates();
        states.jumping = true;
        states.crouching = true;
        states.flying = false;
        states.sprinting = true;

        component.captureWishMovement(1.25, -0.5, 0.75);
        component.captureBodyRotation(10.0f, 20.0f, 30.0f);
        component.captureHeadRotation(40.0f, 50.0f, 60.0f);
        component.captureRiderMovementStates(states);

        assertTrue(component.hasWishMovement());
        assertEquals(1.25, component.getWishX(), 0.0001);
        assertEquals(-0.5, component.getWishY(), 0.0001);
        assertEquals(0.75, component.getWishZ(), 0.0001);
        assertTrue(component.hasBodyRotation());
        assertEquals(10.0f, component.getBodyYaw(), 0.0001f);
        assertEquals(20.0f, component.getBodyPitch(), 0.0001f);
        assertEquals(30.0f, component.getBodyRoll(), 0.0001f);
        assertTrue(component.hasHeadRotation());
        assertEquals(40.0f, component.getHeadYaw(), 0.0001f);
        assertEquals(50.0f, component.getHeadPitch(), 0.0001f);
        assertEquals(60.0f, component.getHeadRoll(), 0.0001f);
        assertTrue(component.isRiderJumping());
        assertTrue(component.isRiderCrouching());
        assertFalse(component.isRiderFlying());
        assertTrue(component.isRiderSprinting());
    }

    @Test
    void groundedCrouchTickHelpersClampIncrementAndReset() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();

        component.setGroundedCrouchTicks(-4);
        assertEquals(0, component.getGroundedCrouchTicks());

        component.incrementGroundedCrouchTicks();
        component.incrementGroundedCrouchTicks();
        assertEquals(2, component.getGroundedCrouchTicks());

        component.resetGroundedCrouchTicks();
        assertEquals(0, component.getGroundedCrouchTicks());
    }

    @Test
    void runningStateAlsoCountsAsRiderSprintIntent() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        MovementStates states = new MovementStates();
        states.running = true;

        component.captureRiderMovementStates(states);

        assertTrue(component.isRiderSprinting());
    }

    @Test
    void clearWishMovementOnlyClearsTranslationIntent() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        component.captureWishMovement(1.0, 2.0, 3.0, true);
        component.captureHeadRotation(4.0f, 5.0f, 6.0f);

        component.clearWishMovement();

        assertFalse(component.hasWishMovement());
        assertEquals(0.0, component.getWishX(), 0.0);
        assertEquals(0.0, component.getWishY(), 0.0);
        assertEquals(0.0, component.getWishZ(), 0.0);
        assertFalse(component.isRiderBackwardBrakeInput());
        assertTrue(component.hasHeadRotation());
        assertEquals(4.0f, component.getHeadYaw(), 0.0001f);
    }

    @Test
    void explicitBackwardWishInputIsTrackedSeparatelyFromProjectedMovement() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();

        component.captureWishMovement(0.0, 0.0, -1.0);
        assertFalse(component.isRiderBackwardBrakeInput());

        component.captureWishMovement(0.0, 0.0, -1.0, true);
        assertTrue(component.isRiderBackwardBrakeInput());

        component.captureWishMovement(0.0, 0.0, -1.0, false);
        assertFalse(component.isRiderBackwardBrakeInput());
    }

    @Test
    void clearInputSnapshotResetsCapturedInputOnly() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        component.setLastInputAtMs(123456789L);
        component.setGroundedCrouchTicks(3);
        component.setGroundedCrouchDismountArmed(true);
        component.captureWishMovement(1.0, 2.0, 3.0);
        component.captureBodyRotation(4.0f, 5.0f, 6.0f);
        component.captureHeadRotation(7.0f, 8.0f, 9.0f);
        component.captureAuthoritativePose(10.0, 11.0, 12.0, 13.0f, 14.0f, 15.0f);
        MovementStates states = new MovementStates();
        states.jumping = true;
        states.crouching = true;
        states.flying = true;
        states.sprinting = true;
        component.captureRiderMovementStates(states);

        component.clearInputSnapshot();

        assertEquals(0.0, component.getWishX(), 0.0);
        assertEquals(0.0f, component.getBodyYaw(), 0.0f);
        assertEquals(0.0f, component.getHeadYaw(), 0.0f);
        assertFalse(component.hasWishMovement());
        assertFalse(component.hasBodyRotation());
        assertFalse(component.hasHeadRotation());
        assertFalse(component.isRiderJumping());
        assertFalse(component.isRiderCrouching());
        assertFalse(component.isRiderFlying());
        assertFalse(component.isRiderSprinting());
        assertEquals(0, component.getGroundedCrouchTicks());
        assertFalse(component.isGroundedCrouchDismountArmed());
        assertEquals(0L, component.getLastInputAtMs());
        assertFalse(component.hasAuthoritativePose());
    }

    @Test
    void clearControlInputSnapshotKeepsRiderStateAndDismountProgress() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        component.setLastInputAtMs(123456789L);
        component.setGroundedCrouchTicks(3);
        component.setGroundedCrouchDismountArmed(true);
        component.setDismountRequested(true);
        component.captureWishMovement(1.0, 2.0, 3.0);
        component.captureBodyRotation(4.0f, 5.0f, 6.0f);
        component.captureHeadRotation(7.0f, 8.0f, 9.0f);
        component.captureAuthoritativePose(10.0, 11.0, 12.0, 13.0f, 14.0f, 15.0f);
        MovementStates states = new MovementStates();
        states.jumping = true;
        states.crouching = true;
        states.flying = true;
        states.sprinting = true;
        component.captureRiderMovementStates(states);

        component.clearControlInputSnapshot();

        assertEquals(0.0, component.getWishX(), 0.0);
        assertEquals(0.0f, component.getBodyYaw(), 0.0f);
        assertEquals(0.0f, component.getHeadYaw(), 0.0f);
        assertFalse(component.hasWishMovement());
        assertFalse(component.hasBodyRotation());
        assertFalse(component.hasHeadRotation());
        assertTrue(component.isRiderJumping());
        assertTrue(component.isRiderCrouching());
        assertTrue(component.isRiderFlying());
        assertTrue(component.isRiderSprinting());
        assertEquals(3, component.getGroundedCrouchTicks());
        assertTrue(component.isGroundedCrouchDismountArmed());
        assertTrue(component.isDismountRequested());
        assertEquals(123456789L, component.getLastInputAtMs());
        assertTrue(component.hasAuthoritativePose());
        assertEquals(10.0, component.getAuthoritativeX(), 0.0001);
    }

    @Test
    void capturesAndClearsAuthoritativePose() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();

        component.captureAuthoritativePose(1.0, 2.0, 3.0, 4.0f, 5.0f, 6.0f);

        assertTrue(component.hasAuthoritativePose());
        assertEquals(1.0, component.getAuthoritativeX(), 0.0001);
        assertEquals(2.0, component.getAuthoritativeY(), 0.0001);
        assertEquals(3.0, component.getAuthoritativeZ(), 0.0001);
        assertEquals(4.0f, component.getAuthoritativeYaw(), 0.0001f);
        assertEquals(5.0f, component.getAuthoritativePitch(), 0.0001f);
        assertEquals(6.0f, component.getAuthoritativeRoll(), 0.0001f);

        component.clearAuthoritativePose();

        assertFalse(component.hasAuthoritativePose());
        assertEquals(0.0, component.getAuthoritativeX(), 0.0);
        assertEquals(0.0f, component.getAuthoritativeYaw(), 0.0f);
    }

    @Test
    void clonePreservesRideStateAndInputSnapshot() {
        TameworkRideMountComponent component = new TameworkRideMountComponent();
        component.setRiderUuid("rider-uuid");
        component.setPreviousState("Fly");
        component.setPreviousSubState("Glide");
        component.setPreviousMotionController("OldController");
        component.setGroundController("CustomGround");
        component.setFlightController("CustomFlight");
        component.setRideState("CustomRide");
        component.setMountStartMs(111L);
        component.setLastInputAtMs(222L);
        component.setGroundedCrouchTicks(5);
        component.setGroundedCrouchDismountArmed(true);
        component.setDismountRequested(true);
        MovementStates states = new MovementStates();
        states.jumping = true;
        states.crouching = false;
        states.flying = true;
        states.sprinting = true;
        component.captureWishMovement(1.25, -0.5, 0.75);
        component.captureBodyRotation(10.0f, 20.0f, 30.0f);
        component.captureHeadRotation(40.0f, 50.0f, 60.0f);
        component.captureRiderMovementStates(states);
        component.captureAuthoritativePose(70.0, 71.0, 72.0, 73.0f, 74.0f, 75.0f);

        TameworkRideMountComponent cloned = component.clone();

        assertEquals("rider-uuid", cloned.getRiderUuid());
        assertEquals("Fly", cloned.getPreviousState());
        assertEquals("Glide", cloned.getPreviousSubState());
        assertEquals("OldController", cloned.getPreviousMotionController());
        assertEquals("CustomGround", cloned.getGroundController());
        assertEquals("CustomFlight", cloned.getFlightController());
        assertEquals("CustomRide", cloned.getRideState());
        assertEquals(111L, cloned.getMountStartMs());
        assertEquals(222L, cloned.getLastInputAtMs());
        assertEquals(5, cloned.getGroundedCrouchTicks());
        assertTrue(cloned.isGroundedCrouchDismountArmed());
        assertTrue(cloned.isDismountRequested());
        assertEquals(1.25, cloned.getWishX(), 0.0001);
        assertEquals(10.0f, cloned.getBodyYaw(), 0.0001f);
        assertEquals(40.0f, cloned.getHeadYaw(), 0.0001f);
        assertTrue(cloned.hasWishMovement());
        assertTrue(cloned.hasBodyRotation());
        assertTrue(cloned.hasHeadRotation());
        assertTrue(cloned.isRiderJumping());
        assertFalse(cloned.isRiderCrouching());
        assertTrue(cloned.isRiderFlying());
        assertTrue(cloned.isRiderSprinting());
        assertTrue(cloned.hasAuthoritativePose());
        assertEquals(70.0, cloned.getAuthoritativeX(), 0.0001);
        assertEquals(71.0, cloned.getAuthoritativeY(), 0.0001);
        assertEquals(72.0, cloned.getAuthoritativeZ(), 0.0001);
        assertEquals(73.0f, cloned.getAuthoritativeYaw(), 0.0001f);
        assertEquals(74.0f, cloned.getAuthoritativePitch(), 0.0001f);
        assertEquals(75.0f, cloned.getAuthoritativeRoll(), 0.0001f);
    }
}
