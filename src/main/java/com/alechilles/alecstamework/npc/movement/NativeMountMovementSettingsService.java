package com.alechilles.alecstamework.npc.movement;

import com.alechilles.alecstamework.Tamework;
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
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Applies a native mount's copied rider profile with its exact clamped companion speed multiplier.
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
        String movementConfigId = resolveMovementConfigId(sourceRoleId, sourceRoleScopes);
        MovementConfig profile = MovementConfig.getAssetMap().getAsset(movementConfigId);
        if (physics == null || manager == null || profile == null) {
            return false;
        }
        MovementSettings profileSettings = profile.toPacket();
        MovementSettings settings = copyWithScaledBaseSpeed(profileSettings, quantizedMultiplier);
        manager.setDefaultSettings(settings, physics, rider.getGameMode());
        manager.applyDefaultSettings();
        manager.update(riderPlayerRef.getPacketHandler());
        logAppliedSettings(sourceRoleId, movementConfigId, profileSettings.baseSpeed, settings.baseSpeed);
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
            String originalRoleId = new NativeMountedRoleLookup(NPCPlugin.get()).resolve(mount);
            return resolveManagedRoleId(true, originalRoleId, null);
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
        return new ActiveWorldRiderLookup(world, store).resolve(ownerUuid);
    }

    /** Resolves the original mounted role's configured parameter scope for a later rider refresh. */
    @Nullable
    public static StdScope[] resolveMountedSourceRoleScopes(@Nullable NPCMountComponent mount) {
        return new NativeMountMovementScopeResolver().resolve(mount);
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

    private static void logAppliedSettings(String sourceRoleId,
                                           String movementConfigId,
                                           float profileBaseSpeed,
                                           float appliedBaseSpeed) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkMount debug: stage=native_settings sourceRole=%s movementConfig=%s "
                        + "profileBaseSpeed=%s appliedBaseSpeed=%s",
                sourceRoleId, movementConfigId, profileBaseSpeed, appliedBaseSpeed
        );
    }
}
