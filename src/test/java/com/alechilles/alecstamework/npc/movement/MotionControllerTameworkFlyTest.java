package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.MovementStates;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionControllerTameworkFlyTest {

    @Test
    void builderIdUsesGenericTameworkFlyControllerName() {
        assertEquals("TameworkFly", BuilderMotionControllerTameworkFly.BUILDER_ID);
    }

    @Test
    void noRideMountUsesHorizontalSpeedForFlightAnimationState() {
        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(null, 0.0));
        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(null, 0.049));
        assertFalse(TameworkFlyAnimationState.resolveHorizontalIdle(null, 0.05));
        assertFalse(TameworkFlyAnimationState.resolveFast(null, false));
    }

    @Test
    void rideMountUsesRiderInputAndSprintForFlightAnimationState() {
        TameworkRideMountComponent ride = new TameworkRideMountComponent();

        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 10.0));

        ride.captureWishMovement(1.0, 0.0, 0.0);
        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.0));
        assertFalse(TameworkFlyAnimationState.resolveFast(ride, true));

        ride.captureWishMovement(-0.99, 0.0, 0.03);
        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.0));

        ride.captureWishMovement(0.0, 0.0, 1.0);
        assertFalse(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.0));
        assertFalse(TameworkFlyAnimationState.resolveFast(ride, false));

        MovementStates states = new MovementStates();
        states.running = true;
        ride.captureRiderMovementStates(states);

        assertTrue(TameworkFlyAnimationState.resolveFast(ride, false));
        assertFalse(TameworkFlyAnimationState.resolveFast(ride, true));
    }

    @Test
    void verticalDominantFlightStaysLevelAndUsesIdleAnimation() {
        TameworkRideMountComponent ride = new TameworkRideMountComponent();
        ride.captureWishMovement(0.0, 0.0, 1.0);

        assertTrue(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.5, true));
        assertFalse(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.5, false));
        assertEquals(0.0f, TameworkFlyVisualState.resolveVisualPitch(
                (float) Math.toRadians(80.0),
                new Vector3d(0.1, 5.0, 0.1)
        ));
        assertEquals((float) Math.toRadians(30.0), TameworkFlyVisualState.resolveVisualPitch(
                (float) Math.toRadians(30.0),
                new Vector3d(5.0, 0.5, 0.0)
        ));
        assertEquals((float) Math.toRadians(40.0), TameworkFlyVisualState.limitPitch(
                (float) Math.toRadians(80.0)
        ));
        assertEquals((float) Math.toRadians(-40.0), TameworkFlyVisualState.limitPitch(
                (float) Math.toRadians(-80.0)
        ));
        assertEquals((float) Math.toRadians(20.0), TameworkFlyVisualState.approachVisualAngle(
                0.0f,
                (float) Math.toRadians(40.0),
                0.5
        ), 0.0001f);
    }

    @Test
    void verticalDominantClientVelocityMapsToVerticalRideIntent() {
        assertTrue(TameworkRideVelocityIntent.isVerticalDominant(31.0, 226.0, -39.0));
        assertEquals(1.0, TameworkRideVelocityIntent.verticalInput(31.0, 226.0, -39.0));
        assertEquals(-1.0, TameworkRideVelocityIntent.verticalInput(5.0, -80.0, 3.0));
        assertFalse(TameworkRideVelocityIntent.isVerticalDominant(84.0, -4.0, -82.0));
        assertFalse(TameworkRideVelocityIntent.hasUsableHorizontalIntent(-0.0003, -0.0023));
        assertTrue(TameworkRideVelocityIntent.hasUsableHorizontalIntent(-13.0, -4.7));
    }

    @Test
    void backwardInputOnlyBrakesToHover() {
        RiddenBackwardBrake.State state = new RiddenBackwardBrake.State();
        Vector3d targetVelocity = new Vector3d(0.0, 0.0, -5.0);
        Vector3d currentVelocity = new Vector3d(0.0, 0.0, 2.0);

        boolean braking = RiddenBackwardBrake.apply(
                targetVelocity,
                currentVelocity,
                state,
                true,
                0.05
        );

        assertTrue(braking);
        assertEquals(0.0, targetVelocity.x);
        assertEquals(0.0, targetVelocity.z);

        currentVelocity.assign(0.0);
        targetVelocity.assign(0.0, 0.0, -5.0);
        braking = RiddenBackwardBrake.apply(
                targetVelocity,
                currentVelocity,
                state,
                true,
                0.05
        );

        assertTrue(braking);
        assertEquals(0.0, targetVelocity.z);

        targetVelocity.assign(0.0, 0.0, -5.0);
        braking = RiddenBackwardBrake.apply(
                targetVelocity,
                currentVelocity,
                state,
                true,
                0.25
        );

        assertTrue(braking);
        assertEquals(0.0, targetVelocity.z);
    }

    @Test
    void releasingBackwardInputClearsTheAirbrake() {
        RiddenBackwardBrake.State state = new RiddenBackwardBrake.State();
        Vector3d stopped = new Vector3d(0.0, 0.0, 0.0);
        Vector3d targetVelocity = new Vector3d(0.0, 0.0, -5.0);

        RiddenBackwardBrake.apply(targetVelocity, stopped, state, true, 0.3);
        assertEquals(0.0, targetVelocity.z);

        targetVelocity.assign(0.0, 0.0, 0.0);
        assertFalse(RiddenBackwardBrake.apply(targetVelocity, stopped, state, false, 0.05));

        targetVelocity.assign(0.0, 0.0, -5.0);
        assertTrue(RiddenBackwardBrake.apply(targetVelocity, stopped, state, true, 0.05));
        assertEquals(0.0, targetVelocity.z);
    }

}
