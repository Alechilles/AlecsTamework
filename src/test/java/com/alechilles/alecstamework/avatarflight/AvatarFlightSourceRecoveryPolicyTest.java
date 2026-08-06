package com.alechilles.alecstamework.avatarflight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies orphan recovery does not take ownership from a live mount lifecycle. */
class AvatarFlightSourceRecoveryPolicyTest {
    @Test
    void restoringPairRemainsOwnedByMountLifecycle() {
        String sourceUuid = "26684248-7c9d-4618-bb65-ced5c14bd04a";
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                sourceUuid, "default", "AHAvatarFlight", 42L);
        session.setPhase(AvatarFlightMountPhase.RESTORING);
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                "bb444e41-67ba-3eea-bfc9-0978b7729522", "Tamed_Dragon_Frost", 7);
        source.setPhase(AvatarFlightMountPhase.RESTORING);

        assertTrue(AvatarFlightSourceRecoveryPolicy.isLifecycleOwned(session, source, sourceUuid));
    }

    @Test
    void differentSourceIsAvailableForOrphanRecovery() {
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                "26684248-7c9d-4618-bb65-ced5c14bd04a", "default", "AHAvatarFlight", 42L);
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                "bb444e41-67ba-3eea-bfc9-0978b7729522", "Tamed_Dragon_Frost", 7);

        assertFalse(AvatarFlightSourceRecoveryPolicy.isLifecycleOwned(
                session, source, "32fcf5cb-2da9-425f-8770-56387868c279"));
    }
}
