package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Permanently culls a linked companion on the current world thread. */
final class CommandOwnerCullService {
    private static final float CULL_DAMAGE_AMOUNT = 2.1474836E9F;

    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandFeedbackService feedbackService;
    private final CommandNpcNameResolver npcNameResolver;

    CommandOwnerCullService(CommandLinkPolicyService linkPolicyService,
                            CommandLinkMutationService linkMutationService,
                            CommandFeedbackService feedbackService,
                            CommandNpcNameResolver npcNameResolver) {
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.feedbackService = feedbackService;
        this.npcNameResolver = npcNameResolver;
    }

    void cull(@Nullable Player player,
              @Nullable String toolId,
              @Nullable TwCommandItemConfig config,
              @Nullable UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        LiveCullTarget target = resolveTarget(player, npcUuid);
        if (target == null) {
            return;
        }
        UUID playerUuid = player.getUuid();
        if (playerUuid == null || !canCull(playerUuid, config, target.reference(), target.store())) {
            warn(player, "tamework.ui.notifications.command.cull.ownedNearbyOnly");
            return;
        }
        DamageCause cause = DamageCause.COMMAND != null ? DamageCause.COMMAND : DamageCause.PHYSICAL;
        if (cause == null) {
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
            return;
        }
        String displayName = resolveDisplayName(player, target);
        clearNpcCommandLinks(target);
        removeNpcFromAllCommandToolRecords(player, npcUuid);
        applyFatalDamage(target, cause);
        feedbackService.showSuccessKey(
                player,
                "tamework.ui.notifications.command.cull.success",
                displayName
        );
    }

    @Nullable
    private LiveCullTarget resolveTarget(Player player, UUID npcUuid) {
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null
                : world.getEntityStore().getStore();
        if (world == null || store == null) {
            warn(player, "tamework.ui.notifications.command.cull.unavailable");
            return null;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            warn(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            warn(player, "tamework.ui.notifications.command.cull.mustBeLoaded");
            return null;
        }
        return new LiveCullTarget(npcRef, store, npc);
    }

    private boolean canCull(UUID ownerUuid,
                            @Nullable TwCommandItemConfig config,
                            Ref<EntityStore> npcRef,
                            Store<EntityStore> store) {
        boolean requireTamed = config != null && config.isRequireTamed();
        return linkPolicyService.passesOwnerAndTamed(
                linkingRequiresOwner(),
                requireTamed,
                npcRef,
                ownerUuid,
                store
        );
    }

    private boolean linkingRequiresOwner() {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global == null ? TwGlobalConfig.defaultConfig() : global;
        return TameworkRuntimeSettings.linkingRequiresOwner(resolved.isOwnershipLinkingRequiresOwner());
    }

    private void clearNpcCommandLinks(@Nullable LiveCullTarget target) {
        if (target == null) {
            throw new IllegalStateException("Applied cull target is no longer live");
        }
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null
                ? null
                : target.store().getComponent(target.reference(), linksType);
        if (links == null) {
            return;
        }
        links.setOwnerId(null);
        links.setToolIds(new String[0]);
        links.setHomePosition(null);
        target.store().putComponent(target.reference(), linksType, links);
    }

    private void applyFatalDamage(@Nullable LiveCullTarget target, DamageCause cause) {
        if (target == null) {
            throw new IllegalStateException("Applied cull target is no longer live");
        }
        DeathComponent.tryAddComponent(
                target.store(),
                target.reference(),
                new Damage(Damage.NULL_SOURCE, cause, CULL_DAMAGE_AMOUNT)
        );
    }

    private void removeNpcFromAllCommandToolRecords(Player player, UUID npcUuid) {
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
            String stackToolId = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_TOOL_ID,
                    Codec.STRING
            );
            if (stackToolId == null || stackToolId.isBlank()) {
                continue;
            }
            ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, npcUuid);
            if (updated != stack) {
                hotbar.setItemStackForSlot(slot, updated);
            }
        }
    }

    private String resolveDisplayName(Player player, LiveCullTarget target) {
        String displayName = npcNameResolver.resolveNpcDisplayName(
                target.reference(),
                target.store(),
                target.npc()
        );
        return displayName == null || displayName.isBlank()
                ? LocalizedText.resolve(player, "tamework.ui.notifications.command.shared.defaultMobName")
                : displayName;
    }

    private void warn(Player player, String key) {
        feedbackService.showWarningKey(player, key);
    }

    private record LiveCullTarget(@Nonnull Ref<EntityStore> reference,
                                  @Nonnull Store<EntityStore> store,
                                  @Nonnull NPCEntity npc) {
    }
}
