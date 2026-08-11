package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Applies nonessential command-target and state effects after relocation commit has started. */
final class CommandRelocationPostMoveEffects {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";

    void apply(World world,
               NPCEntity npc,
               Ref<EntityStore> npcRef,
               Store<EntityStore> store,
               PendingRelocation pending,
               Consumer<String> failed) {
        Role role;
        try {
            role = npc.getRole();
        } catch (RuntimeException | LinkageError exception) {
            report(failed, "role-resolution");
            return;
        }
        applyLockedTargetClear(role, npcRef, store, pending, failed);
        applyOwnerTarget(world, role, npcRef, store, pending, failed);
        applyState(role, npcRef, store, pending, failed);
    }

    private static void applyLockedTargetClear(@Nullable Role role,
                                               Ref<EntityStore> npcRef,
                                               Store<EntityStore> store,
                                               PendingRelocation pending,
                                               Consumer<String> failed) {
        if (!pending.clearLockedTarget || role == null) {
            return;
        }
        try {
            MarkedEntitySupport markedEntity = NpcSupportAccess.markedEntity(role, npcRef, store);
            if (markedEntity != null) {
                markedEntity.setMarkedEntity("LockedTarget", null);
            }
        } catch (RuntimeException | LinkageError exception) {
            report(failed, "locked-target-clear");
        }
    }

    private static void applyOwnerTarget(World world,
                                         @Nullable Role role,
                                         Ref<EntityStore> npcRef,
                                         Store<EntityStore> store,
                                         PendingRelocation pending,
                                         Consumer<String> failed) {
        if (!pending.assignOwnerAsMasterTarget || pending.ownerUuid == null || role == null) {
            return;
        }
        try {
            Ref<EntityStore> ownerRef = world.getEntityRef(pending.ownerUuid);
            MarkedEntitySupport markedEntity = NpcSupportAccess.markedEntity(role, npcRef, store);
            if (ownerRef != null && ownerRef.isValid() && markedEntity != null) {
                markedEntity.setMarkedEntity(MASTER_TARGET_SLOT, ownerRef);
            }
        } catch (RuntimeException | LinkageError exception) {
            report(failed, "owner-target-assign");
        }
    }

    private static void applyState(@Nullable Role role,
                                   Ref<EntityStore> npcRef,
                                   Store<EntityStore> store,
                                   PendingRelocation pending,
                                   Consumer<String> failed) {
        if (role == null || pending.state == null || pending.state.isBlank()) {
            return;
        }
        try {
            StateSupport support = NpcSupportAccess.state(role, npcRef, store);
            if (support == null) {
                return;
            }
            String resolvedSubState = pending.subState;
            if (support.getStateHelper() != null) {
                int stateIndex = support.getStateHelper().getStateIndex(pending.state);
                if (stateIndex == StateSupport.NO_STATE) {
                    return;
                }
                if (resolvedSubState == null || resolvedSubState.isBlank()) {
                    resolvedSubState = support.getStateHelper().getDefaultSubState();
                } else if (support.getStateHelper().getSubStateIndex(stateIndex, resolvedSubState)
                        == StateSupport.NO_STATE) {
                    return;
                }
            }
            support.setState(
                    npcRef, pending.state, resolvedSubState == null ? "" : resolvedSubState, store
            );
        } catch (RuntimeException | LinkageError exception) {
            report(failed, "state-apply");
        }
    }

    private static void report(Consumer<String> failed, String effect) {
        try {
            failed.accept(effect);
        } catch (RuntimeException | LinkageError ignored) {
            // Population commit has already started; diagnostics remain best effort.
        }
    }
}
