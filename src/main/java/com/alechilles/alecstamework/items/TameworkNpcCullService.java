package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nullable;

/** Applies one authorized companion cull on the current world thread. */
public final class TameworkNpcCullService {
    private static final float CULL_DAMAGE_AMOUNT = 2.1474836E9F;

    enum Outcome {
        CULLED,
        DENIED,
        UNAVAILABLE
    }

    private final TameworkCullEligibility eligibility;
    private final CommandItemRegistry registry;
    private final CommandLinkMutationService linkMutationService;

    TameworkNpcCullService(TameworkCullEligibility eligibility,
                           @Nullable CommandItemRegistry registry,
                           CommandLinkMutationService linkMutationService) {
        this.eligibility = eligibility;
        this.registry = registry;
        this.linkMutationService = linkMutationService;
    }

    /** Culls a target from a registered item interaction. */
    public static boolean cullFromItemInteraction(
            @Nullable Player player,
            @Nullable Ref<EntityStore> target,
            @Nullable Store<EntityStore> store,
            boolean requireOwner,
            boolean requireTamed
    ) {
        Tamework plugin = Tamework.getInstance();
        CommandLinkPolicyService policy = new CommandLinkPolicyService();
        TameworkNpcCullService service = new TameworkNpcCullService(
                new TameworkCullEligibility(policy),
                plugin == null ? null : plugin.getCommandItemRegistry(),
                new CommandLinkMutationService(null, policy, null, null)
        );
        return service.cull(player, target, store, requireOwner, requireTamed)
                == Outcome.CULLED;
    }

    Outcome cull(@Nullable Player player,
                 @Nullable Ref<EntityStore> target,
                 @Nullable Store<EntityStore> store,
                 boolean requireOwner,
                 boolean requireTamed) {
        if (player == null || target == null || !target.isValid() || store == null) {
            return Outcome.UNAVAILABLE;
        }
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        if (npc == null) {
            return Outcome.UNAVAILABLE;
        }
        if (!eligibility.allows(player.getUuid(), requireOwner, requireTamed,
                target, store)) {
            return Outcome.DENIED;
        }
        DamageCause cause = DamageCause.COMMAND != null
                ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        if (cause == null) {
            return Outcome.UNAVAILABLE;
        }
        clearCommandLinks(target, store);
        removeCommandToolRecords(player, npc.getUuid());
        DeathComponent.tryAddComponent(
                store,
                target,
                new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT)
        );
        return Outcome.CULLED;
    }

    private void clearCommandLinks(Ref<EntityStore> target,
                                   Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null
                ? null : store.getComponent(target, linksType);
        if (links == null) {
            return;
        }
        links.setOwnerId(null);
        links.setToolIds(new String[0]);
        links.setHomePosition(null);
        store.putComponent(target, linksType, links);
    }

    private void removeCommandToolRecords(Player player, @Nullable UUID npcUuid) {
        if (registry == null || npcUuid == null || linkMutationService == null) {
            return;
        }
        ItemContainer hotbar = PlayerInventoryAccess.getHotbar(player);
        if (hotbar == null) {
            return;
        }
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String toolId = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_TOOL_ID,
                    Codec.STRING
            );
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            TwCommandItemConfig config = registry.get(stack.getItemId());
            if (!CommandGenericTargetAuthority.allowsGenericCullRepair(
                    stack, config)) {
                continue;
            }
            ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            if (updated != stack) {
                hotbar.setItemStackForSlot(slot, updated);
            }
        }
    }
}
