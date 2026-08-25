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

/**
 * Handles linked panel row/header actions that mutate command tool state.
 */
final class CommandPanelActionService {
    private final CommandLinkMutationService linkMutationService;
    private final CommandToolInventoryService toolInventoryService;
    private final CommandPanelPreferenceService panelPreferenceService;
    private final CommandFeedbackService feedbackService;
    private final CommandGroupService groupService;
    private final CommandPanelGroupActionService groupActionService;

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
        this.groupActionService = new CommandPanelGroupActionService(
                linkMutationService,
                toolInventoryService,
                feedbackService,
                this.groupService
        );
    }

    void applyLink(Player player,
                   String toolId,
                   TwCommandItemConfig config,
                   UUID npcUuid) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
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
                    stack,
                    (livePlayer, liveStore, liveTarget) -> applyDeferredLink(
                            livePlayer,
                            liveStore,
                            liveTarget,
                            toolId,
                            config
                    )
            );
            resultHolder[0] = result;
            return result.updatedItem != null ? result.updatedItem : stack;
        });
        LinkToggleResult result = resultHolder[0];
        if (result != null && result.pending) {
            return;
        }
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

    private void applyDeferredLink(Player player,
                                   Store<EntityStore> store,
                                   Ref<EntityStore> targetRef,
                                   String toolId,
                                   TwCommandItemConfig config) {
        LinkToggleResult[] resultHolder = new LinkToggleResult[1];
        boolean mutated = toolInventoryService.mutateToolStack(player, toolId, stack -> {
            LinkToggleResult result = linkMutationService.tryToggleLink(
                    player,
                    store,
                    targetRef,
                    toolId,
                    config,
                    stack,
                    null
            );
            resultHolder[0] = result;
            return result != null && result.updatedItem != null ? result.updatedItem : stack;
        });
        LinkToggleResult result = resultHolder[0];
        if (!mutated || result == null || !result.toggled || result.updatedItem == null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.failed");
            return;
        }
        if (!result.linked) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.link.alreadyLinked");
        } else if (result.active) {
            feedbackService.showSuccessKey(player, "tamework.ui.notifications.command.link.success", result.npcName);
        } else {
            feedbackService.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.link.successInactive",
                    result.npcName
            );
        }
    }

    void applyToggleActive(Player player,
                           String toolId,
                           TwCommandItemConfig config,
                           UUID npcUuid) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
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
                             TwCommandItemConfig config,
                             UUID npcUuid) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
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
                                 TwCommandItemConfig config,
                                 boolean enabled) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)) {
            return;
        }
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setAutoLinkEnabled(stack, enabled)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.autoLinkUpdateFailed");
        }
    }

    void applySetActiveHighlightEnabled(Player player,
                                        String toolId,
                                        TwCommandItemConfig config,
                                        boolean enabled) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)) {
            return;
        }
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> panelPreferenceService.setActiveHighlightEnabled(stack, enabled)
        );
        if (!updated && player != null) {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.panel.activeHighlightUpdateFailed"
            );
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
        boolean updated = trySetSelectedFilterText(player, toolId, value);
        if (!updated && player != null) {
            feedbackService.showWarningKey(player, "tamework.ui.notifications.command.panel.filterUpdateFailed");
        }
    }

    boolean trySetSelectedFilterText(
            Player player,
            String toolId,
            String value
    ) {
        return toolInventoryService.mutateToolStack(
                player, toolId,
                stack -> panelPreferenceService.applySelectedFilterText(
                        stack, value));
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
                                TwCommandItemConfig config,
                                UUID npcUuid,
                                String groupId) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)) {
            return;
        }
        groupActionService.applySetLinkedNpcGroup(player, toolId, npcUuid, groupId);
    }

    void applyCreateGroup(Player player, String toolId, String name, String colorHex) {
        groupActionService.applyCreateGroup(player, toolId, name, colorHex);
    }

    void applyRenameGroup(Player player, String toolId, String groupId, String name) {
        groupActionService.applyRenameGroup(player, toolId, groupId, name);
    }

    void applyRecolorGroup(Player player, String toolId, String groupId, String colorHex) {
        groupActionService.applyRecolorGroup(player, toolId, groupId, colorHex);
    }

    void applyDeleteGroup(Player player, String toolId, String groupId) {
        boolean updated = toolInventoryService.mutateToolStack(
                player,
                toolId,
                stack -> {
                    ItemStack withoutGroup = groupService.deleteGroup(stack, groupId);
                    return withoutGroup == stack
                            ? stack
                            : clearGroupAssignments(withoutGroup, groupId);
                }
        );
        if (player == null) {
            return;
        }
        if (updated) {
            feedbackService.showSuccessKey(
                    player,
                    "tamework.ui.notifications.command.group.deleted"
            );
        } else {
            feedbackService.showWarningKey(
                    player,
                    "tamework.ui.notifications.command.group.deleteFailed"
            );
        }
    }

    private ItemStack clearGroupAssignments(ItemStack stack, String groupId) {
        if (stack == null || stack.isEmpty() || groupId == null || groupId.isBlank()) {
            return stack;
        }
        List<LinkedNpcRecord> records = linkMutationService.readLinkedNpcRecords(stack);
        ArrayList<LinkedNpcRecord> updated = new ArrayList<>(records.size());
        boolean changed = false;
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            if (record.groupId != null && record.groupId.equalsIgnoreCase(groupId.trim())) {
                updated.add(new LinkedNpcRecord(
                        record.npcUuid,
                        record.profileId,
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
            } else {
                updated.add(record);
            }
        }
        return changed ? linkMutationService.writeLinkedNpcRecords(stack, updated) : stack;
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
        if (!enabled && breeding.getManualBreedingUntilMs() != 0L) {
            breeding.clearManualBreedingReady();
            changed = true;
        }
        if (changed) {
            store.putComponent(npcRef, breedingType, breeding);
        }
    }
}
