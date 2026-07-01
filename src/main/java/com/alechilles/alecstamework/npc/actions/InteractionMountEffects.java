package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
import com.alechilles.alecstamework.npc.systems.MountedRideClientAttachment;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.packets.interaction.MountNPC;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/** Applies mount-related interaction effects. */
final class InteractionMountEffects {
    private static final String EMPTY_ROLE_ID = "Empty_Role";
    private static final String DEFAULT_MOUNT_ANCHOR_X_PARAM = "MountAnchorX";
    private static final String DEFAULT_MOUNT_ANCHOR_Y_PARAM = "MountAnchorY";
    private static final String DEFAULT_MOUNT_ANCHOR_Z_PARAM = "MountAnchorZ";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_PARAM = "MountMovementConfig";
    private static final String DEFAULT_MOUNT_MOVEMENT_CONFIG_ID = "Mount";
    private static final String MOUNT_GLIDE_MOVEMENT_CONFIG_PARAM = "MountGlideMovementConfig";
    private static final String DEFAULT_MOUNT_GLIDE_MOVEMENT_CONFIG_ID = "Tamework_Mounted_Glide_Rider";
    private static final String MOUNT_MODE_PARAM = "MountMode";
    private static final String MOUNT_MODE_TAMEWORK_RIDE = "TameworkRide";
    private static final String MOUNT_MODE_TAMEWORK_MOUNTED_GLIDE = "TameworkMountedGlide";
    private static final String MOUNT_RIDE_STATE_PARAM = "MountRideState";
    private static final String MOUNT_GLIDE_STATE_PARAM = "MountGlideState";
    private static final String MOUNT_GLIDE_CONTROLLER_PARAM = "MountGlideController";
    private static final String MOUNT_GROUND_CONTROLLER_PARAM = "MountGroundController";
    private static final String MOUNT_FLIGHT_CONTROLLER_PARAM = "MountFlightController";

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
            return failMount(role, "resolve", "missing_context",
                    "npcRefPresent=%s rolePresent=%s storePresent=%s",
                    npcRef != null,
                    role != null,
                    store != null);
        }
        String mountMode = owner.getRoleStringParam(role, MOUNT_MODE_PARAM);
        logMountDebug(role, "resolve", "begin", "mode=%s", safeValue(mountMode));
        Ref<EntityStore> playerRef = owner.resolveInteractionTarget(role, infoProvider);
        if (playerRef == null || !playerRef.isValid()) {
            return failMount(role, "resolve", "invalid_interaction_target",
                    "playerRefPresent=%s playerRefValid=%s",
                    playerRef != null,
                    playerRef != null && playerRef.isValid());
        }
        if (store.getArchetype(playerRef).contains(DeathComponent.getComponentType())) {
            return failMount(role, "resolve", "player_dead");
        }
        if (MOUNT_MODE_TAMEWORK_RIDE.equalsIgnoreCase(mountMode)) {
            return applyTameworkRideMount(npcRef, playerRef, role, store);
        }
        if (MOUNT_MODE_TAMEWORK_MOUNTED_GLIDE.equalsIgnoreCase(mountMode)) {
            return applyTameworkMountedGlideMount(npcRef, playerRef, role, store);
        }
        ComponentType<EntityStore, NPCMountComponent> mountType = NPCMountComponent.getComponentType();
        if (mountType == null) {
            return failMount(role, "native", "npc_mount_component_type_unavailable");
        }
        NPCMountComponent mountComponent = store.getComponent(npcRef, mountType);
        if (mountComponent != null) {
            return failMount(role, "native", "npc_already_has_mount_component");
        }
        NPCEntity npcComponent = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npcComponent == null) {
            return failMount(role, "native", "missing_npc_component");
        }
        int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
        int emptyRoleIndex = NPCPlugin.get().getIndex(EMPTY_ROLE_ID);
        if (originalRoleIndex < 0 || emptyRoleIndex < 0) {
            return failMount(role, "native", "missing_role_index",
                    "originalRoleIndex=%s emptyRoleIndex=%s",
                    originalRoleIndex,
                    emptyRoleIndex);
        }
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        if (playerComponent == null) {
            return failMount(role, "native", "missing_player_component");
        }
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (playerRefComponent == null) {
            return failMount(role, "native", "missing_player_ref_component");
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
            return failMount(role, "native", "ensure_npc_mount_failed");
        }
        createdMount.setOriginalRoleIndex(originalRoleIndex);
        createdMount.setOwnerPlayerRef(playerRefComponent);
        createdMount.setAnchor(anchorX, anchorY, anchorZ);
        clearStatusAnimation(npcRef, npcComponent, store);
        RoleChangeSystem.requestRoleChange(npcRef, role, emptyRoleIndex, false, null, null, store);
        applyMovementConfig(playerRef, playerRefComponent, playerComponent, store, movementConfigId);
        logMountDebug(role, "native", "applied",
                "anchor=%s/%s/%s movementConfig=%s",
                anchorX,
                anchorY,
                anchorZ,
                movementConfigId);
        return true;
    }

    private boolean applyTameworkMountedGlideMount(Ref<EntityStore> npcRef,
                                                   Ref<EntityStore> playerRef,
                                                   Role role,
                                                   Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkMountedGlideComponent> glideMountType =
                TameworkMountedGlideComponent.getComponentType();
        ComponentType<EntityStore, TameworkMountedGlideRiderComponent> glideRiderType =
                TameworkMountedGlideRiderComponent.getComponentType();
        ComponentType<EntityStore, NPCMountComponent> npcMountType = NPCMountComponent.getComponentType();
        if (glideMountType == null || glideRiderType == null || npcMountType == null) {
            return failMount(role, "mounted_glide", "component_type_unavailable",
                    "glideMountType=%s glideRiderType=%s npcMountType=%s",
                    glideMountType != null,
                    glideRiderType != null,
                    npcMountType != null);
        }
        UUIDComponent npcUuid = store.getComponent(npcRef, UUIDComponent.getComponentType());
        UUIDComponent riderUuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        NPCEntity npcComponent = store.getComponent(npcRef, NPCEntity.getComponentType());
        NetworkId npcNetworkId = store.getComponent(npcRef, NetworkId.getComponentType());
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        boolean npcTransformReady = npcTransform != null
                && npcTransform.getPosition() != null
                && npcTransform.getRotation() != null;
        if (npcUuid == null || riderUuid == null || playerRefComponent == null || playerComponent == null
                || npcComponent == null || npcNetworkId == null || !npcTransformReady) {
            return failMount(role, "mounted_glide", "missing_required_components",
                    "npcUuid=%s riderUuid=%s playerRef=%s player=%s npc=%s networkId=%s transform=%s",
                    npcUuid != null,
                    riderUuid != null,
                    playerRefComponent != null,
                    playerComponent != null,
                    npcComponent != null,
                    npcNetworkId != null,
                    npcTransformReady);
        }
        MountedGlideStaleStateCleanup.clearInvalidRiderState(
                playerRef,
                playerComponent,
                role,
                glideRiderType,
                glideMountType,
                npcMountType,
                store
        );
        if (hasActiveNativeMount(playerComponent)
                || store.getComponent(npcRef, npcMountType) != null
                || store.getComponent(npcRef, glideMountType) != null
                || store.getComponent(playerRef, glideRiderType) != null) {
            return failMount(role, "mounted_glide", "existing_mount_state",
                    "playerMountEntityId=%s hasNpcMount=%s hasGlideMount=%s hasGlideRider=%s",
                    playerComponent.getMountEntityId(),
                    store.getComponent(npcRef, npcMountType) != null,
                    store.getComponent(npcRef, glideMountType) != null,
                    store.getComponent(playerRef, glideRiderType) != null);
        }
        int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
        if (originalRoleIndex < 0) {
            return failMount(role, "mounted_glide", "missing_original_role_index",
                    "role=%s",
                    safeValue(role.getRoleName()));
        }
        TwMountedGlideConfig config = TwMountedGlideConfig.resolveForRole(role.getRoleName());
        if (config == null) {
            config = new TwMountedGlideConfig();
        }
        String glideState = resolveStringParam(
                role,
                MOUNT_GLIDE_STATE_PARAM,
                TameworkMountedGlideComponent.DEFAULT_GLIDE_STATE
        );
        String glideController = resolveStringParam(
                role,
                MOUNT_GLIDE_CONTROLLER_PARAM,
                TameworkMountedGlideComponent.DEFAULT_GLIDE_CONTROLLER
        );
        TameworkMountedGlideComponent glideMount = new TameworkMountedGlideComponent(riderUuid.getUuid().toString());
        glideMount.setConfigId(config.getId());
        glideMount.setPreviousState(resolveCurrentState(role));
        glideMount.setPreviousSubState(resolveCurrentSubState(role));
        glideMount.setPreviousMotionController(resolveCurrentMotionController(role));
        glideMount.setGlideState(glideState);
        glideMount.setGlideController(glideController);
        glideMount.setMountStartMs(System.currentTimeMillis());
        glideMount.initializePhysicsState(config);
        glideMount.captureAuthoritativePose(
                npcTransform.getPosition().x,
                npcTransform.getPosition().y,
                npcTransform.getPosition().z,
                npcTransform.getRotation().yaw(),
                npcTransform.getRotation().pitch(),
                npcTransform.getRotation().roll()
        );

        TameworkMountedGlideRiderComponent glideRider =
                new TameworkMountedGlideRiderComponent(npcUuid.getUuid().toString());
        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        String movementConfigId = resolveGlideMovementConfigId(role);
        NPCMountComponent npcMount = store.ensureAndGetComponent(npcRef, npcMountType);
        if (npcMount == null) {
            return failMount(role, "mounted_glide", "ensure_npc_mount_failed");
        }
        npcMount.setOriginalRoleIndex(originalRoleIndex);
        npcMount.setOwnerPlayerRef(playerRefComponent);
        npcMount.setAnchor(anchorX, anchorY, anchorZ);
        store.putComponent(npcRef, glideMountType, glideMount);
        store.putComponent(playerRef, glideRiderType, glideRider);
        clearStatusAnimation(npcRef, npcComponent, store);
        applyRideState(npcRef, role, store, glideState);
        role.setActiveMotionController(npcRef, npcComponent, glideController, store);
        boolean movementConfigApplied =
                applyMovementConfig(playerRef, playerRefComponent, playerComponent, store, movementConfigId);
        attachNativeNpcMount(playerComponent, playerRefComponent, npcNetworkId, anchorX, anchorY, anchorZ, npcRef, store);
        logMountDebug(role, "mounted_glide", "applied",
                "npcUuid=%s riderUuid=%s networkId=%s state=%s controller=%s config=%s anchor=%s/%s/%s movementConfig=%s movementConfigApplied=%s",
                npcUuid.getUuid(),
                riderUuid.getUuid(),
                npcNetworkId.getId(),
                glideState,
                glideController,
                config.getId(),
                anchorX,
                anchorY,
                anchorZ,
                movementConfigId,
                movementConfigApplied);
        return true;
    }

    private String resolveGlideMovementConfigId(Role role) {
        String movementConfigId = owner.getRoleStringParam(role, MOUNT_GLIDE_MOVEMENT_CONFIG_PARAM);
        if (movementConfigId == null || movementConfigId.isBlank()) {
            return DEFAULT_MOUNT_GLIDE_MOVEMENT_CONFIG_ID;
        }
        return movementConfigId.trim();
    }

    private void attachNativeNpcMount(Player playerComponent,
                                      PlayerRef playerRefComponent,
                                      NetworkId npcNetworkId,
                                      float anchorX,
                                      float anchorY,
                                      float anchorZ,
                                      Ref<EntityStore> npcRef,
                                      Store<EntityStore> store) {
        playerComponent.setMountEntityId(npcNetworkId.getId());
        playerRefComponent.getPacketHandler().write(new MountNPC(anchorX, anchorY, anchorZ, npcNetworkId.getId()));
        store.tryRemoveComponent(npcRef, Interactable.getComponentType());
        logMountDebug(null, "mounted_glide", "native_attach",
                "networkId=%s anchor=%s/%s/%s",
                npcNetworkId.getId(),
                anchorX,
                anchorY,
                anchorZ);
    }

    static boolean hasActiveNativeMount(@Nonnull Player playerComponent) {
        return playerComponent.getMountEntityId() > 0;
    }

    private boolean failMount(Role role, String stage, String reason) {
        logMountDebug(role, stage, reason, null);
        return false;
    }

    private boolean failMount(Role role, String stage, String reason, String detailFormat, Object... detailArgs) {
        logMountDebug(role, stage, reason, detailFormat, detailArgs);
        return false;
    }

    private void logMountDebug(Role role, String stage, String reason, String detailFormat, Object... detailArgs) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        String detail = "";
        if (detailFormat != null && !detailFormat.isBlank()) {
            detail = " " + String.format(detailFormat, detailArgs);
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkMount debug: stage=%s role=%s reason=%s%s",
                stage,
                role == null ? "<null>" : safeValue(role.getRoleName()),
                reason,
                detail
        );
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        String text = value.toString();
        return text == null || text.isBlank() ? "<blank>" : text;
    }

    private boolean applyTameworkRideMount(Ref<EntityStore> npcRef,
                                           Ref<EntityStore> playerRef,
                                           Role role,
                                           Store<EntityStore> store) {
        ComponentType<EntityStore, MountedComponent> mountedType = MountedComponent.getComponentType();
        ComponentType<EntityStore, TameworkRideMountComponent> rideMountType =
                TameworkRideMountComponent.getComponentType();
        ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderType =
                TameworkRideRiderComponent.getComponentType();
        if (rideMountType == null || rideRiderType == null) {
            return false;
        }
        UUIDComponent npcUuid = store.getComponent(npcRef, UUIDComponent.getComponentType());
        UUIDComponent riderUuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
        PlayerRef playerRefComponent = store.getComponent(playerRef, PlayerRef.getComponentType());
        if (npcUuid == null || riderUuid == null) {
            return false;
        }
        TameworkRideMountComponent existingNpcRide = store.getComponent(npcRef, rideMountType);
        TameworkRideRiderComponent existingRider = store.getComponent(playerRef, rideRiderType);
        if (isSameActiveTameworkRide(existingNpcRide, existingRider, npcUuid, riderUuid)) {
            maybeLogTameworkRideRefreshDebug(role, existingNpcRide, existingRider, npcUuid, riderUuid);
            MountedRidePacketHandler.registerRide(
                    playerRefComponent == null ? null : playerRefComponent.getUuid(),
                    riderUuid.getUuid(),
                    npcUuid.getUuid(),
                    store.getExternalData().getWorld()
            );
            MountedRideClientAttachment.placeRiderAtMountAnchor(store, playerRef, npcRef, existingNpcRide);
            MountedRideClientAttachment.attach(store, playerRef, npcRef, existingNpcRide);
            return true;
        }
        if ((mountedType != null && store.getComponent(playerRef, mountedType) != null)
                || existingNpcRide != null
                || existingRider != null) {
            maybeLogTameworkRideExistingStateDebug(role, existingNpcRide, existingRider, npcUuid, riderUuid);
            clearInvalidExistingRideState(playerRef, mountedType, rideRiderType, rideMountType, store);
        }
        if ((mountedType != null && store.getComponent(playerRef, mountedType) != null)
                || store.getComponent(npcRef, rideMountType) != null
                || store.getComponent(playerRef, rideRiderType) != null) {
            return false;
        }
        NPCEntity npcComponent = store.getComponent(npcRef, NPCEntity.getComponentType());
        Player playerComponent = store.getComponent(playerRef, Player.getComponentType());
        if (npcComponent == null || playerComponent == null) {
            return false;
        }

        float anchorX = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_X_PARAM, 0.0);
        float anchorY = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Y_PARAM, 0.0);
        float anchorZ = (float) owner.getRoleNumberParam(role, DEFAULT_MOUNT_ANCHOR_Z_PARAM, 0.0);
        String rideState = resolveStringParam(role, MOUNT_RIDE_STATE_PARAM, TameworkRideMountComponent.DEFAULT_RIDE_STATE);
        String groundController = resolveStringParam(
                role,
                MOUNT_GROUND_CONTROLLER_PARAM,
                TameworkRideMountComponent.DEFAULT_GROUND_CONTROLLER
        );
        String flightController = resolveStringParam(
                role,
                MOUNT_FLIGHT_CONTROLLER_PARAM,
                TameworkRideMountComponent.DEFAULT_FLIGHT_CONTROLLER
        );
        String previousState = resolveCurrentState(role);
        String previousSubState = resolveCurrentSubState(role);
        String previousController = resolveCurrentMotionController(role);

        TameworkRideMountComponent rideMount = new TameworkRideMountComponent(
                riderUuid.getUuid().toString(),
                previousState,
                previousSubState,
                previousController,
                groundController,
                flightController,
                rideState,
                anchorX,
                anchorY,
                anchorZ,
                System.currentTimeMillis()
        );
        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (npcTransform != null && npcTransform.getRotation() != null) {
            rideMount.captureAuthoritativePose(
                    npcTransform.getPosition().x,
                    npcTransform.getPosition().y,
                    npcTransform.getPosition().z,
                    npcTransform.getRotation().yaw(),
                    npcTransform.getRotation().pitch(),
                    npcTransform.getRotation().roll()
            );
        }
        TameworkRideRiderComponent rideRider = new TameworkRideRiderComponent(npcUuid.getUuid().toString());
        store.putComponent(npcRef, rideMountType, rideMount);
        store.putComponent(playerRef, rideRiderType, rideRider);
        MountedRidePacketHandler.registerRide(
                playerRefComponent == null ? null : playerRefComponent.getUuid(),
                riderUuid.getUuid(),
                npcUuid.getUuid(),
                store.getExternalData().getWorld()
        );
        MountedRideClientAttachment.placeRiderAtMountAnchor(store, playerRef, npcRef, rideMount);
        MountedRideClientAttachment.attach(store, playerRef, npcRef, rideMount);
        clearStatusAnimation(npcRef, npcComponent, store);
        applyRideState(npcRef, role, store, rideState);
        maybeLogTameworkRideMountDebug(role, rideMount, playerRefComponent, anchorX, anchorY, anchorZ);
        return true;
    }

    private boolean isSameActiveTameworkRide(TameworkRideMountComponent existingNpcRide,
                                             TameworkRideRiderComponent existingRider,
                                             UUIDComponent npcUuid,
                                             UUIDComponent riderUuid) {
        if (existingNpcRide == null || existingRider == null || npcUuid == null || riderUuid == null) {
            return false;
        }
        UUID npc = npcUuid.getUuid();
        UUID rider = riderUuid.getUuid();
        return npc != null
                && rider != null
                && rider.toString().equals(existingNpcRide.getRiderUuid())
                && npc.toString().equals(existingRider.getMountUuid());
    }

    private void maybeLogTameworkRideMountDebug(Role role,
                                                TameworkRideMountComponent rideMount,
                                                PlayerRef playerRefComponent,
                                                float anchorX,
                                                float anchorY,
                                                float anchorZ) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: applyMount role=%s rideState=%s previousState=%s previousController=%s " +
                        "groundController=%s flightController=%s anchor=%s/%s/%s riderUuid=%s playerUuid=%s",
                role != null ? role.getRoleName() : "<null>",
                rideMount.getRideState(),
                rideMount.getPreviousState(),
                rideMount.getPreviousMotionController(),
                rideMount.getGroundController(),
                rideMount.getFlightController(),
                anchorX,
                anchorY,
                anchorZ,
                rideMount.getRiderUuid(),
                playerRefComponent == null ? "<none>" : playerRefComponent.getUuid()
        );
    }

    private void maybeLogTameworkRideRefreshDebug(Role role,
                                                  TameworkRideMountComponent existingNpcRide,
                                                  TameworkRideRiderComponent existingRider,
                                                  UUIDComponent npcUuid,
                                                  UUIDComponent riderUuid) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: refreshMount role=%s npcUuid=%s riderUuid=%s existingMountRider=%s existingRiderMount=%s",
                role == null ? "<null>" : role.getRoleName(),
                npcUuid == null ? "<none>" : npcUuid.getUuid(),
                riderUuid == null ? "<none>" : riderUuid.getUuid(),
                existingNpcRide == null ? "<none>" : existingNpcRide.getRiderUuid(),
                existingRider == null ? "<none>" : existingRider.getMountUuid()
        );
    }

    private void maybeLogTameworkRideExistingStateDebug(Role role,
                                                        TameworkRideMountComponent existingNpcRide,
                                                        TameworkRideRiderComponent existingRider,
                                                        UUIDComponent npcUuid,
                                                        UUIDComponent riderUuid) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: existingRideState role=%s npcUuid=%s riderUuid=%s hasNpcRide=%s hasRiderRide=%s " +
                        "existingMountRider=%s existingRiderMount=%s sameRide=%s",
                role == null ? "<null>" : role.getRoleName(),
                npcUuid == null ? "<none>" : npcUuid.getUuid(),
                riderUuid == null ? "<none>" : riderUuid.getUuid(),
                existingNpcRide != null,
                existingRider != null,
                existingNpcRide == null ? "<none>" : existingNpcRide.getRiderUuid(),
                existingRider == null ? "<none>" : existingRider.getMountUuid(),
                isSameActiveTameworkRide(existingNpcRide, existingRider, npcUuid, riderUuid)
        );
    }

    private void clearInvalidExistingRideState(
            Ref<EntityStore> playerRef,
            ComponentType<EntityStore, MountedComponent> mountedType,
            ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderType,
            ComponentType<EntityStore, TameworkRideMountComponent> rideMountType,
            Store<EntityStore> store) {
        TameworkRideRiderComponent rider = store.getComponent(playerRef, rideRiderType);
        if (rider == null) {
            return;
        }
        MountedComponent mounted = mountedType == null ? null : store.getComponent(playerRef, mountedType);
        Ref<EntityStore> existingMountRef = resolveMountRefFromRider(rider, store);
        if (existingMountRef == null && mounted != null) {
            existingMountRef = mounted.getMountedToEntity();
        }
        TameworkRideMountComponent existingMount = existingMountRef == null || !existingMountRef.isValid()
                ? null
                : store.getComponent(existingMountRef, rideMountType);
        boolean stale = existingMountRef == null
                || !existingMountRef.isValid()
                || existingMount == null
                || !rideRiderMatchesMount(rider, existingMountRef, store);
        if (!stale) {
            return;
        }
        maybeLogTameworkRideStaleCleanupDebug(
                rider,
                existingMountRef,
                existingMount,
                existingMountRef == null,
                existingMountRef != null && !existingMountRef.isValid(),
                existingMount == null,
                existingMountRef != null && existingMountRef.isValid()
                        && existingMount != null
                        && !rideRiderMatchesMount(rider, existingMountRef, store)
        );
        if (existingMountRef != null && existingMountRef.isValid() && existingMount != null) {
            NPCEntity npc = store.getComponent(existingMountRef, NPCEntity.getComponentType());
            if (npc != null) {
                restoreTameworkRideNpc(existingMountRef, npc, existingMount, store);
            }
        }
        UUIDComponent playerUuid = store.getComponent(playerRef, UUIDComponent.getComponentType());
        if (playerUuid != null && playerUuid.getUuid() != null) {
            MountedRidePacketHandler.unregisterRide(playerUuid.getUuid());
        }
        MountedRideClientAttachment.detach(store, playerRef);
        store.tryRemoveComponent(playerRef, rideRiderType);
        if (mountedType != null) {
            store.tryRemoveComponent(playerRef, mountedType);
        }
        if (existingMountRef != null && existingMountRef.isValid()) {
            store.tryRemoveComponent(existingMountRef, rideMountType);
        }
    }

    private void maybeLogTameworkRideStaleCleanupDebug(TameworkRideRiderComponent rider,
                                                       Ref<EntityStore> existingMountRef,
                                                       TameworkRideMountComponent existingMount,
                                                       boolean mountMissing,
                                                       boolean mountInvalid,
                                                       boolean mountComponentMissing,
                                                       boolean uuidMismatch) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugRideEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkRide debug: staleRideCleanup riderMountUuid=%s mountRefValid=%s hasMountComponent=%s " +
                        "mountMissing=%s mountInvalid=%s mountComponentMissing=%s uuidMismatch=%s existingMountRider=%s",
                rider == null ? "<none>" : rider.getMountUuid(),
                existingMountRef != null && existingMountRef.isValid(),
                existingMount != null,
                mountMissing,
                mountInvalid,
                mountComponentMissing,
                uuidMismatch,
                existingMount == null ? "<none>" : existingMount.getRiderUuid()
        );
    }

    private void restoreTameworkRideNpc(@Nonnull Ref<EntityStore> mountRef,
                                        @Nonnull NPCEntity npc,
                                        @Nonnull TameworkRideMountComponent mount,
                                        @Nonnull Store<EntityStore> store) {
        clearStatusAnimation(mountRef, npc, store);
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        if (!mount.getPreviousMotionController().isBlank()) {
            role.setActiveMotionController(mountRef, npc, mount.getPreviousMotionController(), store);
        }
        if (mount.getPreviousState().isBlank() || role.getStateSupport() == null) {
            return;
        }
        StateSupport support = role.getStateSupport();
        if (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(mount.getPreviousState()) == StateSupport.NO_STATE) {
            return;
        }
        support.setState(mountRef, mount.getPreviousState(), mount.getPreviousSubState(), store);
    }

    private Ref<EntityStore> resolveMountRefFromRider(TameworkRideRiderComponent rider, Store<EntityStore> store) {
        String expectedMountUuid = rider.getMountUuid();
        if (expectedMountUuid == null || expectedMountUuid.isBlank()) {
            return null;
        }
        try {
            return store.getExternalData().getWorld().getEntityRef(UUID.fromString(expectedMountUuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean rideRiderMatchesMount(TameworkRideRiderComponent rider,
                                          Ref<EntityStore> mountRef,
                                          Store<EntityStore> store) {
        String expectedMountUuid = rider.getMountUuid();
        if (expectedMountUuid == null || expectedMountUuid.isBlank()) {
            return true;
        }
        UUIDComponent uuid = store.getComponent(mountRef, UUIDComponent.getComponentType());
        return uuid != null && expectedMountUuid.equals(uuid.getUuid().toString());
    }

    private String resolveStringParam(Role role, String paramName, String defaultValue) {
        String value = owner.getRoleStringParam(role, paramName);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String resolveCurrentState(Role role) {
        if (role == null || role.getStateSupport() == null) {
            return "";
        }
        String state = role.getStateSupport().getStateName();
        return state == null ? "" : state;
    }

    private String resolveCurrentSubState(Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return "";
        }
        StateSupport support = role.getStateSupport();
        int stateIndex = support.getStateIndex();
        int subStateIndex = support.getSubStateIndex();
        if (stateIndex < 0 || subStateIndex < 0) {
            return "";
        }
        String subState = support.getStateHelper().getSubStateName(stateIndex, subStateIndex);
        return subState == null ? "" : subState;
    }

    private String resolveCurrentMotionController(Role role) {
        if (role == null) {
            return "";
        }
        MotionController controller = role.getActiveMotionController();
        return controller == null ? "" : controller.getType();
    }

    private void applyRideState(Ref<EntityStore> npcRef,
                                Role role,
                                Store<EntityStore> store,
                                String rideState) {
        if (role == null || role.getStateSupport() == null || rideState == null || rideState.isBlank()) {
            return;
        }
        StateSupport support = role.getStateSupport();
        if (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(rideState) == StateSupport.NO_STATE) {
            return;
        }
        String subState = support.getStateHelper() == null ? "" : support.getStateHelper().getDefaultSubState();
        support.setState(npcRef, rideState, subState == null ? "" : subState, store);
    }

    private void clearStatusAnimation(Ref<EntityStore> npcRef,
                                      NPCEntity npcComponent,
                                      Store<EntityStore> store) {
        if (npcRef == null || npcComponent == null || store == null) {
            return;
        }
        npcComponent.playAnimation(npcRef, AnimationSlot.Status, null, store);
    }

    private boolean applyMovementConfig(Ref<EntityStore> playerRef,
                                        PlayerRef playerRefComponent,
                                        Player playerComponent,
                                        Store<EntityStore> store,
                                        String movementConfigId) {
        if (playerRef == null || playerRefComponent == null || playerComponent == null || store == null) {
            return false;
        }
        PhysicsValues playerPhysicsValues = store.getComponent(playerRef, PhysicsValues.getComponentType());
        if (playerPhysicsValues == null) {
            return false;
        }
        String resolvedMovementConfigId = movementConfigId == null ? "" : movementConfigId.trim();
        if (resolvedMovementConfigId.isBlank() || isMovementConfigDisabled(resolvedMovementConfigId)) {
            return false;
        }
        MovementConfig movementConfig = MovementConfig.getAssetMap().getAsset(resolvedMovementConfigId);
        if (movementConfig == null) {
            return false;
        }
        MovementManager movementManager = store.getComponent(playerRef, MovementManager.getComponentType());
        if (movementManager == null) {
            return false;
        }
        movementManager.setDefaultSettings(movementConfig.toPacket(), playerPhysicsValues, playerComponent.getGameMode());
        movementManager.applyDefaultSettings();
        movementManager.update(playerRefComponent.getPacketHandler());
        return true;
    }

    private static boolean isMovementConfigDisabled(String movementConfigId) {
        return switch (movementConfigId.toLowerCase(Locale.ROOT)) {
            case "none", "off", "disabled", "false", "0" -> true;
            default -> false;
        };
    }
}
