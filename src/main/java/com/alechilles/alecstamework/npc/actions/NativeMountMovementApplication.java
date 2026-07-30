package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.npc.movement.NativeMountMovementSettingsService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionMovementSpeedResolver;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.logging.Level;

/** Applies native mounting and its source-role rider movement profile as one ordered operation. */
final class NativeMountMovementApplication {
    private static final String EMPTY_ROLE_ID = "Empty_Role";
    private static final String MOVE_SPEED_MULTIPLIER_EFFECT_KEY = "MoveSpeedMultiplier";
    private static final String DEFAULT_MOUNT_ANCHOR_X_PARAM = "MountAnchorX";
    private static final String DEFAULT_MOUNT_ANCHOR_Y_PARAM = "MountAnchorY";
    private static final String DEFAULT_MOUNT_ANCHOR_Z_PARAM = "MountAnchorZ";

    private final ActionTameworkInteract owner;
    private final NativeMountMovementSettingsService movementSettings = new NativeMountMovementSettingsService();
    private final CompanionMovementSpeedResolver speedResolver = new CompanionMovementSpeedResolver();

    NativeMountMovementApplication(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    boolean apply(InteractionMountRequest request) {
        Ref<EntityStore> npcRef = request.npcRef();
        Ref<EntityStore> riderRef = request.playerRef();
        Role role = request.role();
        Store<EntityStore> store = request.store();
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        if (mountType == null) {
            return fail(role, "npc_mount_component_type_unavailable");
        }
        if (store.getComponent(npcRef, mountType) != null) {
            return fail(role, "npc_already_has_mount_component");
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        Player rider = store.getComponent(riderRef, Player.getComponentType());
        PlayerRef riderPlayerRef = store.getComponent(riderRef, PlayerRef.getComponentType());
        if (npc == null) {
            return fail(role, "missing_npc_component");
        }
        if (rider == null) {
            return fail(role, "missing_player_component");
        }
        if (riderPlayerRef == null) {
            return fail(role, "missing_player_ref_component");
        }
        int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
        int emptyRoleIndex = NPCPlugin.get().getIndex(EMPTY_ROLE_ID);
        if (originalRoleIndex < 0 || emptyRoleIndex < 0) {
            return fail(role, "missing_role_index");
        }
        String sourceRoleId = role.getRoleName();
        double multiplier = resolveQuantizedMultiplier(npcRef, sourceRoleId, store);
        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        NPCMountComponent mount = store.ensureAndGetComponent(npcRef, mountType);
        if (mount == null) {
            return fail(role, "ensure_npc_mount_failed");
        }
        mount.setOriginalRoleIndex(originalRoleIndex);
        mount.setOwnerPlayerRef(riderPlayerRef);
        mount.setAnchor(anchorX, anchorY, anchorZ);
        npc.playAnimation(npcRef, AnimationSlot.Status, null, store);
        movementSettings.applyScaledSettings(
                sourceRoleId, owner.resolveRoleScopes(role), riderRef, riderPlayerRef, rider, store, multiplier);
        RoleChangeSystem.requestRoleChange(npcRef, role, emptyRoleIndex, false, null, null, store);
        logApplied(role, anchorX, anchorY, anchorZ);
        return true;
    }

    private double resolveQuantizedMultiplier(Ref<EntityStore> npcRef,
                                              String sourceRoleId,
                                              Store<EntityStore> store) {
        TwCompanionMovementConfig.ResolvedMovement movement = TwCompanionMovementConfig.resolveForRole(sourceRoleId);
        double progression = CompanionProgressionModifierService.resolveMultiplier(
                npcRef, store, sourceRoleId, MOVE_SPEED_MULTIPLIER_EFFECT_KEY, 1.0);
        return speedResolver.resolve(
                movement,
                CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store),
                progression
        ).quantizedMultiplier();
    }

    private static void logApplied(Role role, float anchorX, float anchorY, float anchorZ) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkMount debug: stage=native role=%s reason=applied anchor=%s/%s/%s",
                role == null ? "<null>" : role.getRoleName(), anchorX, anchorY, anchorZ
        );
    }

    private static boolean fail(Role role, String reason) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.isDebugRideEnabled() && instance.getLogger() != null) {
            instance.getLogger().at(Level.INFO).log(
                    "TameworkMount debug: stage=native role=%s reason=%s",
                    role == null ? "<null>" : role.getRoleName(), reason
            );
        }
        return false;
    }
}
