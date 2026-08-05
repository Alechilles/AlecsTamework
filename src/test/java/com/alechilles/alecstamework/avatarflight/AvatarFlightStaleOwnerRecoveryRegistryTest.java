package com.alechilles.alecstamework.avatarflight;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for handing crash-recovered avatar sources back to roster cleanup. */
class AvatarFlightStaleOwnerRecoveryRegistryTest {
    @Test
    void staleOwnerIsClaimedOnlyOnce() {
        UUID owner = UUID.randomUUID();

        AvatarFlightStaleOwnerRecoveryRegistry.record(owner);

        assertTrue(AvatarFlightStaleOwnerRecoveryRegistry.claim(owner));
        assertFalse(AvatarFlightStaleOwnerRecoveryRegistry.claim(owner));
    }
}
