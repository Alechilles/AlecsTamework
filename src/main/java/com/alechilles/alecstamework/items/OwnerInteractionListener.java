package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.actions.InteractionInputTracker;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
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
import com.hypixel.hytale.server.npc.role.Role;
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

    // Enforce owner-only interactions for NPCs with an owner component.
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null) {
            return;
        }
        Entity target = event.getTargetEntity();
        Player player = event.getPlayer();
        InteractionType actionType = event.getActionType();
        if (player != null && actionType != null
                && (actionType == InteractionType.Use
                || actionType == InteractionType.Primary
                || actionType == InteractionType.Secondary)) {
            Entity recordTarget = target instanceof NPCEntity ? target : null;
            InteractionInputTracker.record(player, recordTarget, actionType);
        }
        if (event.isCancelled()) {
            return;
        }
        if (actionType != InteractionType.Use
                && actionType != InteractionType.Primary
                && actionType != InteractionType.Secondary) {
            return;
        }
        if (player == null) {
            return;
        }
        if (!(target instanceof NPCEntity)) {
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
        // Skip restriction when there is no owner or the player is the owner.
        if (ownerUuid == null || ownerUuid.equals(player.getUuid())) {
            maybeTriggerAltInteraction(actionType, (NPCEntity) target, player);
            return;
        }

        // If this is a spawner capture attempt and owner restrictions are disabled, allow it.
        Inventory inventory = player.getInventory();
        if (inventory != null) {
            ItemStack active = inventory.getActiveHotbarItem();
            if (active != null && !active.isEmpty()) {
                Tamework instance = Tamework.getInstance();
                ItemFeatureRegistry registry = instance != null ? instance.getItemFeatureRegistry() : null;
                if (registry != null) {
                    ItemFeatureConfig config = registry.get(active.getItemId());
                    if (config != null && config.isSpawnerEnabled() && !config.isCaptureOwnerRestricted()) {
                        maybeTriggerAltInteraction(actionType, (NPCEntity) target, player);
                        return;
                    }
                }
            }
        }

        NPCEntity npc = (NPCEntity) target;
        // Resolve NPC display name with a best-effort fallback chain.
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

        // Determine whether this is a capture attempt or a generic interaction.
        String verb = "interact with";
        // Decide which verb to show based on whether the held item is a spawner.
        Inventory interactionInventory = player.getInventory();
        if (interactionInventory != null) {
            ItemStack active = interactionInventory.getActiveHotbarItem();
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

    private void maybeTriggerAltInteraction(InteractionType actionType, NPCEntity npc, Player player) {
        if (actionType != InteractionType.Primary && actionType != InteractionType.Secondary) {
            return;
        }
        if (npc == null || player == null) {
            return;
        }
        Role role = npc.getRole();
        if (role == null || role.getStateSupport() == null) {
            return;
        }
        if (!roleHasInteractionType(role, actionType)) {
            return;
        }
        role.getStateSupport().addInteraction(player);
    }

    private boolean roleHasInteractionType(Role role, InteractionType actionType) {
        if (role == null || actionType == null) {
            return false;
        }
        TwInteractionConfig config = resolveConfig(role);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        TwInteractionConfig.InteractionInputType required =
                actionType == InteractionType.Primary
                        ? TwInteractionConfig.InteractionInputType.Primary
                        : TwInteractionConfig.InteractionInputType.Secondary;
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (entry == null || !entry.isEnabled()) {
                continue;
            }
            if (required == entry.getInteractionType()) {
                return true;
            }
        }
        return false;
    }

    private TwInteractionConfig resolveConfig(Role role) {
        if (role == null) {
            return null;
        }
        String roleId = role.getRoleName();
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        var assetMap = TwInteractionConfig.getAssetMap();
        if (assetMap == null) {
            return null;
        }
        for (TwInteractionConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate != null && candidate.isEnabled() && candidate.matchesRole(roleId)) {
                return candidate;
            }
        }
        return null;
    }
}
