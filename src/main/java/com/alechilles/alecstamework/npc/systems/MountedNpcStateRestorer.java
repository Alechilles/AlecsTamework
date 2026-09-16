package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import javax.annotation.Nonnull;

/** Restores state captured by the mounted ride and glide systems on their current world thread. */
final class MountedNpcStateRestorer {
    private MountedNpcStateRestorer() {
    }

    static void restore(@Nonnull Ref<EntityStore> mountRef,
                        @Nonnull NPCEntity npc,
                        @Nonnull String previousMotionController,
                        @Nonnull String previousState,
                        @Nonnull String previousSubState,
                        @Nonnull Store<EntityStore> store) {
        Role role = npc.getRole();
        if (role == null) {
            return;
        }
        npc.playAnimation(mountRef, AnimationSlot.Movement, null, store);
        if (!previousMotionController.isBlank()) {
            role.setActiveMotionController(mountRef, npc, previousMotionController, store);
        }
        if (previousState.isBlank()) {
            return;
        }
        StateSupport support = NpcSupportAccess.state(role, mountRef, store);
        if (support == null
                || (support.getStateHelper() != null
                && support.getStateHelper().getStateIndex(previousState) == StateSupport.NO_STATE)) {
            return;
        }
        support.setState(mountRef, previousState, previousSubState, store);
    }
}
