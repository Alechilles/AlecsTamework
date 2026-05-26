package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;

/**
 * Resolves naming-related NPC identity and ownership details.
 */
public final class NamingNpcInfoService {
    private final TranslationRegistry translationRegistry;

    public NamingNpcInfoService(TranslationRegistry translationRegistry) {
        this.translationRegistry = translationRegistry;
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
        if (roleIndex >= 0) {
            String nameKey = NPCPlugin.get().getName(roleIndex);
            if (nameKey != null && !nameKey.isBlank()) {
                return nameKey;
            }
        }
        return null;
    }

    public boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return TamedStateResolver.isTamed(npcRef, store);
    }

    public UUID resolveOwnerUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return component != null ? component.getOwnerId() : null;
    }

    public String resolveOwnerName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        return component != null ? component.getOwnerName() : null;
    }

    public boolean hasTameworkName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkNpcNameComponent component = store.getComponent(npcRef, type);
        return component != null && component.getName() != null && !component.getName().isBlank();
    }

    public boolean hasAnyName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (hasTameworkName(npcRef, store)) {
            return true;
        }
        String componentName = NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
        if (componentName != null && !componentName.isBlank()) {
            return true;
        }
        String legacy = npc != null ? npc.getLegacyDisplayName() : null;
        return legacy != null && !legacy.isBlank();
    }

    public String resolveAssignedName(Ref<EntityStore> npcRef, Store<EntityStore> store, NPCEntity npc) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> type = TameworkNpcNameComponent.getComponentType();
        if (type != null) {
            TameworkNpcNameComponent component = store.getComponent(npcRef, type);
            if (component != null && component.getName() != null && !component.getName().isBlank()) {
                return component.getName();
            }
        }
        String componentName = NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(npcRef, store);
        if (componentName != null && !componentName.isBlank()) {
            return componentName;
        }
        String legacy = npc != null ? npc.getLegacyDisplayName() : null;
        if (legacy != null && !legacy.isBlank()) {
            return legacy;
        }
        return null;
    }

    public String resolveDisplayName(NPCEntity npc) {
        if (npc == null) {
            return "pet";
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        String roleNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
        if (npcPlugin != null) {
            int roleIndex = npc.getRoleIndex();
            if (roleIndex >= 0) {
                String nameKey = npcPlugin.getName(roleIndex);
                if (nameKey != null && translationRegistry != null) {
                    String translated = RoleNameResolver.resolveDisplayName(
                            nameKey,
                            roleNameKey,
                            translationRegistry::get
                    );
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            if (translationRegistry != null) {
                String translated = RoleNameResolver.resolveDisplayName(
                        roleName,
                        roleNameKey,
                        translationRegistry::get
                );
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            return roleName;
        }
        return "pet";
    }
}

