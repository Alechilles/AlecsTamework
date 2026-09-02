package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.AvatarFlightMountingSettings;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects the shared player/source placement used when an avatar-flight mount ends. */
final class AvatarFlightRestorationPolicy {
    private AvatarFlightRestorationPolicy() {
    }

    @Nonnull
    static Position select(
            @Nonnull AvatarFlightMountSessionComponent session,
            @Nonnull AvatarFlightMountingSettings settings,
            @Nonnull AvatarFlightMountLifecycleService.EndReason reason,
            @Nullable Position current,
            @Nullable Boolean grounded
    ) {
        Position origin = new Position(
                session.getOriginX(), session.getOriginY(), session.getOriginZ(), session.getOriginYaw());
        if (reason == AvatarFlightMountLifecycleService.EndReason.SOURCE_MISSING
                && session.isLastSafeGroundValid()) {
            return lastSafeGround(session);
        }
        if (reason != AvatarFlightMountLifecycleService.EndReason.NORMAL) {
            return origin;
        }
        if (Boolean.TRUE.equals(grounded)
                && settings.isRestoreNpcAtLastSafeGround()
                && session.isLastSafeGroundValid()) {
            return lastSafeGround(session);
        }
        if (current != null) {
            return current;
        }
        if (settings.isRestoreNpcAtLastSafeGround() && session.isLastSafeGroundValid()) {
            return lastSafeGround(session);
        }
        return origin;
    }

    @Nonnull
    static Position selectPlayerPosition(
            @Nonnull Position restoration,
            @Nonnull AvatarFlightMountingSettings settings,
            @Nonnull AvatarFlightMountLifecycleService.EndReason reason
    ) {
        if (reason == AvatarFlightMountLifecycleService.EndReason.SOURCE_MISSING) {
            return restoration;
        }
        double offset = settings.getPlayerDismountOffset();
        return new Position(
                restoration.x() - Math.sin(restoration.yaw()) * offset,
                restoration.y(),
                restoration.z() - Math.cos(restoration.yaw()) * offset,
                restoration.yaw()
        );
    }

    private static Position lastSafeGround(AvatarFlightMountSessionComponent session) {
        return new Position(
                session.getLastSafeGroundX(),
                session.getLastSafeGroundY(),
                session.getLastSafeGroundZ(),
                session.getLastSafeGroundYaw()
        );
    }

    record Position(double x, double y, double z, float yaw) {
    }
}
