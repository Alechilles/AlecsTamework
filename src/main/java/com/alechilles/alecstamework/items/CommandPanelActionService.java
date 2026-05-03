package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles linked panel row/header actions that mutate command tool state.
 */
final class CommandPanelActionService {
    private static final Logger LOGGER = Logger.getLogger(CommandPanelActionService.class.getName());
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandFeedbackService feedbackService;
    private final CommandGroupService groupService;

    CommandPanelActionService(CommandLinkMutationService linkMutationService,
                              CommandToolInventoryService toolInventoryService,
                              CommandPanelPreferenceService panelPreferenceService,
                              CommandFeedbackService feedbackService,
                              CommandGroupService groupService) {
        this.linkMutationService = linkMutationService;
        this.toolInventoryService = toolInventoryService;
        this.panelPreferenceService = panelPreferenceService;
        this.feedbackService = feedbackService;
        this.groupService = groupService != null ? groupService : new CommandGroupService();
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.unavailable");
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (npcRef == null || !npcRef.isValid() || store == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.mustBeLoaded");
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.failed");
            return;
        }
        if (!result.linked) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.alreadyLinked");
            return;
        }
        if (result.active) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.link.success", result.npcName);
            return;
        }
        feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.link.successInactive", result.npcName);
    }

    void applyToggleActive(Player player,
                           String toolId,
                           TwCommandItemConfig config,
                           UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        CommandLinkMutationService.ActiveToggleResult[] resultHolder =
                new CommandLinkMutationService.ActiveToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            CommandLinkMutationService.ActiveToggleResult result =
                    linkMutationService.toggleLinkedNpcActive(stack, npcUuid, config);
            resultHolder[0] = result;
            return result.updatedItem;
        });
        CommandLinkMutationService.ActiveToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            if (result != null && result.blockedByMaxActive) {
                feedbackService.showWarningKey(player, "tamework.ui.notifications.command.toggleActive.maxReached");
                return;
            }
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
            return;
        }
        feedbackService.showSuccessKey(
                player,
                result.active
                        ? "tamework.ui.notifications.command.toggleActive.enabled"
                        : "tamework.ui.notifications.command.toggleActive.disabled"
        );
    }

    void applyToggleBreeding(Player player,
                             String toolId,
                             UUID npcUuid) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        CommandLinkMutationService.BreedingToggleResult[] resultHolder =
                new CommandLinkMutationService.BreedingToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            CommandLinkMutationService.BreedingToggleResult result =
                    linkMutationService.toggleLinkedNpcBreeding(stack, npcUuid);
            resultHolder[0] = result;
            return result.updatedItem;
        });
        CommandLinkMutationService.BreedingToggleResult result = resultHolder[0];
        if (result == null || !result.toggled) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.shared.notLinkedToTool");
            return;
        }
        applyLoadedNpcBreedingToggle(player, npcUuid, result.breedingEnabled);
        feedbackService.showSuccessKey(
                player,
                result.breedingEnabled
                        ? "tamework.ui.notifications.command.toggleBreeding.enabled"
                        : "tamework.ui.notifications.command.toggleBreeding.disabled"
        );
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.modeUpdateFailed");
        }
    }

    void applySetPanelMode(Player player,
                           String toolId,
                           String modeValue) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setPanelMode(stack, modeValue)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.modeUpdateFailed");
        }
    }

    void applySetAutoLinkEnabled(Player player,
                                 String toolId,
                                 boolean enabled) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setAutoLinkEnabled(stack, enabled)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.autoLinkUpdateFailed");
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
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.radiusUpdateFailed");
        }
    }

    void applyCycleSort(Player player, String toolId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                panelPreferenceService::cycleSort
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.sortUpdateFailed");
        }
    }

    void applySetSort(Player player, String toolId, String sortValue) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setSort(stack, sortValue)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.sortUpdateFailed");
        }
    }

    void applySetFilterMode(Player player, String toolId, String filterModeValue) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setFilterMode(stack, filterModeValue)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.filterModeUpdateFailed");
        }
    }

    void applySetSelectedFilterText(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.applySelectedFilterText(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.filterUpdateFailed");
        }
    }

    void applyClearFilters(Player player, String toolId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                panelPreferenceService::clearFilters
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.clearFiltersFailed");
        }
    }

    void applySetNameFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setNameFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.nameFilterUpdateFailed");
        }
    }

    void applySetSpeciesFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setSpeciesFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.speciesFilterUpdateFailed");
        }
    }

    void applySetGroupFilter(Player player, String toolId, String value) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setGroupFilter(stack, value)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.groupFilterUpdateFailed");
        }
    }

    void applySetLinkedNpcGroup(Player player,
                                String toolId,
                                UUID npcUuid,
                                String groupId) {
        if (player == null || toolId == null || toolId.isBlank() || npcUuid == null) {
            return;
        }
        String normalizedGroupId = normalizeOptionalGroupId(groupId);
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    if (normalizedGroupId != null && groupService.findGroup(stack, normalizedGroupId) == null) {
                        return stack;
                    }
                    return linkMutationService.setLinkedNpcGroup(stack, npcUuid, normalizedGroupId);
                }
        );
        if (!updated && player != null && normalizedGroupId != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.assignFailed");
        }
    }

    void applyCreateGroup(Player player, String toolId, String name, String colorHex) {
        LOGGER.log(
                Level.INFO,
                "Group create mutation requested: toolId={0} name={1} color={2}",
                new Object[] { safeForLog(toolId), safeForLog(name), safeForLog(colorHex) }
        );
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.createGroup(stack, name, colorHex)
        );
        LOGGER.log(
                Level.INFO,
                "Group create mutation result: toolId={0} updated={1}",
                new Object[] { safeForLog(toolId), updated }
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.createFailed");
            return;
        }
        if (player != null) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.group.created");
        }
    }

    void applyRenameGroup(Player player, String toolId, String groupId, String name) {
        LOGGER.log(
                Level.INFO,
                "Group rename mutation requested: toolId={0} groupId={1} newName={2}",
                new Object[] { safeForLog(toolId), safeForLog(groupId), safeForLog(name) }
        );
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.renameGroup(stack, groupId, name)
        );
        LOGGER.log(
                Level.INFO,
                "Group rename mutation result: toolId={0} groupId={1} updated={2}",
                new Object[] { safeForLog(toolId), safeForLog(groupId), updated }
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.renameFailed");
            return;
        }
        if (player != null) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.group.renamed");
        }
    }

    void applyRecolorGroup(Player player, String toolId, String groupId, String colorHex) {
        LOGGER.log(
                Level.INFO,
                "Group recolor mutation requested: toolId={0} groupId={1} color={2}",
                new Object[] { safeForLog(toolId), safeForLog(groupId), safeForLog(colorHex) }
        );
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> groupService.recolorGroup(stack, groupId, colorHex)
        );
        LOGGER.log(
                Level.INFO,
                "Group recolor mutation result: toolId={0} groupId={1} updated={2}",
                new Object[] { safeForLog(toolId), safeForLog(groupId), updated }
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.recolorFailed");
            return;
        }
        if (player != null) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.group.recolored");
        }
    }

    void applyDeleteGroup(Player player, String toolId, String groupId) {
        LOGGER.log(
                Level.INFO,
                "Group delete mutation requested: toolId={0} groupId={1}",
                new Object[] { safeForLog(toolId), safeForLog(groupId) }
        );
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    ItemStack updatedStack = groupService.deleteGroup(stack, groupId);
                    if (updatedStack == stack) {
                        return stack;
                    }
                    return clearGroupAssignments(updatedStack, groupId);
                }
        );
        LOGGER.log(
                Level.INFO,
                "Group delete mutation result: toolId={0} groupId={1} updated={2}",
                new Object[] { safeForLog(toolId), safeForLog(groupId), updated }
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.group.deleteFailed");
            return;
        }
        if (player != null) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.group.deleted");
        }
    }

    private ItemStack clearGroupAssignments(ItemStack stack, String groupId) {
        if (stack == null || stack.isEmpty() || groupId == null || groupId.isBlank()) {
            return stack;
        }
        List<LinkedNpcRecord> records = linkMutationService.readLinkedNpcRecords(stack);
        if (records.isEmpty()) {
            return stack;
        }
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        boolean changed = false;
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (record.groupId != null && record.groupId.equalsIgnoreCase(groupId.trim())) {
                updated.add(new LinkedNpcRecord(
                        record.npcUuid,
                        record.lastKnownPosition,
                        record.lastKnownWorldName,
                        record.homePosition,
                        record.cachedDisplayName,
                        record.cachedNameKey,
                        record.cachedRoleId,
                        record.cachedCommandState,
                        record.active,
                        record.breedingEnabled,
                        null
                ));
                changed = true;
                continue;
            }
            updated.add(record);
        }
        if (!changed) {
            return stack;
        }
        return linkMutationService.writeLinkedNpcRecords(stack, updated);
    }

    private static String safeForLog(String value) {
        if (value == null) {
            return "<null>";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "<empty>";
        }
        if (trimmed.length() <= 48) {
            return trimmed;
        }
        return trimmed.substring(0, 45) + "...";
    }

    private String normalizeOptionalGroupId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void applyLoadedNpcBreedingToggle(Player player,
                                              UUID npcUuid,
                                              boolean enabled) {
        if (player == null || npcUuid == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
        if (npcRef == null || !npcRef.isValid()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) {
            return;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return;
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            return;
        }
        boolean changed = false;
        if (breeding.isEnabled() != enabled) {
            breeding.setEnabled(enabled);
            changed = true;
        }
        if (!enabled && breeding.isReady()) {
            breeding.setReady(false);
            changed = true;
        }
        if (changed) {
            store.putComponent(npcRef, breedingType, breeding);
        }
    }
}
