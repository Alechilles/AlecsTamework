package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkFlyingCompanionComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import org.joml.Vector3d;

import javax.annotation.Nullable;

/**
 * Sets the desired Tamework flying companion mode on an NPC.
 */
public final class ActionTameworkSetFlyingCompanionMode extends TameworkActionBase {
    private final String mode;
    private final String landingState;
    private final String groundedState;
    private final double descendStep;
    private final long reissueDelayMs;
    private final long groundedStableTicks;
    private final double verticalMovementEpsilon;
    private final int landingTargetSlotIndex;
    private final boolean landingUseInfoProviderPosition;

    public ActionTameworkSetFlyingCompanionMode(BuilderActionTameworkSetFlyingCompanionMode builder,
                                                BuilderSupport support) {
        super(builder);
        this.mode = builder.getMode(support);
        this.landingState = builder.getLandingState(support);
        this.groundedState = builder.getGroundedState(support);
        this.descendStep = builder.getDescendStep(support);
        this.reissueDelayMs = builder.getReissueDelayMs(support);
        this.groundedStableTicks = builder.getGroundedStableTicks(support);
        this.verticalMovementEpsilon = builder.getVerticalMovementEpsilon(support);
        this.landingTargetSlotIndex = builder.getLandingTargetSlotIndex(support);
        this.landingUseInfoProviderPosition = builder.getLandingUseInfoProviderPosition(support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid();
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        if (TameworkFlyingCompanionComponent.MODE_FALL.equalsIgnoreCase(mode)) {
            NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
            if (role == null || npc == null
                    || !role.setActiveMotionController(npcRef, npc, "Walk", store)) {
                return false;
            }
            disableLandingControl(npcRef, store);
            return true;
        }
        ComponentType<EntityStore, TameworkFlyingCompanionComponent> type =
                TameworkFlyingCompanionComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkFlyingCompanionComponent existing = store.getComponent(npcRef, type);
        TameworkFlyingCompanionComponent updated = existing != null
                ? existing.clone()
                : new TameworkFlyingCompanionComponent();
        String previousMode = updated.getMode();
        Vector3d landingPosition = landingUseInfoProviderPosition ? resolveLandingPosition(infoProvider) : null;
        if (landingPosition == null && landingTargetSlotIndex >= 0) {
            landingPosition = resolveMarkedTargetPosition(role, store, landingTargetSlotIndex);
        }
        updated.configure(
                mode,
                landingState,
                groundedState,
                descendStep,
                reissueDelayMs,
                groundedStableTicks,
                verticalMovementEpsilon,
                landingTargetSlotIndex,
                landingPosition
        );
        boolean modeChanged = previousMode == null || !previousMode.equalsIgnoreCase(updated.getMode());
        if (TameworkFlyingCompanionComponent.MODE_HOLD.equals(updated.getMode())) {
            if (isGroundedByController(role) && isGroundedState(role, updated.getGroundedState())) {
                if (modeChanged || !updated.isGroundedPhase()) {
                    updated.enterGroundedPhase(updated.getLastObservedY());
                }
            } else if (modeChanged || !TameworkFlyingCompanionComponent.PHASE_LANDING.equals(updated.getPhase())) {
                updated.enterLandingPhase(updated.getLastObservedY());
            }
        } else if (modeChanged || !TameworkFlyingCompanionComponent.PHASE_FOLLOWING.equals(updated.getPhase())) {
            updated.enterFollowingPhase();
        }
        store.putComponent(npcRef, type, updated);
        return true;
    }

    private static void disableLandingControl(Ref<EntityStore> npcRef,
                                              Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkFlyingCompanionComponent> type =
                TameworkFlyingCompanionComponent.getComponentType();
        if (type == null) {
            return;
        }
        TameworkFlyingCompanionComponent existing = store.getComponent(npcRef, type);
        if (existing == null) {
            return;
        }
        TameworkFlyingCompanionComponent updated = existing.clone();
        updated.setMode(TameworkFlyingCompanionComponent.MODE_FOLLOW);
        updated.enterFollowingPhase();
        store.putComponent(npcRef, type, updated);
    }

    @Nullable
    private static Vector3d resolveMarkedTargetPosition(@Nullable Role role,
                                                        @Nullable Store<EntityStore> store,
                                                        int targetSlotIndex) {
        if (role == null || store == null || targetSlotIndex < 0 || role.getMarkedEntitySupport() == null) {
            return null;
        }
        Ref<EntityStore> targetRef = role.getMarkedEntitySupport().getMarkedEntityRef(targetSlotIndex);
        if (targetRef == null || !targetRef.isValid()) {
            return null;
        }
        TransformComponent transform = store.getComponent(targetRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        Vector3d position = transform.getPosition();
        return new Vector3d(position.x, position.y, position.z);
    }

    @Nullable
    private static Vector3d resolveLandingPosition(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null || !infoProvider.hasPosition()) {
            return null;
        }
        IPositionProvider provider = infoProvider.getPositionProvider();
        if (provider == null || !provider.hasPosition()) {
            return null;
        }
        double x = provider.getX();
        double y = provider.getY();
        double z = provider.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        return new Vector3d(x, y, z);
    }

    private boolean isGroundedByController(Role role) {
        if (role == null) {
            return false;
        }
        MotionController controller = role.getActiveMotionController();
        return controller != null && controller.onGround();
    }

    private boolean isGroundedState(Role role, String groundedState) {
        if (role == null || role.getStateSupport() == null || groundedState == null || groundedState.isBlank()) {
            return false;
        }
        String state = groundedState;
        String subState = null;
        if (state.contains(".")) {
            String[] parts = state.split("\\.", 2);
            state = parts[0];
            subState = parts[1];
        }
        if (subState == null || subState.isBlank()) {
            return role.getStateSupport().inState(state, "");
        }
        return role.getStateSupport().inState(state, subState);
    }
}
