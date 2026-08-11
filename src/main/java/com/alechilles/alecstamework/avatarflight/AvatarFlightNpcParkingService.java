package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.compat.HytaleMountedComponentAccess;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Captures, parks, and restores the same source NPC entity used by avatar flight. */
public final class AvatarFlightNpcParkingService {

    @Nullable
    public AvatarFlightSourceComponent capture(@Nonnull Store<EntityStore> store,
                                               @Nonnull Ref<EntityStore> npcRef,
                                               @Nonnull Role role,
                                               @Nonnull String riderUuid,
                                               int originalRoleIndex) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (npc == null || transform == null || transform.getPosition() == null || transform.getRotation() == null) {
            return null;
        }
        AvatarFlightSourceComponent source = new AvatarFlightSourceComponent(
                riderUuid, role.getRoleName(), originalRoleIndex);
        StateSupport state = NpcSupportAccess.state(role, npcRef, store);
        source.setPreviousState(state == null ? "" : state.getStateName());
        source.setPreviousSubState(resolveSubState(state));
        MotionController controller = role.getActiveMotionController();
        source.setPreviousMotionController(controller == null ? "" : controller.getType());
        source.captureOrigin(
                transform.getPosition().x,
                transform.getPosition().y,
                transform.getPosition().z,
                transform.getRotation().yaw(),
                transform.getRotation().pitch(),
                transform.getRotation().roll()
        );
        source.setWasInteractable(store.getComponent(npcRef, Interactable.getComponentType()) != null);
        source.setWasVisible(store.getComponent(npcRef, EntityTrackerSystems.Visible.getComponentType()) != null);
        source.setWasFrozen(store.getComponent(npcRef, Frozen.getComponentType()) != null);
        source.setWasIntangible(store.getComponent(npcRef, Intangible.getComponentType()) != null);
        source.setWasInvulnerable(store.getComponent(npcRef, Invulnerable.getComponentType()) != null);
        return source;
    }

    public boolean park(@Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> npcRef,
                        @Nonnull Ref<EntityStore> riderRef,
                        @Nonnull Role role,
                        @Nonnull AvatarFlightSourceComponent source,
                        int emptyRoleIndex) {
        if (!npcRef.isValid() || !riderRef.isValid() || emptyRoleIndex < 0) {
            return false;
        }
        // Empty_Role has no persistent population ownership. Detach the parked
        // companion so the world-spawn despawner cannot remove it before the
        // avatar-flight teardown restores its original role.
        RoleChangeSystem.requestRoleChange(
                npcRef, role, emptyRoleIndex, false, null, null, true, store);
        ensure(store, npcRef, Frozen.getComponentType());
        ensure(store, npcRef, Intangible.getComponentType());
        ensure(store, npcRef, Invulnerable.getComponentType());
        removeIfPresent(store, npcRef, Interactable.getComponentType());
        removeIfPresent(store, npcRef, EntityTrackerSystems.Visible.getComponentType());
        store.putComponent(npcRef, MountedComponent.getComponentType(),
                HytaleMountedComponentAccess.createEntityMount(
                        riderRef, 0.0F, 0.0F, 0.0F, MountController.Minecart));
        source.setPhase(AvatarFlightMountPhase.ACTIVE);
        return true;
    }

    public void restore(@Nonnull Store<EntityStore> store,
                        @Nonnull Ref<EntityStore> npcRef,
                        @Nonnull AvatarFlightSourceComponent source,
                        double x,
                        double y,
                        double z,
                        float yaw) {
        if (!npcRef.isValid()) {
            return;
        }
        removeIfPresent(store, npcRef, MountedComponent.getComponentType());
        moveNpc(store, npcRef, x, y, z, yaw, 0.0f, 0.0f);
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null && source.getOriginalRoleIndex() >= 0) {
            npc.playAnimation(npcRef, AnimationSlot.Movement, null, store);
            RoleChangeSystem.requestRoleChange(
                    npcRef,
                    npc.getRole(),
                    source.getOriginalRoleIndex(),
                    false,
                    source.getPreviousState().isBlank() ? "Idle" : source.getPreviousState(),
                    source.getPreviousSubState().isBlank() ? null : source.getPreviousSubState(),
                    store
            );
            Role restoredRole = npc.getRole();
            if (restoredRole != null && !source.getPreviousMotionController().isBlank()) {
                restoredRole.setActiveMotionController(
                        npcRef, npc, source.getPreviousMotionController(), store);
            }
        }
        if (source.wasInteractable()) {
            ensure(store, npcRef, Interactable.getComponentType());
        }
        restorePresence(store, npcRef, EntityTrackerSystems.Visible.getComponentType(), source.wasVisible());
        restorePresence(store, npcRef, Frozen.getComponentType(), source.wasFrozen());
        restorePresence(store, npcRef, Intangible.getComponentType(), source.wasIntangible());
        restorePresence(store, npcRef, Invulnerable.getComponentType(), source.wasInvulnerable());
    }

    private static void moveNpc(Store<EntityStore> store,
                                Ref<EntityStore> npcRef,
                                double x,
                                double y,
                                double z,
                                float yaw,
                                float pitch,
                                float roll) {
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null || transform.getRotation() == null) {
            return;
        }
        transform.getPosition().x = x;
        transform.getPosition().y = y;
        transform.getPosition().z = z;
        transform.getRotation().setYaw(yaw);
        transform.getRotation().setPitch(pitch);
        transform.getRotation().setRoll(roll);
        store.putComponent(npcRef, TransformComponent.getComponentType(), transform);
    }

    private static String resolveSubState(@Nullable StateSupport state) {
        if (state == null || state.getStateHelper() == null
                || state.getStateIndex() < 0 || state.getSubStateIndex() < 0) {
            return "";
        }
        String value = state.getStateHelper().getSubStateName(state.getStateIndex(), state.getSubStateIndex());
        return value == null ? "" : value;
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> void removeIfPresent(
            Store<EntityStore> store, Ref<EntityStore> ref, ComponentType<EntityStore, T> type) {
        if (type != null && store.getComponent(ref, type) != null) store.tryRemoveComponent(ref, type);
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> void ensure(
            Store<EntityStore> store, Ref<EntityStore> ref, ComponentType<EntityStore, T> type) {
        if (type != null && store.getComponent(ref, type) == null) store.ensureAndGetComponent(ref, type);
    }

    private static <T extends com.hypixel.hytale.component.Component<EntityStore>> void restorePresence(
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            ComponentType<EntityStore, T> type,
            boolean present) {
        if (present) {
            ensure(store, ref, type);
        } else {
            removeIfPresent(store, ref, type);
        }
    }
}
