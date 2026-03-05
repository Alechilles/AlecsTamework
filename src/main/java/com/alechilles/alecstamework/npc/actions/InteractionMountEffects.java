package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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
        NPCEntity npcComponent = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npcComponent == null) {
            return false;
        }
        int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
        int emptyRoleIndex = NPCPlugin.get().getIndex(EMPTY_ROLE_ID);
        if (originalRoleIndex < 0 || emptyRoleIndex < 0) {
            return false;
        }
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        if (playerComponent == null) {
            return false;
        }
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) {
            return false;
        }
        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        String movementConfigId = owner.getRoleStringParam(role, DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM);
        if (movementConfigId == null || movementConfigId.isBlank()) {
            movementConfigId = DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
        }
        NPCMountComponent createdMount = store.ensureAndGetComponent(npcRef, mountType);
        if (createdMount == null) {
            return false;
        }
        createdMount.setOriginalRoleIndex(originalRoleIndex);
        createdMount.setOwnerPlayerRef(playerRefComponent);
        createdMount.setAnchor(anchorX, anchorY, anchorZ);
        clearStatusAnimation(npcRef, npcComponent, store);
        RoleChangeSystem.requestRoleChange(npcRef, role, emptyRoleIndex, false, null, null, store);
        applyMovementConfig(playerRef, playerRefComponent, playerComponent, store, movementConfigId);
        return true;
    }

    private void clearStatusAnimation(Ref<EntityStore> npcRef,
                                      NPCEntity npcComponent,
                                      Store<EntityStore> store) {
        if (npcRef == null || npcComponent == null || store == null) {
            return;
        }
        npcComponent.playAnimation(npcRef, AnimationSlot.Status, null, store);
    }

    private void applyMovementConfig(Ref<EntityStore> playerRef,
                                     PlayerRef playerRefComponent,
                                     Player playerComponent,
                                     Store<EntityStore> store,
                                     String movementConfigId) {
        if (playerRef == null || playerRefComponent == null || playerComponent == null || store == null) {
            return;
        }
        PhysicsValues playerPhysicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
        if (playerPhysicsValues == null) {
            return;
        }
        String resolvedMovementConfigId = movementConfigId;
        if (resolvedMovementConfigId == null || resolvedMovementConfigId.isBlank()) {
            resolvedMovementConfigId = DEFAULT_MOUNT_MOVEMENT_CONFIG_ID;
        }
        MovementConfig movementConfig = MovementConfig.getAssetMap().getAsset(resolvedMovementConfigId);
        if (movementConfig == null) {
            return;
        }
        MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
        if (movementManager == null) {
            return;
        }
        movementManager.setDefaultSettings(movementConfig.toPacket(), playerPhysicsValues, playerComponent.getGameMode());
        movementManager.applyDefaultSettings();
        movementManager.update(playerRefComponent.getPacketHandler());
    }
}
