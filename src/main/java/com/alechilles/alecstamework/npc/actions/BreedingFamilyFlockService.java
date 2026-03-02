package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

/**
 * Assigns parent-offspring breeding groups into vanilla flock membership.
 */
final class BreedingFamilyFlockService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    boolean assignFamilyFlock(Ref<EntityStore> childRef,
                              @Nullable Ref<EntityStore> parentARef,
                              @Nullable Ref<EntityStore> parentBRef,
                              Store<EntityStore> store) {
        if (store == null) {
            return false;
        }
        ParentFlockContext parentA = resolveParentFlockContext(parentARef, store);
        ParentFlockContext parentB = resolveParentFlockContext(parentBRef, store);
        FlockAssignmentPlan plan = buildAssignmentPlan(parentA, parentB, store);
        if (plan == null || plan.leaderRef() == null || !plan.leaderRef().isValid() || plan.flockRef() == null || !plan.flockRef().isValid()) {
            return false;
        }
        if (plan.detachFollowerParentA()) {
            leaveFlock(parentARef, store);
        }
        if (plan.detachFollowerParentB()) {
            leaveFlock(parentBRef, store);
        }
        if (!ensureJoined(plan.leaderRef(), plan.flockRef(), store)) {
            return false;
        }
        // Keep parents in the same flock but do not force their behavior state.
        ensureJoined(parentARef, plan.flockRef(), store);
        ensureJoined(parentBRef, plan.flockRef(), store);
        if (!ensureJoined(childRef, plan.flockRef(), store)) {
            return false;
        }
        return applyFollowerLeaderTarget(childRef, plan.leaderRef(), store);
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

    private FlockAssignmentPlan buildAssignmentPlan(ParentFlockContext parentA,
                                                    ParentFlockContext parentB,
                                                    Store<EntityStore> store) {
        if (parentA != null && parentA.isLeader() && parentB != null && parentB.isLeader()) {
            ParentFlockContext selected = ThreadLocalRandom.current().nextBoolean() ? parentA : parentB;
            return new FlockAssignmentPlan(selected.ref(), selected.flockRef(), false, false);
        }
        if (parentA != null && parentA.isLeader()) {
            return new FlockAssignmentPlan(parentA.ref(), parentA.flockRef(), false, false);
        }
        if (parentB != null && parentB.isLeader()) {
            return new FlockAssignmentPlan(parentB.ref(), parentB.flockRef(), false, false);
        }

        boolean parentAFollower = parentA != null && parentA.isFollower();
        boolean parentBFollower = parentB != null && parentB.isFollower();
        Ref<EntityStore> leaderRef = resolveLeaderRef(parentA != null ? parentA.ref() : null, parentB != null ? parentB.ref() : null);
        if (leaderRef == null || !leaderRef.isValid()) {
            return null;
        }
        Ref<EntityStore> newFlockRef = createFlockForLeader(leaderRef, store);
        if (newFlockRef == null || !newFlockRef.isValid()) {
            return null;
        }
        return new FlockAssignmentPlan(leaderRef, newFlockRef, parentAFollower, parentBFollower);
    }

    @Nullable
    private Ref<EntityStore> createFlockForLeader(Ref<EntityStore> leaderRef, Store<EntityStore> store) {
        NPCEntity leaderNpc = store.getComponent(leaderRef, NPCEntity.getComponentType());
        Role leaderRole = leaderNpc != null ? leaderNpc.getRole() : null;
        if (leaderRole == null) {
            return null;
        }
        return FlockPlugin.createFlock(store, leaderRole);
    }

    @Nullable
    private ParentFlockContext resolveParentFlockContext(@Nullable Ref<EntityStore> memberRef,
                                                         Store<EntityStore> store) {
        if (memberRef == null || !memberRef.isValid()) {
            return null;
        }
        FlockMembership membership = store.getComponent(memberRef, FlockMembership.getComponentType());
        if (membership == null) {
            return new ParentFlockContext(memberRef, null, false, false);
        }
        Ref<EntityStore> flockRef = membership.getFlockRef();
        if (flockRef == null || !flockRef.isValid()) {
            return new ParentFlockContext(memberRef, null, false, false);
        }
        boolean leader = membership.getMembershipType() != null && membership.getMembershipType().isActingAsLeader();
        boolean follower = membership.getMembershipType() != null && !membership.getMembershipType().isActingAsLeader();
        return new ParentFlockContext(memberRef, flockRef, leader, follower);
    }

    private void leaveFlock(@Nullable Ref<EntityStore> memberRef, Store<EntityStore> store) {
        if (memberRef == null || !memberRef.isValid() || store == null) {
            return;
        }
        ComponentType<EntityStore, FlockMembership> flockMembershipType = FlockMembership.getComponentType();
        if (flockMembershipType == null) {
            return;
        }
        store.tryRemoveComponent(memberRef, flockMembershipType);
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

    private boolean applyFollowerLeaderTarget(@Nullable Ref<EntityStore> followerRef,
                                              Ref<EntityStore> leaderRef,
                                              Store<EntityStore> store) {
        if (followerRef == null || !followerRef.isValid() || followerRef.equals(leaderRef)) {
            return true;
        }
        NPCEntity followerNpc = store.getComponent(followerRef, NPCEntity.getComponentType());
        if (followerNpc == null) {
            return true;
        }
        Role role = followerNpc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return true;
        }
        role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, leaderRef);
        return true;
    }

    private record ParentFlockContext(Ref<EntityStore> ref,
                                      @Nullable Ref<EntityStore> flockRef,
                                      boolean isLeader,
                                      boolean isFollower) {
    }

    private record FlockAssignmentPlan(Ref<EntityStore> leaderRef,
                                       Ref<EntityStore> flockRef,
                                       boolean detachFollowerParentA,
                                       boolean detachFollowerParentB) {
    }
}
