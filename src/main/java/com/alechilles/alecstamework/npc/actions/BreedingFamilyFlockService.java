package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import javax.annotation.Nullable;

/**
 * Assigns parent-offspring breeding groups into a vanilla flock and follower behavior state.
 */
final class BreedingFamilyFlockService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private static final String FLOCK_FOLLOW_STATE = "FlockFollow";
    private static final String FALLBACK_FOLLOW_STATE = "Follow";

    void assignFamilyFlock(Ref<EntityStore> childRef,
                           @Nullable Ref<EntityStore> parentARef,
                           @Nullable Ref<EntityStore> parentBRef,
                           Store<EntityStore> store) {
        if (store == null) {
            return;
        }
        Ref<EntityStore> leaderRef = resolveLeaderRef(parentARef, parentBRef);
        if (leaderRef == null || !leaderRef.isValid()) {
            return;
        }
        NPCEntity leaderNpc = store.getComponent(leaderRef, NPCEntity.getComponentType());
        Role leaderRole = leaderNpc != null ? leaderNpc.getRole() : null;
        if (leaderRole == null) {
            return;
        }
        Ref<EntityStore> flockRef = FlockPlugin.createFlock(store, leaderRole);
        if (flockRef == null || !flockRef.isValid()) {
            return;
        }

        joinFlockMember(leaderRef, flockRef, store);
        joinFollower(parentARef, leaderRef, flockRef, store);
        joinFollower(parentBRef, leaderRef, flockRef, store);
        joinFollower(childRef, leaderRef, flockRef, store);
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

    private void joinFollower(@Nullable Ref<EntityStore> followerRef,
                              Ref<EntityStore> leaderRef,
                              Ref<EntityStore> flockRef,
                              Store<EntityStore> store) {
        if (followerRef == null || !followerRef.isValid() || followerRef.equals(leaderRef)) {
            return;
        }
        joinFlockMember(followerRef, flockRef, store);
        applyFollowerTargetAndState(followerRef, leaderRef, store);
    }

    private void joinFlockMember(Ref<EntityStore> memberRef,
                                 Ref<EntityStore> flockRef,
                                 Store<EntityStore> store) {
        if (memberRef == null || !memberRef.isValid() || flockRef == null || !flockRef.isValid() || store == null) {
            return;
        }
        FlockMembershipSystems.join(memberRef, flockRef, store);
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
        String resolvedState = resolveFollowerState(stateSupport);
        if (resolvedState == null) {
            return;
        }
        String subState = "";
        if (stateSupport.getStateHelper() != null) {
            String defaultSubState = stateSupport.getStateHelper().getDefaultSubState();
            if (defaultSubState != null && !defaultSubState.isBlank()) {
                subState = defaultSubState;
            }
        }
        stateSupport.setState(followerRef, resolvedState, subState, store);
    }

    @Nullable
    private String resolveFollowerState(StateSupport stateSupport) {
        if (stateSupport == null || stateSupport.getStateHelper() == null) {
            return FALLBACK_FOLLOW_STATE;
        }
        int flockFollowStateIndex = stateSupport.getStateHelper().getStateIndex(FLOCK_FOLLOW_STATE);
        if (flockFollowStateIndex != StateSupport.NO_STATE) {
            return FLOCK_FOLLOW_STATE;
        }
        int followStateIndex = stateSupport.getStateHelper().getStateIndex(FALLBACK_FOLLOW_STATE);
        if (followStateIndex != StateSupport.NO_STATE) {
            return FALLBACK_FOLLOW_STATE;
        }
        return null;
    }
}
