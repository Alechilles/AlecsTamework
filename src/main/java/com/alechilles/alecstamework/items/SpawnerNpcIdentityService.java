package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Resolves NPC identity information used by spawner capture/snapshot flows.
 */
public final class SpawnerNpcIdentityService {

    public String resolveModelAssetId(Player player, Ref<EntityStore> targetRef) {
        if (player == null || targetRef == null || !targetRef.isValid()) {
            return "<none>";
        }
        World world = player.getWorld();
        if (world == null) {
            return "<no-world>";
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        ModelComponent modelComponent = store.getComponent(targetRef, ModelComponent.getComponentType());
        if (modelComponent == null || modelComponent.getModel() == null) {
            return "<no-model>";
        }
        return modelComponent.getModel().getModelAssetId();
    }

    public String resolveDisplayName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (npcRef != null && npcRef.isValid() && store != null) {
            ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
            if (nameType != null) {
                TameworkNpcNameComponent nameComponent = store.getComponent(npcRef, nameType);
                if (nameComponent != null && nameComponent.getName() != null && !nameComponent.getName().isBlank()) {
                    return nameComponent.getName();
                }
            }
        }
        return resolveDisplayName(npc);
    }

    public String resolveDisplayName(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        TranslationRegistry registry = resolveTranslationRegistry();
        int roleIndex = npc.getRoleIndex();
        String roleNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                if (registry != null) {
                    String translated = RoleNameResolver.resolveDisplayName(
                            nameKey,
                            roleNameKey,
                            registry::get
                    );
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                }
                return nameKey;
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            if (registry != null) {
                String translated = RoleNameResolver.resolveDisplayName(
                        roleName,
                        roleNameKey,
                        registry::get
                );
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            return roleName;
        }
        return null;
    }

    public String resolveRoleId(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex >= 0 && NPCPlugin.get() != null) {
            String name = NPCPlugin.get().getName(roleIndex);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private TranslationRegistry resolveTranslationRegistry() {
        Tamework instance = Tamework.getInstance();
        if (instance == null) {
            return null;
        }
        return instance.getTranslationRegistry();
    }
}

