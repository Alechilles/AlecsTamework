package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
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

    boolean assignFamilyFlock(Ref<EntityStore> childRef,
                              @Nullable Ref<EntityStore> parentARef,
                              @Nullable Ref<EntityStore> parentBRef,
                              Store<EntityStore> store) {
        if (store == null) {
            return false;
        }
        Ref<EntityStore> leaderRef = resolveLeaderRef(parentARef, parentBRef);
        if (leaderRef == null || !leaderRef.isValid()) {
            return false;
        }
        Ref<EntityStore> flockRef = resolveOrCreateFlockRef(leaderRef, parentARef, parentBRef, store);
        if (flockRef == null || !flockRef.isValid()) {
            return false;
        }
        if (!ensureJoined(leaderRef, flockRef, store)) {
            return false;
        }
        // Keep parents in the same flock but do not force their behavior state.
        ensureJoined(parentARef, flockRef, store);
        ensureJoined(parentBRef, flockRef, store);
        if (!ensureJoined(childRef, flockRef, store)) {
            return false;
        }
        return applyFollowerTargetAndState(childRef, leaderRef, store);
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

    @Nullable
    private Ref<EntityStore> resolveOrCreateFlockRef(Ref<EntityStore> leaderRef,
                                                     @Nullable Ref<EntityStore> parentARef,
                                                     @Nullable Ref<EntityStore> parentBRef,
                                                     Store<EntityStore> store) {
        Ref<EntityStore> flockRef = resolveExistingFlockRef(leaderRef, store);
        if (flockRef != null && flockRef.isValid()) {
            return flockRef;
        }
        flockRef = resolveExistingFlockRef(parentARef, store);
        if (flockRef != null && flockRef.isValid()) {
            return flockRef;
        }
        flockRef = resolveExistingFlockRef(parentBRef, store);
        if (flockRef != null && flockRef.isValid()) {
            return flockRef;
        }

        NPCEntity leaderNpc = store.getComponent(leaderRef, NPCEntity.getComponentType());
        Role leaderRole = leaderNpc != null ? leaderNpc.getRole() : null;
        if (leaderRole == null) {
            return null;
        }
        return FlockPlugin.createFlock(store, leaderRole);
    }

    @Nullable
    private Ref<EntityStore> resolveExistingFlockRef(@Nullable Ref<EntityStore> memberRef,
                                                     Store<EntityStore> store) {
        if (memberRef == null || !memberRef.isValid()) {
            return null;
        }
        Ref<EntityStore> flockRef = FlockPlugin.getFlockReference(memberRef, store);
        return flockRef != null && flockRef.isValid() ? flockRef : null;
    }

    private boolean ensureJoined(@Nullable Ref<EntityStore> memberRef,
                                 Ref<EntityStore> flockRef,
                                 Store<EntityStore> store) {
        if (memberRef == null || !memberRef.isValid() || flockRef == null || !flockRef.isValid() || store == null) {
            return false;
        }
        FlockMembership membership = store.getComponent(memberRef, FlockMembership.getComponentType());
        Ref<EntityStore> currentFlockRef = membership != null ? membership.getFlockRef() : null;
        if (currentFlockRef != null && currentFlockRef.equals(flockRef)) {
            return true;
        }
        if (!FlockMembershipSystems.canJoinFlock(memberRef, flockRef, store)) {
            return false;
        }
        FlockMembershipSystems.join(memberRef, flockRef, store);
        return true;
    }

    private boolean applyFollowerTargetAndState(@Nullable Ref<EntityStore> followerRef,
                                                Ref<EntityStore> leaderRef,
                                                Store<EntityStore> store) {
        if (followerRef == null || !followerRef.isValid() || followerRef.equals(leaderRef)) {
            return false;
        }
        NPCEntity followerNpc = store.getComponent(followerRef, NPCEntity.getComponentType());
        if (followerNpc == null) {
            return false;
        }
        Role role = followerNpc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return false;
        }
        role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, leaderRef);
        return setFollowState(followerRef, followerNpc, store);
    }

    private boolean setFollowState(Ref<EntityStore> followerRef,
                                   NPCEntity followerNpc,
                                   Store<EntityStore> store) {
        Role role = followerNpc.getRole();
        if (role == null || role.getStateSupport() == null) {
            return false;
        }
        StateSupport stateSupport = role.getStateSupport();
        String resolvedState = resolveFollowerState(stateSupport);
        if (resolvedState == null) {
            return false;
        }
        String subState = "";
        if (stateSupport.getStateHelper() != null) {
            String defaultSubState = stateSupport.getStateHelper().getDefaultSubState();
            if (defaultSubState != null && !defaultSubState.isBlank()) {
                subState = defaultSubState;
            }
        }
        stateSupport.setState(followerRef, resolvedState, subState, store);
        return true;
    }

    @Nullable
    private String resolveFollowerState(StateSupport stateSupport) {
        if (stateSupport == null || stateSupport.getStateHelper() == null) {
            return null;
        }
        int flockFollowStateIndex = stateSupport.getStateHelper().getStateIndex(FLOCK_FOLLOW_STATE);
        if (flockFollowStateIndex != StateSupport.NO_STATE) {
            return FLOCK_FOLLOW_STATE;
        }
        return null;
    }
}
