package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkFlockFollowComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Assigns parent-offspring breeding groups into a lightweight follow flock.
 */
final class BreedingFamilyFlockService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final String FOLLOW_STATE = "Follow";

    void assignFamilyFlock(Ref<EntityStore> childRef,
                           @Nullable Ref<EntityStore> parentARef,
                           @Nullable Ref<EntityStore> parentBRef,
                           Store<EntityStore> store) {
        if (store == null || childRef == null || !childRef.isValid()) {
            return;
        }
        ComponentType<EntityStore, TameworkFlockFollowComponent> flockType = TameworkFlockFollowComponent.getComponentType();
        if (flockType == null) {
            return;
        }
        Ref<EntityStore> leaderRef = resolveLeaderRef(parentARef, parentBRef);
        if (leaderRef == null || !leaderRef.isValid()) {
            return;
        }
        NPCEntity leaderNpc = store.getComponent(leaderRef, NPCEntity.getComponentType());
        UUID leaderUuid = leaderNpc != null ? leaderNpc.getUuid() : null;
        if (leaderUuid == null) {
            return;
        }

        long formedAtMs = System.currentTimeMillis();
        String flockId = UUID.randomUUID().toString();
        store.putComponent(
                leaderRef,
                flockType,
                new TameworkFlockFollowComponent(flockId, leaderUuid, true, formedAtMs)
        );

        assignFollower(parentARef, leaderRef, leaderUuid, flockId, formedAtMs, flockType, store);
        assignFollower(parentBRef, leaderRef, leaderUuid, flockId, formedAtMs, flockType, store);
        assignFollower(childRef, leaderRef, leaderUuid, flockId, formedAtMs, flockType, store);
    }

    @Nullable
    private Ref<EntityStore> resolveLeaderRef(@Nullable Ref<EntityStore> parentARef,
                                              @Nullable Ref<EntityStore> parentBRef) {
        if (parentARef != null && parentARef.isValid()) {
            return parentARef;
        }
        if (parentBRef != null && parentBRef.isValid()) {
            return parentBRef;
        }
        return null;
    }

    private void assignFollower(@Nullable Ref<EntityStore> followerRef,
                                Ref<EntityStore> leaderRef,
                                UUID leaderUuid,
                                String flockId,
                                long formedAtMs,
                                ComponentType<EntityStore, TameworkFlockFollowComponent> flockType,
                                Store<EntityStore> store) {
        if (followerRef == null || !followerRef.isValid() || followerRef.equals(leaderRef)) {
            return;
        }
        store.putComponent(
                followerRef,
                flockType,
                new TameworkFlockFollowComponent(flockId, leaderUuid, false, formedAtMs)
        );
        applyFollowerTargetAndState(followerRef, leaderRef, store);
    }

    private void applyFollowerTargetAndState(Ref<EntityStore> followerRef,
                                             Ref<EntityStore> leaderRef,
                                             Store<EntityStore> store) {
        NPCEntity followerNpc = store.getComponent(followerRef, NPCEntity.getComponentType());
        if (followerNpc == null) {
            return;
        }
        Role role = followerNpc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return;
        }
        role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, leaderRef);
        setFollowState(followerRef, followerNpc, store);
    }

    private void setFollowState(Ref<EntityStore> followerRef,
                                NPCEntity followerNpc,
                                Store<EntityStore> store) {
        Role role = followerNpc.getRole();
        if (role == null || role.getStateSupport() == null) {
            return;
        }
        StateSupport stateSupport = role.getStateSupport();
        String subState = "";
        if (stateSupport.getStateHelper() != null) {
            int followStateIndex = stateSupport.getStateHelper().getStateIndex(FOLLOW_STATE);
            if (followStateIndex == StateSupport.NO_STATE) {
                return;
            }
            String defaultSubState = stateSupport.getStateHelper().getDefaultSubState();
            if (defaultSubState != null && !defaultSubState.isBlank()) {
                subState = defaultSubState;
            }
        }
        stateSupport.setState(followerRef, FOLLOW_STATE, subState, store);
    }
}
