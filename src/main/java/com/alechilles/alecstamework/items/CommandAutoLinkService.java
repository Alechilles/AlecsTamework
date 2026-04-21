package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.math.vector.Vector3d;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Auto-links newly tamed companions to the first applicable command tool in a player's inventory.
 */
public final class CommandAutoLinkService {
    private final CommandItemRegistry registry;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandLinkMutationService linkMutationService;

    CommandAutoLinkService(CommandItemRegistry registry,
                           CommandPanelPreferenceService panelPreferenceService,
                           CommandLinkMutationService linkMutationService) {
        this.registry = registry;
        this.panelPreferenceService = panelPreferenceService != null
                ? panelPreferenceService
                : new CommandPanelPreferenceService();
        this.linkMutationService = linkMutationService != null
                ? linkMutationService
                : new CommandLinkMutationService(null, null, null);
    }

    public static void autoLinkNewlyTamedNpc(@Nullable Player player,
                                             @Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        Tamework plugin = Tamework.getInstance();
        CommandItemRegistry registry = plugin != null ? plugin.getCommandItemRegistry() : null;
        if (registry == null) {
            return;
        }
        new CommandAutoLinkService(registry, new CommandPanelPreferenceService(), new CommandLinkMutationService(
                new CommandLinkedNpcRecordStore(),
                new CommandLinkPolicyService(),
                new CommandNpcNameResolver(),
                null
        )).autoLinkNewlyTamedNpcInternal(player, npcRef, store);
    }

    public static void autoLinkNpcToPreferredTool(@Nullable Player player,
                                                  @Nullable Ref<EntityStore> npcRef,
                                                  @Nullable Store<EntityStore> store,
                                                  @Nullable String preferredToolId) {
        Tamework plugin = Tamework.getInstance();
        CommandItemRegistry registry = plugin != null ? plugin.getCommandItemRegistry() : null;
        if (registry == null) {
            return;
        }
        new CommandAutoLinkService(registry, new CommandPanelPreferenceService(), new CommandLinkMutationService(
                new CommandLinkedNpcRecordStore(),
                new CommandLinkPolicyService(),
                new CommandNpcNameResolver(),
                null
        )).autoLinkNpcToPreferredToolInternal(player, npcRef, store, preferredToolId);
    }

    private void autoLinkNewlyTamedNpcInternal(@Nullable Player player,
                                               @Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store) {
        if (player == null || npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        CombinedItemContainer combined = inventory.getCombinedBackpackStorageHotbarFirst();
        if (combined == null || combined.getCapacity() <= 0) {
            return;
        }
        List<ToolCandidate> toolCandidates = resolveToolCandidates(combined);
        if (toolCandidates.isEmpty()) {
            return;
        }
        for (ToolCandidate candidate : toolCandidates) {
            if (candidate == null || tryLinkCandidate(player, store, npcRef, candidate)) {
                break;
            }
        }
    }

    private void autoLinkNpcToPreferredToolInternal(@Nullable Player player,
                                                    @Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable String preferredToolId) {
        if (preferredToolId == null || preferredToolId.isBlank()) {
            autoLinkNewlyTamedNpcInternal(player, npcRef, store);
            return;
        }
        if (player == null || npcRef == null || !npcRef.isValid() || store == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return;
        }
        CombinedItemContainer combined = inventory.getCombinedBackpackStorageHotbarFirst();
        if (combined == null || combined.getCapacity() <= 0) {
            return;
        }
        List<ToolCandidate> toolCandidates = resolveToolCandidates(combined);
        if (toolCandidates.isEmpty()) {
            return;
        }
        for (ToolCandidate candidate : toolCandidates) {
            if (candidate == null) {
                continue;
            }
            String candidateToolId = candidate.toolId();
            if (candidateToolId == null || !preferredToolId.equals(candidateToolId)) {
                continue;
            }
            syncPreferredCandidate(player, store, npcRef, candidate, preferredToolId);
            break;
        }
    }

    private boolean syncPreferredCandidate(@Nonnull Player player,
                                           @Nonnull Store<EntityStore> store,
                                           @Nonnull Ref<EntityStore> npcRef,
                                           @Nonnull ToolCandidate candidate,
                                           @Nonnull String preferredToolId) {
        TameworkCommandLinksComponent links = store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        if (links == null || !links.containsToolId(preferredToolId)) {
            return tryLinkCandidate(player, store, npcRef, candidate);
        }
        ItemStack stack = candidate.container().getItemStack(candidate.slot());
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (stack == null || stack.isEmpty() || npc == null || npc.getUuid() == null) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d lastKnown = transform != null ? new Vector3d(transform.getPosition()) : null;
        Vector3d homePosition = links.hasHome() ? links.getHomePosition() : null;
        String displayName = new CommandNpcNameResolver().resolveNpcDisplayNameFromComponents(npcRef, store);
        String nameKey = new CommandNpcNameResolver().resolveNpcNameKey(npc);
        String roleId = new CommandLinkPolicyService().resolveRoleId(npc);
        ItemStack updated = linkMutationService.upsertLinkedNpcRecord(
                stack,
                npc.getUuid(),
                lastKnown,
                homePosition,
                displayName,
                nameKey,
                roleId
        );
        if (updated == null || updated == stack) {
            return false;
        }
        candidate.container().setItemStackForSlot(candidate.slot(), updated);
        return true;
    }

    private List<ToolCandidate> resolveToolCandidates(CombinedItemContainer combined) {
        ArrayList<ToolCandidate> candidates = new ArrayList<>();
        for (short slot = 0; slot < combined.getCapacity(); slot++) {
            ItemStack stack = combined.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            TwCommandItemConfig config = registry != null ? registry.get(stack.getItemId()) : null;
            if (config == null) {
                continue;
            }
            if (!config.isEnabled()) {
                continue;
            }
            if (!panelPreferenceService.resolveAutoLinkEnabled(stack)) {
                continue;
            }
            String toolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            candidates.add(new ToolCandidate(combined, slot, stack, toolId, config));
        }
        return candidates;
    }

    private boolean tryLinkCandidate(@Nonnull Player player,
                                     @Nonnull Store<EntityStore> store,
                                     @Nonnull Ref<EntityStore> npcRef,
                                     @Nonnull ToolCandidate candidate) {
        ItemStack stack = candidate.container().getItemStack(candidate.slot());
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        TwCommandItemConfig config = registry != null ? registry.get(stack.getItemId()) : candidate.config();
        if (config == null || !config.isEnabled()) {
            return false;
        }
        ToolResolution resolution = ensureToolId(stack, candidate.toolId());
        if (resolution.changed()) {
            candidate.container().setItemStackForSlot(candidate.slot(), resolution.stack());
            stack = resolution.stack();
        }
        LinkToggleResult result = linkMutationService.tryToggleLink(
                player,
                store,
                npcRef,
                resolution.toolId(),
                config,
                stack
        );
        if (result == null || !result.toggled || !result.linked || result.updatedItem == null) {
            return false;
        }
        candidate.container().setItemStackForSlot(candidate.slot(), result.updatedItem);
        return true;
    }

    private ToolResolution ensureToolId(@Nonnull ItemStack stack, @Nullable String toolId) {
        if (toolId != null && !toolId.isBlank()) {
            return new ToolResolution(stack, toolId, false);
        }
        String generated = UUID.randomUUID().toString();
        return new ToolResolution(
                stack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, generated),
                generated,
                true
        );
    }

    private record ToolCandidate(@Nonnull CombinedItemContainer container,
                                 short slot,
                                 @Nonnull ItemStack stack,
                                 @Nullable String toolId,
                                 @Nonnull TwCommandItemConfig config) {
    }

    private record ToolResolution(@Nonnull ItemStack stack,
                                  @Nonnull String toolId,
                                  boolean changed) {
    }
}
