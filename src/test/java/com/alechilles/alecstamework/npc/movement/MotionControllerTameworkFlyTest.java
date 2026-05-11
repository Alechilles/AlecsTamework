package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
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

        ride.captureWishMovement(0.0, 0.0, 1.0);
        assertFalse(TameworkFlyAnimationState.resolveHorizontalIdle(ride, 0.0));
        assertFalse(TameworkFlyAnimationState.resolveFast(ride, false));

        MovementStates states = new MovementStates();
        states.running = true;
        ride.captureRiderMovementStates(states);

        assertTrue(TameworkFlyAnimationState.resolveFast(ride, false));
        assertFalse(TameworkFlyAnimationState.resolveFast(ride, true));
    }
}
