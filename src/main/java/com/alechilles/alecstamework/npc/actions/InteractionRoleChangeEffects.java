package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;

/** Applies an interaction-requested NPC role change. */
interface InteractionRoleChangeEffects {
    boolean applySetRole(String roleId,
                         boolean changeAppearance,
                         Ref<EntityStore> npcRef,
                         Role role,
                         Store<EntityStore> store);
}
