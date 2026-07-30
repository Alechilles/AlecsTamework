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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Applies a native mount's copied rider profile with its already-quantized companion speed multiplier.
 */
public final class NativeMountMovementSettingsService {
    public static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_ID = "Mount";
    private static final String MOUNT_MOVEMENT_CONFIG_PARAM = "MountMovementConfig";
    private final StdScopeLookupCache scopeLookupCache = new StdScopeLookupCache();

    /** Applies the selected source-role movement profile to the active rider without mutating its asset. */
    public boolean applyScaledSettings(@Nullable String sourceRoleId,
                                       @Nullable StdScope[] sourceRoleScopes,
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
        MovementConfig profile = MovementConfig.getAssetMap().getAsset(
                resolveMovementConfigId(sourceRoleId, sourceRoleScopes));
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
            return resolveMountedRoleId(
                    mount.getOriginalRoleIndex(),
                    plugin == null ? null : plugin::getName
            );
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        return resolveManagedRoleId(false, null, npc == null ? null : npc.getRoleName());
    }

    /** Resolves the mounted rider through the mount's stable player reference in the active entity store. */
    @Nullable
    public static Ref<EntityStore> resolveMountedRiderRef(@Nullable NPCMountComponent mount,
                                                           @Nullable Store<EntityStore> store) {
        PlayerRef owner = mount == null ? null : mount.getOwnerPlayerRef();
        UUID ownerUuid = owner == null ? null : owner.getUuid();
        World world = store == null || store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
        return resolveActiveRider(ownerUuid, new ActiveRiderLookup<>() {
            @Override
            public Ref<EntityStore> findInActiveWorld(UUID riderUuid) {
                return world == null ? null : world.getEntityRef(riderUuid);
            }

            @Override
            public boolean isActivePlayer(Ref<EntityStore> riderRef) {
                return riderRef != null && riderRef.isValid() && store != null
                        && store.getComponent(riderRef, Player.getComponentType()) != null;
            }
        });
    }

    @Nullable
    static String resolveManagedRoleId(boolean nativeMountPresent,
                                       @Nullable String originalMountedRoleId,
                                       @Nullable String currentRoleId) {
        if (nativeMountPresent) {
            return originalMountedRoleId == null || originalMountedRoleId.isBlank() ? null : originalMountedRoleId;
        }
        return currentRoleId == null || currentRoleId.isBlank() ? null : currentRoleId;
    }

    @Nullable
    static String resolveMountedRoleId(int originalRoleIndex, @Nullable RoleNameLookup roleNames) {
        if (roleNames == null) {
            return null;
        }
        String originalRoleId = roleNames.getName(originalRoleIndex);
        return resolveManagedRoleId(true, originalRoleId, null);
    }

    @Nullable
    static <T> T resolveActiveRider(@Nullable UUID riderUuid, @Nullable ActiveRiderLookup<T> lookup) {
        if (riderUuid == null || lookup == null) {
            return null;
        }
        T rider = lookup.findInActiveWorld(riderUuid);
        return rider != null && lookup.isActivePlayer(rider) ? rider : null;
    }

    static MovementSettings copyWithScaledBaseSpeed(@Nullable MovementSettings source, double multiplier) {
        MovementSettings copy = new MovementSettings(source == null ? new MovementSettings() : source);
        double effectiveMultiplier = Double.isFinite(multiplier) && multiplier > 0.0 ? multiplier : 1.0;
        copy.baseSpeed = (float) (copy.baseSpeed * effectiveMultiplier);
        return copy;
    }

    private String resolveMovementConfigId(@Nullable String sourceRoleId, @Nullable StdScope[] sourceRoleScopes) {
        if (sourceRoleId == null || sourceRoleId.isBlank() || sourceRoleScopes == null) {
            return DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
        }
        for (StdScope scope : sourceRoleScopes) {
            String configured = scopeLookupCache.getString(scope, MOUNT_MOVEMENT_CONFIG_PARAM);
            if (configured != null && !configured.isBlank()) {
                return configured.trim();
            }
        }
        return DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
    }

    interface RoleNameLookup {
        @Nullable String getName(int originalRoleIndex);
    }

    interface ActiveRiderLookup<T> {
        @Nullable T findInActiveWorld(UUID riderUuid);

        boolean isActivePlayer(T rider);
    }
}
