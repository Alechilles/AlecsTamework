package com.alechilles.alecstamework.npc.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.RoleDebugFlags;
import java.util.EnumSet;
import javax.annotation.Nonnull;

/**
 * Re-activates NPC role debug displays after entity load/unload cycles.
 *
 * <p>Some debug displays (notably {@code DisplayState}) can remain visually stale after entity reload because the
 * nameplate text persists while role debug display refresh does not resume. Re-applying current debug flags forces
 * the role debug support to reactivate, preserving existing debug selections while restoring live updates.
 */
public final class NpcDebugDisplayResumeOnLoadSystem extends RefSystem<EntityStore> {
    private final ComponentType<EntityStore, NPCEntity> npcType;

    public NpcDebugDisplayResumeOnLoadSystem(ComponentType<EntityStore, NPCEntity> npcType) {
        this.npcType = npcType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (npcType == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, npcType);
        if (npc == null || npc.getRole() == null) {
            return;
        }
        EnumSet<RoleDebugFlags> flags = npc.getRoleDebugFlags();
        if (flags == null || flags.isEmpty() || !flags.contains(RoleDebugFlags.DisplayState)) {
            return;
        }
        npc.setRoleDebugFlags(EnumSet.copyOf(flags));
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // No-op.
    }

    @Override
    public Query<EntityStore> getQuery() {
        return npcType == null ? Query.any() : Query.and(npcType);
    }
}
