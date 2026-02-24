package com.alechilles.alecstamework.npc.progression;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nullable;

/**
 * Resolves role IDs from NPC entity state for progression systems.
 */
public final class CompanionRoleIdResolver {
    private CompanionRoleIdResolver() {
    }

    @Nullable
    public static String resolveRoleId(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex < 0) {
            return null;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        String roleId = npcPlugin.getName(roleIndex);
        return roleId == null || roleId.isBlank() ? null : roleId;
    }
}
