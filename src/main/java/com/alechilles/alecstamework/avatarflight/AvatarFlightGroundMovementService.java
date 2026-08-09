package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Applies the configured native base speed only while avatar flight is grounded. */
final class AvatarFlightGroundMovementService {
    private static final float SPEED_EPSILON = 0.0001f;

    void sync(@Nonnull Ref<EntityStore> ref,
              @Nonnull CommandBuffer<EntityStore> commandBuffer,
              @Nonnull AvatarFlightComponent flight,
              double groundedMoveSpeed,
              boolean grounded,
              boolean movementLocked) {
        MovementManager current = commandBuffer.getComponent(ref, MovementManager.getComponentType());
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (current == null || current.getSettings() == null || playerRef == null) {
            return;
        }
        if (!grounded) {
            restore(ref, commandBuffer, current, playerRef, flight);
            return;
        }

        MovementManager updated = new MovementManager(current);
        MovementSettings settings = updated.getSettings();
        if (!flight.isGroundedMoveSpeedApplied()) {
            flight.captureGroundedBaseSpeed(settings.baseSpeed);
        }
        float target = movementLocked ? 0.0f : positiveFloat(groundedMoveSpeed, 8.0f);
        if (approximately(settings.baseSpeed, target)) {
            return;
        }
        settings.baseSpeed = target;
        commandBuffer.putComponent(ref, MovementManager.getComponentType(), updated);
        updated.update(playerRef.getPacketHandler());
    }

    void restore(@Nonnull Store<EntityStore> store,
                 @Nonnull Ref<EntityStore> ref,
                 @Nonnull AvatarFlightComponent flight) {
        if (!flight.isGroundedMoveSpeedApplied()) {
            return;
        }
        MovementManager current = store.getComponent(ref, MovementManager.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (current != null && current.getSettings() != null && playerRef != null) {
            MovementManager updated = new MovementManager(current);
            updated.getSettings().baseSpeed = (float) flight.getOriginalGroundedBaseSpeed();
            store.putComponent(ref, MovementManager.getComponentType(), updated);
            updated.update(playerRef.getPacketHandler());
        }
        flight.clearGroundedBaseSpeed();
    }

    private static void restore(@Nonnull Ref<EntityStore> ref,
                                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull MovementManager current,
                                @Nonnull PlayerRef playerRef,
                                @Nonnull AvatarFlightComponent flight) {
        if (!flight.isGroundedMoveSpeedApplied()) {
            return;
        }
        MovementManager updated = new MovementManager(current);
        float original = (float) flight.getOriginalGroundedBaseSpeed();
        if (!approximately(updated.getSettings().baseSpeed, original)) {
            updated.getSettings().baseSpeed = original;
            commandBuffer.putComponent(ref, MovementManager.getComponentType(), updated);
            updated.update(playerRef.getPacketHandler());
        }
        flight.clearGroundedBaseSpeed();
    }

    private static float positiveFloat(double value, float fallback) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return fallback;
        }
        return (float) Math.min(value, Float.MAX_VALUE);
    }

    private static boolean approximately(float left, float right) {
        return Math.abs(left - right) <= SPEED_EPSILON;
    }
}
