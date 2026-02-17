package com.alechilles.alecstamework.npc;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Locale;

/**
 * Resolves whether an NPC should be treated as tamed.
 *
 * Supports both the Tamework tamed component and vanilla-style role names that start with "Tamed".
 */
public final class TamedStateResolver {
    private static final String VANILLA_TAMED_ROLE_PREFIX = "tamed";

    private TamedStateResolver() {
    }

    public static boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type != null) {
            TameworkTamedComponent component = store.getComponent(npcRef, type);
            if (component != null && component.isTamed()) {
                return true;
            }
        }
        return isTamedRoleId(resolveRoleId(npcRef, store));
    }

    public static boolean isTamedRoleId(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return roleId.trim().toLowerCase(Locale.ROOT).startsWith(VANILLA_TAMED_ROLE_PREFIX);
    }

    private static String resolveRoleId(Ref<EntityStore> npcRef, Store<EntityStore> store) {
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
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return roleId;
    }
}
