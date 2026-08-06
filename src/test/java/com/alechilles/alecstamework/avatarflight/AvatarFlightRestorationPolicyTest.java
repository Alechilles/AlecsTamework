package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightMountingSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies restoration placement distinguishes voluntary dismounts from exceptional cleanup. */
class AvatarFlightRestorationPolicyTest {
    private final AvatarFlightMountingSettings settings = new AvatarFlightMountingSettings();

    /** Protects F-key dismount from reusing the takeoff-ground snapshot while airborne. */
    @Test
    void airborneNormalDismountUsesCurrentFlightPosition() {
        AvatarFlightMountSessionComponent session = sessionWithOriginAndSafeGround();
        AvatarFlightRestorationPolicy.Position current =
                new AvatarFlightRestorationPolicy.Position(40.0, 70.0, 90.0, 1.25f);

        AvatarFlightRestorationPolicy.Position selected = AvatarFlightRestorationPolicy.select(
                session,
                settings,
                AvatarFlightMountLifecycleService.EndReason.NORMAL,
                current,
                false
        );

        assertEquals(current, selected);
    }

    @Test
    void groundedNormalDismountKeepsLastSafeGroundBehavior() {
        AvatarFlightMountSessionComponent session = sessionWithOriginAndSafeGround();
        AvatarFlightRestorationPolicy.Position current =
                new AvatarFlightRestorationPolicy.Position(40.0, 70.0, 90.0, 1.25f);

        AvatarFlightRestorationPolicy.Position selected = AvatarFlightRestorationPolicy.select(
                session,
                settings,
                AvatarFlightMountLifecycleService.EndReason.NORMAL,
                current,
                true
        );

        assertEquals(new AvatarFlightRestorationPolicy.Position(4.0, 5.0, 6.0, 0.75f), selected);
    }

    /** Prevents death cleanup from leaving the restored companion suspended at the rider's flight position. */
    @Test
    void exceptionalCleanupKeepsOriginalMountPosition() {
        AvatarFlightMountSessionComponent session = sessionWithOriginAndSafeGround();
        AvatarFlightRestorationPolicy.Position current =
                new AvatarFlightRestorationPolicy.Position(40.0, 70.0, 90.0, 1.25f);

        AvatarFlightRestorationPolicy.Position selected = AvatarFlightRestorationPolicy.select(
                session,
                settings,
                AvatarFlightMountLifecycleService.EndReason.PLAYER_DEAD,
                current,
                false
        );

        assertEquals(new AvatarFlightRestorationPolicy.Position(1.0, 2.0, 3.0, 0.25f), selected);
    }

    private static AvatarFlightMountSessionComponent sessionWithOriginAndSafeGround() {
        AvatarFlightMountSessionComponent session = new AvatarFlightMountSessionComponent(
                "26684248-7c9d-4618-bb65-ced5c14bd04a", "default", "AHAvatarFlight", 42L);
        session.captureOrigin(1.0, 2.0, 3.0, 0.25f);
        session.captureLastSafeGround(4.0, 5.0, 6.0, 0.75f);
        return session;
    }
}
