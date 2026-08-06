package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import javax.annotation.Nullable;

/** Keeps a hidden parked avatar-flight source spatially aligned with its rider. */
final class AvatarFlightSourceFollowService {
    boolean sync(@Nullable TransformComponent rider,
                 @Nullable TransformComponent source) {
        if (!ready(rider) || !ready(source)) {
            return false;
        }
        source.setPosition(rider.getPosition());
        source.getRotation().setYaw(rider.getRotation().yaw());
        source.getRotation().setPitch(rider.getRotation().pitch());
        source.getRotation().setRoll(rider.getRotation().roll());
        return true;
    }

    private static boolean ready(@Nullable TransformComponent transform) {
        return transform != null && transform.getPosition() != null
                && transform.getRotation() != null;
    }
}
