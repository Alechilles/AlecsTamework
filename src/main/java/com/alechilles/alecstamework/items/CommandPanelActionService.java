package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/**
 * Handles linked panel row/header actions that mutate command tool state.
 */
final class CommandPanelActionService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandFeedbackService feedbackService;

    CommandPanelActionService(CommandLinkMutationService linkMutationService,
                              CommandToolInventoryService toolInventoryService,
                              CommandPanelPreferenceService panelPreferenceService,
                              CommandFeedbackService feedbackService) {
        this.linkMutationService = linkMutationService;
        this.toolInventoryService = toolInventoryService;
        this.panelPreferenceService = panelPreferenceService;
        this.feedbackService = feedbackService;
    }

    void applyLink(Player player,
                   String toolId,
                   TwCommandItemConfig config,
                   UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || config == null || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            feedbackService.showWarning(player, "Unable to link right now.");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (npcRef == null || !npcRef.isValid() || store == null) {
            feedbackService.showWarning(player, "That companion must be loaded to link.");
            return;
        }
        LinkToggleResult[] resultHolder = new LinkToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            LinkToggleResult result = linkMutationService.tryToggleLink(
                    player,
                    store,
                    npcRef,
                    toolId,
                    config,
                    stack
            );
            resultHolder[0] = result;
            return result.updatedItem != null ? result.updatedItem : stack;
        });
        LinkToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            feedbackService.showWarning(player, "Unable to link that companion.");
            return;
        }
        if (!result.linked) {
            feedbackService.showWarning(player, "That companion is already linked.");
            return;
        }
        feedbackService.showSuccess(player, "Linked " + result.npcName + ".");
    }

    void applyToggleActive(Player player,
                           String toolId,
                           UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        CommandLinkMutationService.ActiveToggleResult[] resultHolder =
                new CommandLinkMutationService.ActiveToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            CommandLinkMutationService.ActiveToggleResult result =
                    linkMutationService.toggleLinkedNpcActive(stack, npcUuid);
            resultHolder[0] = result;
            return result.updatedItem;
        });
        CommandLinkMutationService.ActiveToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            feedbackService.showWarning(player, "That NPC is not linked to this tool.");
            return;
        }
        feedbackService.showSuccess(player, result.active ? "Companion activated." : "Companion set inactive.");
    }

    void applyTogglePanelMode(Player player,
                              String toolId,
                              TwCommandItemConfig config) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.togglePanelMode(stack, config)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update panel mode.");
        }
    }

    void applyAdjustPanelRadius(Player player,
                                String toolId,
                                TwCommandItemConfig config,
                                boolean increase) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.stepNearbyRadius(stack, config, increase)
        );
        if (!updated && player != null) {
            feedbackService.showWarning(player, "Unable to update panel radius.");
        }
    }
}
