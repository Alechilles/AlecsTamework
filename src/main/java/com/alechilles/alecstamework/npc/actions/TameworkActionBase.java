package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
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

public abstract class TameworkActionBase extends ActionBase {
    protected TameworkActionBase(BuilderActionBase builder) {
        super(builder);
    }

    protected Player resolveInteractionPlayer(Role role, InfoProvider infoProvider, Store<EntityStore> store) {
        if (role != null && role.getStateSupport() != null) {
            Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
            if (target != null && target.isValid()) {
                Player player = store.getComponent(target, Player.getComponentType());
                if (player != null) {
                    return player;
                }
            }
        }
        if (infoProvider != null && infoProvider.hasPosition()) {
            IPositionProvider positionProvider = infoProvider.getPositionProvider();
            if (positionProvider instanceof EntityPositionProvider) {
                Ref<EntityStore> target = ((EntityPositionProvider) positionProvider).getTarget();
                if (target != null && target.isValid()) {
                    Player player = store.getComponent(target, Player.getComponentType());
                    if (player != null) {
                        return player;
                    }
                }
            }
        }
        return null;
    }

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

    protected NPCEntity resolveNpcEntity(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        return store.getComponent(npcRef, NPCEntity.getComponentType());
    }

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
        if (npcPlugin != null) {
            int roleIndex = npc.getRoleIndex();
            if (roleIndex >= 0) {
                String nameKey = npcPlugin.getName(roleIndex);
                if (nameKey != null && instance != null && instance.getTranslationRegistry() != null) {
                    String translated = instance.getTranslationRegistry().get(nameKey);
                    if (translated != null && !translated.isBlank()) {
                        return translated;
                    }
                    if (!nameKey.contains(".")) {
                        String derivedKey = "npcRoles." + nameKey + ".name";
                        translated = instance.getTranslationRegistry().get(derivedKey);
                        if (translated != null && !translated.isBlank()) {
                            return translated;
                        }
                    }
                }
            }
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            if (instance != null && instance.getTranslationRegistry() != null) {
                String derivedKey = "npcRoles." + roleName + ".name";
                String translated = instance.getTranslationRegistry().get(derivedKey);
                if (translated != null && !translated.isBlank()) {
                    return translated;
                }
            }
            return roleName;
        }
        return null;
    }

    protected boolean isEmptySpawnerItem(ItemFeatureConfig config) {
        if (config == null) {
            return false;
        }
        String filledId = config.getSpawnerFilledItemId();
        return filledId != null && !filledId.isBlank();
    }

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
