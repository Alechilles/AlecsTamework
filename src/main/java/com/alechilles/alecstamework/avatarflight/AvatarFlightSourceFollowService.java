package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import javax.annotation.Nullable;

/** Keeps a parked source aligned with its rider inside Hytale's entity height range. */
final class AvatarFlightSourceFollowService {
    boolean sync(@Nullable TransformComponent rider,
                 @Nullable TransformComponent source) {
        if (!ready(rider) || !ready(source)) {
            return false;
        }
        rider.getPosition().y = Math.min(
                rider.getPosition().y,
                ChunkUtil.HEIGHT_MINUS_1
        );
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
