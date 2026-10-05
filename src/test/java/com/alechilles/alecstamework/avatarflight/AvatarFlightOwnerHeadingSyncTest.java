package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvatarFlightOwnerHeadingSyncTest {
    @Test
    void ownerBodyIsTurnedOnlyWhileFlyingForwardOffHeading() {
        double lookYaw = Math.toRadians(90.0);
        double forwardX = -Math.sin(lookYaw);
        double forwardZ = -Math.cos(lookYaw);

        assertTrue(AvatarFlightOwnerHeadingSyncSystem.shouldSync(
                forwardX * 6.0, forwardZ * 6.0, lookYaw, Math.toRadians(40.0)));
        assertFalse(AvatarFlightOwnerHeadingSyncSystem.shouldSync(
                forwardX * 6.0, forwardZ * 6.0, lookYaw, Math.toRadians(88.0)));
        assertFalse(AvatarFlightOwnerHeadingSyncSystem.shouldSync(
                forwardX * 0.5, forwardZ * 0.5, lookYaw, Math.toRadians(40.0)));
        assertFalse(AvatarFlightOwnerHeadingSyncSystem.shouldSync(
                forwardX * -6.0, forwardZ * -6.0, lookYaw, Math.toRadians(40.0)));
    }

    @Test
    void headingErrorWrapsAcrossTheYawSeam() {
        double lookYaw = Math.toRadians(179.0);
        double forwardX = -Math.sin(lookYaw);
        double forwardZ = -Math.cos(lookYaw);

        assertFalse(AvatarFlightOwnerHeadingSyncSystem.shouldSync(
                forwardX * 6.0, forwardZ * 6.0, lookYaw, Math.toRadians(-179.0)));
    }
}
