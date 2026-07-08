package com.alechilles.alecstamework.avatarflight;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Temporarily enables client flight capability only to expose the double-jump flight-toggle input.
 */
public final class AvatarFlightActivationCapability {
    private static final ConcurrentHashMap<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private AvatarFlightActivationCapability() {
    }

    public static void enableGroundedProbe(@Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> ref,
                                           @Nonnull UUID playerUuid) {
        apply(store, ref, playerUuid, true);
    }

    public static void setGroundedProbeEnabled(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                                               @Nonnull Ref<EntityStore> ref,
                                               @Nullable UUID playerUuid,
                                               boolean enabled) {
        if (playerUuid == null) {
            return;
        }
        apply(commandBuffer, ref, playerUuid, enabled);
    }

    public static void restore(@Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref,
                               @Nonnull UUID playerUuid) {
        Snapshot snapshot = SNAPSHOTS.remove(playerUuid);
        if (snapshot == null) {
            return;
        }
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return;
        }
        restoreSnapshot(movementManager, snapshot);
        updateClientMovementSettings(store, ref, movementManager);
        store.putComponent(ref, MovementManager.getComponentType(), movementManager);
    }

    public static void clear(@Nonnull UUID playerUuid) {
        SNAPSHOTS.remove(playerUuid);
    }

    private static void apply(@Nonnull Store<EntityStore> store,
                              @Nonnull Ref<EntityStore> ref,
                              @Nonnull UUID playerUuid,
                              boolean enabled) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return;
        }
        Snapshot snapshot = snapshot(playerUuid, movementManager);
        if (!setCanFly(movementManager, snapshot, enabled)) {
            return;
        }
        updateClientMovementSettings(store, ref, movementManager);
        store.putComponent(ref, MovementManager.getComponentType(), movementManager);
    }

    private static void apply(@Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull Ref<EntityStore> ref,
                              @Nonnull UUID playerUuid,
                              boolean enabled) {
        MovementManager movementManager = commandBuffer.getComponent(ref, MovementManager.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return;
        }
        Snapshot snapshot = snapshot(playerUuid, movementManager);
        if (!setCanFly(movementManager, snapshot, enabled)) {
            return;
        }
        updateClientMovementSettings(commandBuffer, ref, movementManager);
        commandBuffer.putComponent(ref, MovementManager.getComponentType(), movementManager);
    }

    @Nonnull
    private static Snapshot snapshot(@Nonnull UUID playerUuid, @Nonnull MovementManager movementManager) {
        return SNAPSHOTS.computeIfAbsent(playerUuid, ignored -> new Snapshot(
                movementManager.getSettings().canFly,
                movementManager.getDefaultSettings() == null ? null : movementManager.getDefaultSettings().canFly
        ));
    }

    private static boolean setCanFly(@Nonnull MovementManager movementManager,
                                     @Nonnull Snapshot snapshot,
                                     boolean enabled) {
        boolean desired = enabled || snapshot.canFly();
        boolean changed = movementManager.getSettings().canFly != desired;
        movementManager.getSettings().canFly = desired;
        if (movementManager.getDefaultSettings() != null) {
            boolean desiredDefault = enabled || snapshot.defaultCanFlyOrFallback();
            changed |= movementManager.getDefaultSettings().canFly != desiredDefault;
            movementManager.getDefaultSettings().canFly = desiredDefault;
        }
        return changed;
    }

    private static void restoreSnapshot(@Nonnull MovementManager movementManager, @Nonnull Snapshot snapshot) {
        movementManager.getSettings().canFly = snapshot.canFly();
        if (movementManager.getDefaultSettings() != null && snapshot.defaultCanFly() != null) {
            movementManager.getDefaultSettings().canFly = snapshot.defaultCanFly();
        }
    }

    private static void updateClientMovementSettings(@Nonnull ComponentAccessor<EntityStore> accessor,
                                                     @Nonnull Ref<EntityStore> ref,
                                                     @Nonnull MovementManager movementManager) {
        PacketHandler packetHandler = resolvePacketHandler(accessor, ref);
        if (packetHandler != null) {
            movementManager.update(packetHandler);
        }
    }

    @Nullable
    private static PacketHandler resolvePacketHandler(@Nonnull ComponentAccessor<EntityStore> accessor,
                                                      @Nonnull Ref<EntityStore> ref) {
        PlayerRef playerRef = accessor.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getPacketHandler();
    }

    private record Snapshot(boolean canFly, @Nullable Boolean defaultCanFly) {
        private boolean defaultCanFlyOrFallback() {
            return defaultCanFly == null ? canFly : defaultCanFly;
        }
    }
}
