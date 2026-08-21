package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkFlyingCompanionComponent;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
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
    private final MotionControllerSwitcher motionControllerSwitcher;

    public ActionTameworkSetFlyingCompanionMode(BuilderActionTameworkSetFlyingCompanionMode builder,
                                                BuilderSupport support) {
        this(builder, support, (role, ref, npc, controller, accessor) ->
                role.setActiveMotionController(ref, npc, controller, accessor));
    }

    ActionTameworkSetFlyingCompanionMode(BuilderActionTameworkSetFlyingCompanionMode builder,
                                         BuilderSupport support,
                                         MotionControllerSwitcher motionControllerSwitcher) {
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
        this.motionControllerSwitcher = motionControllerSwitcher;
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
                    || !motionControllerSwitcher.activate(role, npcRef, npc, "Walk", store)) {
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
            landingPosition = resolveMarkedTargetPosition(npcRef, role, store, landingTargetSlotIndex);
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
            MotionController controller = role != null ? role.getActiveMotionController() : null;
            boolean groundedByController = controller != null && controller.onGround();
            if (groundedByController && isGroundedState(npcRef, role, store, updated.getGroundedState())) {
                if (LandingContactTransition.canConfirm(controller.getType(), true)) {
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc == null || !LandingContactTransition.confirm(
                            controller.getType(),
                            true,
                            () -> motionControllerSwitcher.activate(role, npcRef, npc, "Walk", store)
                    )) {
                        return false;
                    }
                }
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
    private static Vector3d resolveMarkedTargetPosition(@Nullable Ref<EntityStore> npcRef,
                                                        @Nullable Role role,
                                                        @Nullable Store<EntityStore> store,
                                                        int targetSlotIndex) {
        MarkedEntitySupport markedEntitySupport = NpcSupportAccess.markedEntity(role, npcRef, store);
        if (role == null || store == null || targetSlotIndex < 0 || markedEntitySupport == null) {
            return null;
        }
        Ref<EntityStore> targetRef = markedEntitySupport.getMarkedEntityRef(targetSlotIndex);
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

    private boolean isGroundedState(@Nullable Ref<EntityStore> npcRef,
                                    Role role,
                                    Store<EntityStore> store,
                                    String groundedState) {
        StateSupport stateSupport = NpcSupportAccess.state(role, npcRef, store);
        if (role == null || stateSupport == null || groundedState == null || groundedState.isBlank()) {
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
            return stateSupport.inState(state, "");
        }
        return stateSupport.inState(state, subState);
    }

    @FunctionalInterface
    interface MotionControllerSwitcher {
        boolean activate(Role role,
                         Ref<EntityStore> ref,
                         NPCEntity npc,
                         String controller,
                         ComponentAccessor<EntityStore> accessor);
    }
}
