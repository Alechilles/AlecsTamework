package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.hypixel.hytale.math.vector.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyMotionTameworkRideTest {

    @Test
    void riderLookYawOverridesMountedBodyYawForSteering() {
        TameworkRideMountComponent ride = new TameworkRideMountComponent();
        ride.captureBodyRotation(0.25f, 0.0f, 0.0f);
        ride.captureHeadRotation(1.5f, -0.4f, 0.0f);

        assertEquals(1.5f, BodyMotionTameworkRide.resolveYawFromSnapshot(ride, 0.0f), 0.0001f);
        assertEquals(-0.4f, BodyMotionTameworkRide.resolveFlightPitchFromSnapshot(ride), 0.0001f);
        assertEquals(0.4f, BodyMotionTameworkRide.resolveControlPitchFromSnapshot(ride), 0.0001f);
    }

    @Test
    void flightTranslationUsesLookPitchForForwardMovement() {
        TameworkRideMountComponent ride = new TameworkRideMountComponent();
        ride.captureHeadRotation(0.0f, -0.5f, 0.0f);
        ride.captureWishMovement(0.0, 0.0, 1.0);

        Vector3d translation = BodyMotionTameworkRide.resolveTranslationFromSnapshot(ride, 0.0f, true);

        assertTrue(translation.y > 0.4);
        assertEquals(-Math.cos(0.5), translation.z, 0.001);
    }

    @Test
    void groundTranslationIgnoresLookPitch() {
        TameworkRideMountComponent ride = new TameworkRideMountComponent();
        ride.captureHeadRotation(0.0f, 0.5f, 0.0f);
        ride.captureWishMovement(0.0, 0.0, 1.0);

        Vector3d translation = BodyMotionTameworkRide.resolveTranslationFromSnapshot(ride, 0.0f, false);

        assertEquals(0.0, translation.y, 0.0001);
        assertEquals(-1.0, translation.z, 0.0001);
    }
}
