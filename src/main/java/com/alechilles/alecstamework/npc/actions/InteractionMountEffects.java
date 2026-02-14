package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;

/** Applies mount-related interaction effects. */
final class InteractionMountEffects {
    private static final String EMPTY_ROLE_ID = "Empty_Role";
    private static final String DEFAULT_MOUNT_ANCHOR_X_PARAM = "MountAnchorX";
    private static final String DEFAULT_MOUNT_ANCHOR_Y_PARAM = "MountAnchorY";
    private static final String DEFAULT_MOUNT_ANCHOR_Z_PARAM = "MountAnchorZ";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM = "MountMovementConfig";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_ID = "Mount";

    private final ActionTameworkInteract owner;

    InteractionMountEffects(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Attempts to mount the interacting player onto the NPC and configure movement.
    boolean applyMount(Ref<EntityStore> npcRef,
                       Role role,
                       InfoProvider infoProvider,
                       Store<EntityStore> store) {
        if (npcRef == null || role == null || store == null) {
            return false;
        }
        Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        if (store.getArchetype(playerRef).contains(DeathComponent.getComponentType())) {
            return false;
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        if (mountType == null) {
            return false;
        }
        NPCMountComponent mountComponent = store.getComponent(npcRef, mountType);
        if (mountComponent != null) {
            return false;
        }
        mountComponent = store.ensureAndGetComponent(npcRef, mountType);
        mountComponent.setOriginalRoleIndex(NPCPlugin.get().getIndex(role.getRoleName()));
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) {
            return false;
        }
        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        mountComponent.setOwnerPlayerRef(playerRefComponent);
        mountComponent.setAnchor(anchorX, anchorY, anchorZ);
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        PhysicsValues playerPhysicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
        RoleChangeSystem.requestRoleChange(npcRef, role, NPCPlugin.get().getIndex(EMPTY_ROLE_ID), false, null, null, store);
        String movementConfigId = owner.getRoleStringParam(role, DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM);
        if (movementConfigId == null || movementConfigId.isBlank()) {
            movementConfigId = DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
        }
        MovementConfig movementConfig = MovementConfig.getAssetMap().getAsset(movementConfigId);
        if (movementConfig != null && playerPhysicsValues != null) {
            MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
            if (movementManager != null) {
                movementManager.setDefaultSettings(movementConfig.toPacket(), playerPhysicsValues, playerComponent.getGameMode());
                movementManager.applyDefaultSettings();
                movementManager.update(playerRefComponent.getPacketHandler());
            }
        }
        return true;
    }
}
