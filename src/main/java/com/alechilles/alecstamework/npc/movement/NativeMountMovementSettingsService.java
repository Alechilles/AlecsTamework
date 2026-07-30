package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/**
 * Applies a native mount's copied rider profile with its already-quantized companion speed multiplier.
 */
public final class NativeMountMovementSettingsService {
    public static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_ID = "Mount";

    /** Applies the selected source-role movement profile to the active rider without mutating its asset. */
    public boolean applyScaledSettings(@Nullable String sourceRoleId,
                                       @Nullable String configuredMovementConfigId,
                                       @Nullable Ref<EntityStore> riderRef,
                                       @Nullable PlayerRef riderPlayerRef,
                                       @Nullable Player rider,
                                       @Nullable Store<EntityStore> store,
                                       double quantizedMultiplier) {
        if (sourceRoleId == null || sourceRoleId.isBlank() || riderRef == null || !riderRef.isValid()
                || riderPlayerRef == null || rider == null || store == null) {
            return false;
        }
        PhysicsValues physics = store.getComponent(riderRef, PhysicsValues.getComponentType());
        MovementManager manager = store.getComponent(riderRef, MovementManager.getComponentType());
        MovementConfig profile = MovementConfig.getAssetMap().getAsset(resolveMovementConfigId(configuredMovementConfigId));
        if (physics == null || manager == null || profile == null) {
            return false;
        }
        MovementSettings settings = copyWithScaledBaseSpeed(profile.toPacket(), quantizedMultiplier);
        manager.setDefaultSettings(settings, physics, rider.getGameMode());
        manager.applyDefaultSettings();
        manager.update(riderPlayerRef.getPacketHandler());
        return true;
    }

    /** Returns the active role ID, retaining the pre-Empty_Role role while natively mounted. */
    @Nullable
    public static String resolveManagedRoleId(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        NPCMountComponent mount = store.getComponent(npcRef, NPCMountComponent.getComponentType());
        if (mount != null) {
            NPCPlugin plugin = NPCPlugin.get();
            String originalRoleId = plugin == null ? null : plugin.getName(mount.getOriginalRoleIndex());
            if (originalRoleId != null && !originalRoleId.isBlank()) {
                return originalRoleId;
            }
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc == null ? null : npc.getRoleName();
    }

    /** Resolves the mounted rider through the mount's stable player reference in the active entity store. */
    @Nullable
    public static Ref<EntityStore> resolveMountedRiderRef(@Nullable NPCMountComponent mount,
                                                           @Nullable Store<EntityStore> store) {
        PlayerRef owner = mount == null ? null : mount.getOwnerPlayerRef();
        Ref<EntityStore> riderRef = owner == null ? null : owner.getReference();
        if (riderRef == null || !riderRef.isValid() || store == null) {
            return null;
        }
        return store.getComponent(riderRef, Player.getComponentType()) == null ? null : riderRef;
    }

    static MovementSettings copyWithScaledBaseSpeed(@Nullable MovementSettings source, double multiplier) {
        MovementSettings copy = new MovementSettings(source == null ? new MovementSettings() : source);
        double effectiveMultiplier = Double.isFinite(multiplier) && multiplier > 0.0 ? multiplier : 1.0;
        copy.baseSpeed = (float) (copy.baseSpeed * effectiveMultiplier);
        return copy;
    }

    private static String resolveMovementConfigId(@Nullable String configuredMovementConfigId) {
        return configuredMovementConfigId == null || configuredMovementConfigId.isBlank()
                ? DEFAULT_MOUNT_MOVEMENT_CONFIG_ID
                : configuredMovementConfigId.trim();
    }
}
