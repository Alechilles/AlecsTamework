package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderActionBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.EntityPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Shared helpers for Tamework NPC actions.
 */
public abstract class TameworkActionBase extends ActionBase {
    protected TameworkActionBase(BuilderActionBase builder) {
        super(builder);
    }

    // Prefer the role's interaction target, then fall back to the info provider.
    protected Player resolveInteractionPlayer(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        Ref<EntityStore> target = resolveInteractionTarget(role, infoProvider);
        if (target != null && target.isValid()) {
            Player player = store.getComponent(target, Player.getComponentType());
            if (player != null) {
                return player;
            }
        }
        return null;
    }

    // Resolves the current interaction target ref from role state or sensor info.
    protected Ref<EntityStore> resolveInteractionTarget(Role role, InfoProvider infoProvider) {
        if (role != null && role.getStateSupport() != null) {
            Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
            if (target != null && target.isValid()) {
                return target;
            }
        }
        if (infoProvider != null && infoProvider.hasPosition()) {
            IPositionProvider positionProvider = infoProvider.getPositionProvider();
            if (positionProvider instanceof EntityPositionProvider) {
                Ref<EntityStore> target = ((EntityPositionProvider) positionProvider).getTarget();
                if (target != null && target.isValid()) {
                    return target;
                }
            }
        }
        return null;
    }

    // Returns the active hotbar item if present.
    protected ItemStack getActiveItem(Player player) {
        if (player == null) {
            return null;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return null;
        }
        ItemStack stack = inventory.getActiveHotbarItem();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack;
    }

    protected UUID getPlayerUuid(Player player) {
        if (player == null) {
            return null;
        }
        return player.getUuid();
    }

    // Reads owner UUID from the component if available.
    protected UUID resolveOwnerUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        if (component != null) {
            return component.getOwnerId();
        }
        return null;
    }

    // Reads owner display name from the component if available.
    protected String resolveOwnerName(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        if (component != null) {
            return component.getOwnerName();
        }
        return null;
    }

    // Resolve the NPC entity component from the ref.
    protected NPCEntity resolveNpcEntity(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        return store.getComponent(npcRef, NPCEntity.getComponentType());
    }

    // Resolve a player-facing NPC name with translation fallbacks.
    protected String resolveNpcName(NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        Tamework instance = Tamework.getInstance();
        String roleNameKey = RoleNameResolver.resolveRoleNameKey(npc.getRole());
        if (npcPlugin != null) {
            int roleIndex = npc.getRoleIndex();
            if (roleIndex >= 0) {
                String roleId = npcPlugin.getName(roleIndex);
                String resolved = resolveRoleDisplayName(roleId, roleNameKey, instance);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            String resolved = resolveRoleDisplayName(roleName, roleNameKey, instance);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
            return roleName;
        }
        return null;
    }

    private String resolveRoleDisplayName(String roleId, String roleNameKey, Tamework instance) {
        if (instance != null && instance.getTranslationRegistry() != null) {
            return RoleNameResolver.resolveDisplayName(roleId, roleNameKey, instance.getTranslationRegistry()::get);
        }
        return RoleNameResolver.resolveDisplayName(roleId, roleNameKey, null);
    }

    // Empty spawners are identified by having a filled-item target configured.
    protected boolean isEmptySpawnerItem(ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        String filledId = config.getSpawnerFilledItemId();
        return filledId != null && !filledId.isBlank();
    }

    // Resolve spawner config only when the item is a valid spawner.
    protected ItemFeatureConfig resolveSpawnerConfig(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return null;
        }
        Tamework instance = Tamework.getInstance();
        ItemFeatureRegistry registry = instance != null ? instance.getItemFeatureRegistry() : null;
        if (registry == null) {
            return null;
        }
        ItemFeatureConfig config = registry.get(itemStack.getItemId());
        if (config == null || !config.isSpawnerEnabled()) {
            return null;
        }
        return config;
    }
}
