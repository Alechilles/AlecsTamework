package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Blocks interactions with owned NPCs and notifies the player.
 */
public final class OwnerInteractionListener {
    private final TranslationRegistry translationRegistry;
    private final HytaleLogger logger;

    public OwnerInteractionListener(TranslationRegistry translationRegistry, HytaleLogger logger) {
        this.translationRegistry = translationRegistry;
        this.logger = logger;
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null) {
            return;
        }
        InteractionType actionType = event.getActionType();
        if (actionType != InteractionType.Use
                && actionType != InteractionType.Primary
                && actionType != InteractionType.Secondary) {
            return;
        }
        Entity target = event.getTargetEntity();
        if (!(target instanceof NPCEntity)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID ownerUuid = null;
        String ownerName = null;
        boolean componentPresent = false;
        World world = player.getWorld();
        if (world != null) {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Ref<EntityStore> ref = ((NPCEntity) target).getReference();
            if (ref != null && ref.isValid()) {
                TameworkOwnerComponent component = store.getComponent(ref, TameworkOwnerComponent.getComponentType());
                if (component != null) {
                    componentPresent = true;
                    ownerUuid = component.getOwnerId();
                    ownerName = component.getOwnerName();
                }
            }
        }
        if (ownerUuid == null || ownerUuid.equals(player.getUuid())) {
            return;
        }

        NPCEntity npc = (NPCEntity) target;
        String npcName = null;
        String displayName = npc.getLegacyDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            npcName = displayName;
        }
        if (npcName == null) {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin != null) {
                int roleIndex = npc.getRoleIndex();
                if (roleIndex >= 0) {
                    String nameKey = npcPlugin.getName(roleIndex);
                    if (nameKey != null && translationRegistry != null) {
                        String translated = translationRegistry.get(nameKey);
                        if (translated != null && !translated.isBlank()) {
                            npcName = translated;
                        } else if (!nameKey.contains(".")) {
                            String derivedKey = "npcRoles." + nameKey + ".name";
                            translated = translationRegistry.get(derivedKey);
                            if (translated != null && !translated.isBlank()) {
                                npcName = translated;
                            }
                        }
                    }
                }
            }
        }
        if (npcName == null) {
            String roleName = npc.getRoleName();
            if (roleName != null && !roleName.isBlank()) {
                if (translationRegistry != null) {
                    String derivedKey = "npcRoles." + roleName + ".name";
                    String translated = translationRegistry.get(derivedKey);
                    if (translated != null && !translated.isBlank()) {
                        npcName = translated;
                    }
                }
                if (npcName == null) {
                    npcName = roleName;
                }
            }
        }

        String verb = "interact with";
        Inventory inventory = player.getInventory();
        if (inventory != null) {
            ItemStack active = inventory.getActiveHotbarItem();
            if (active != null && !active.isEmpty()) {
                Tamework instance = Tamework.getInstance();
                ItemFeatureRegistry registry = instance != null ? instance.getItemFeatureRegistry() : null;
                if (registry != null) {
                    ItemFeatureConfig config = registry.get(active.getItemId());
                    if (config != null && config.isSpawnerEnabled()) {
                        String filledId = config.getSpawnerFilledItemId();
                        if (filledId != null && !filledId.isBlank()) {
                            verb = "capture";
                        }
                    }
                }
            }
        }

        event.setCancelled(true);
        OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, verb);
        logger.at(Level.FINE).log(
                "Owner restrict: denied interaction player=" + player.getDisplayName()
                        + " target=" + target.getUuid()
        );
    }
}
