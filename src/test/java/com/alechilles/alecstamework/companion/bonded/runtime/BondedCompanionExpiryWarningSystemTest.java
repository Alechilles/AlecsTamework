package com.alechilles.alecstamework.companion.bonded.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.avatarflight.AvatarFlightMountPhase;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BondedCompanionExpiryWarningSystemTest {
    private static final UUID COMPANION_UUID = UUID.fromString("d99a56f4-c0b3-4c6b-9a74-1b1e6e318c2e");

    @Test
    void targets_the_owner_avatar_for_the_active_session_of_the_expiring_companion() {
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                COMPANION_UUID.toString(), "world", "dragon-flight", 1L);
        session.setPhase(AvatarFlightMountPhase.ACTIVE);

        assertTrue(BondedCompanionExpiryWarningSystem.usesOwnerAvatarModel(
                session, COMPANION_UUID));
    }

    @Test
    void keeps_the_companion_target_while_avatar_flight_is_preparing() {
        AvatarFlightMountSessionComponent preparing = new AvatarFlightMountSessionComponent(
                COMPANION_UUID.toString(), "world", "dragon-flight", 1L);

        assertFalse(BondedCompanionExpiryWarningSystem.usesOwnerAvatarModel(
                preparing, COMPANION_UUID));
    }

    @Test
    void keeps_the_companion_target_for_another_active_flight() {
        AvatarFlightMountSessionComponent otherCompanion = new AvatarFlightMountSessionComponent(
                "c1c9c820-6be8-4c10-8948-45a5081b4d3f", "world", "dragon-flight", 1L);
        otherCompanion.setPhase(AvatarFlightMountPhase.ACTIVE);

        assertFalse(BondedCompanionExpiryWarningSystem.usesOwnerAvatarModel(
                otherCompanion, COMPANION_UUID));
    }
}
