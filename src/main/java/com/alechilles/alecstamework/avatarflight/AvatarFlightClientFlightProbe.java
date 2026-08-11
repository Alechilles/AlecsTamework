package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.compat.HytaleMovementSettingsAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Debug-only probe that gives a player client flight capability without changing game mode.
 */
public final class AvatarFlightClientFlightProbe {
    private static final ConcurrentHashMap<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private AvatarFlightClientFlightProbe() {
    }

    @Nonnull
    public static Result enable(@Nonnull Store<EntityStore> store,
                                @Nonnull Ref<EntityStore> ref,
                                @Nonnull UUID playerUuid) {
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        MovementStatesComponent movementStatesComponent =
                store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (movementManager == null || movementManager.getSettings() == null) {
            return Result.fail("Client flight probe failed: MovementManager is unavailable.");
        }
        if (movementStatesComponent == null || movementStatesComponent.getMovementStates() == null) {
            return Result.fail("Client flight probe failed: MovementStatesComponent is unavailable.");
        }
        PacketHandler packetHandler = resolvePacketHandler(store, ref);
        if (packetHandler == null) {
            return Result.fail("Client flight probe failed: Player packet handler is unavailable.");
        }

        MovementStates movementStates = movementStatesComponent.getMovementStates();
        SNAPSHOTS.computeIfAbsent(playerUuid, ignored -> new Snapshot(
                HytaleMovementSettingsAccess.readFlightSetting(movementManager.getSettings()),
                HytaleMovementSettingsAccess.readFlightSetting(movementManager.getDefaultSettings()),
                movementStates.flying
        ));

        HytaleMovementSettingsAccess.allowFlight(movementManager.getSettings());
        if (movementManager.getDefaultSettings() != null) {
            HytaleMovementSettingsAccess.allowFlight(movementManager.getDefaultSettings());
        }
        movementManager.update(packetHandler);
        Player.applyMovementStates(ref, new SavedMovementStates(true), movementStates, store);
        return Result.ok("Client flight probe enabled and input logging enabled.");
    }

    @Nonnull
    public static Result disable(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> ref,
                                 @Nonnull UUID playerUuid) {
        Snapshot snapshot = SNAPSHOTS.remove(playerUuid);
        if (snapshot == null) {
            return Result.ok("Client flight probe was not active.");
        }
        MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
        MovementStatesComponent movementStatesComponent =
                store.getComponent(ref, MovementStatesComponent.getComponentType());
        PacketHandler packetHandler = resolvePacketHandler(store, ref);
        if (movementManager != null && movementManager.getSettings() != null) {
            HytaleMovementSettingsAccess.restoreFlightSetting(
                    movementManager.getSettings(), snapshot.flightSetting());
            if (movementManager.getDefaultSettings() != null
                    && snapshot.defaultFlightSetting() != null) {
                HytaleMovementSettingsAccess.restoreFlightSetting(
                        movementManager.getDefaultSettings(), snapshot.defaultFlightSetting());
            }
            if (packetHandler != null) {
                movementManager.update(packetHandler);
            }
        }
        if (movementStatesComponent != null && movementStatesComponent.getMovementStates() != null) {
            Player.applyMovementStates(
                    ref,
                    new SavedMovementStates(snapshot.flying()),
                    movementStatesComponent.getMovementStates(),
                    store
            );
        }
        return Result.ok("Client flight probe disabled and movement settings restored.");
    }

    public static void clear(@Nonnull UUID playerUuid) {
        SNAPSHOTS.remove(playerUuid);
    }

    public static boolean isActive(@Nonnull UUID playerUuid) {
        return SNAPSHOTS.containsKey(playerUuid);
    }

    @Nullable
    private static PacketHandler resolvePacketHandler(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getPacketHandler();
    }

    private record Snapshot(
            @Nonnull Object flightSetting,
            @Nullable Object defaultFlightSetting,
            boolean flying) {
    }

    public record Result(boolean ok, @Nonnull String message) {
        @Nonnull
        public static Result ok(@Nonnull String message) {
            return new Result(true, message);
        }

        @Nonnull
        public static Result fail(@Nonnull String message) {
            return new Result(false, message);
        }
    }
}
