package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.List;
import javax.annotation.Nullable;

/** Removes the current sensor target from the NPC combat evaluator's hostile memory. */
public final class ActionTameworkForgetHostileTarget extends TameworkActionBase {
    public ActionTameworkForgetHostileTarget(BuilderActionTameworkForgetHostileTarget builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null
                && npcRef.isValid()
                && store != null
                && resolveTarget(infoProvider) != null
                && store.getComponent(npcRef, TargetMemory.getComponentType()) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        Ref<EntityStore> targetRef = resolveTarget(infoProvider);
        if (npcRef == null || !npcRef.isValid() || store == null || targetRef == null) {
            return false;
        }
        TargetMemory memory = store.getComponent(npcRef, TargetMemory.getComponentType());
        if (memory == null) {
            return false;
        }
        removeHostileTarget(memory, targetRef.getIndex());
        return true;
    }

    static boolean removeHostileTarget(@Nullable TargetMemory memory, int targetIndex) {
        if (memory == null || targetIndex < 0) {
            return false;
        }
        boolean removed = memory.getKnownHostiles().remove(targetIndex) >= 0;
        List<Ref<EntityStore>> hostiles = memory.getKnownHostilesList();
        removed |= hostiles.removeIf(target -> target != null && target.getIndex() == targetIndex);

        Ref<EntityStore> closest = memory.getClosestHostile();
        if (closest != null && closest.getIndex() == targetIndex) {
            memory.setClosestHostile(null);
            removed = true;
        }
        return removed;
    }

    @Nullable
    private static Ref<EntityStore> resolveTarget(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null || !infoProvider.hasPosition()) {
            return null;
        }
        IPositionProvider provider = infoProvider.getPositionProvider();
        if (provider == null) {
            return null;
        }
        Ref<EntityStore> target = provider.getTarget();
        return target != null && target.isValid() ? target : null;
    }
}
