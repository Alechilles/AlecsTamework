package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
            feedbackService.showWarningKey(player, result == null
                    ? "tamework.ui.notifications.command.link.failed" : result.failureMessageKey);
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
            feedbackService.showWarningKey(player, result == null
                    ? "tamework.ui.notifications.command.link.failed" : result.failureMessageKey);
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
        applyToggleActive(player, toolId, config, npcUuid, null);
    }

    /**
     * Applies a selection change for a row whose owned record was resolved by
     * the server from the current companion projection.
     */
    void applyToggleActive(Player player,
                           String toolId,
                           TwCommandItemConfig config,
                           UUID npcUuid,
                           LinkedNpcRecord ownedRecord) {
        if (!CommandRosterStorageBoundary.allowsGenericRosterActions(config)
                || player == null || toolId == null || toolId.isBlank()
                || npcUuid == null) {
            return;
        }
        CommandLinkMutationService.ActiveToggleResult[] resultHolder =
                new CommandLinkMutationService.ActiveToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            LinkedNpcRecord existing = linkMutationService.findLinkedNpcRecord(
                    linkMutationService.readLinkedNpcRecords(stack), npcUuid);
            LinkedNpcRecord resolvedOwnedRecord = toolInventoryService.resolveOwnedSelectionRecord(
                    player, toolId, config, npcUuid);
            // Deselecting is always allowed; selecting must still belong to this player.
            if ((existing == null || !existing.active) && resolvedOwnedRecord == null) return stack;
            CommandLinkMutationService.ActiveToggleResult result =
                    linkMutationService.toggleLinkedNpcActive(
                            stack, npcUuid, config, resolvedOwnedRecord);
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
                        ? "tamework.command.selection.selected"
                        : "tamework.command.selection.deselected",
                resolveFeedbackName(player, result.updatedItem, npcUuid)
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
        LinkedNpcRecord owned = toolInventoryService.resolveOwnedSelectionRecord(player, toolId, config, npcUuid);
        if (owned == null) return;
        var entry = toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config).stream()
                .filter(row -> npcUuid.equals(row.npcUuid()) && row.loaded() && row.breedingAvailable()).findFirst().orElse(null);
        if (entry == null) return;
        CommandLinkMutationService.BreedingToggleResult[] resultHolder =
                new CommandLinkMutationService.BreedingToggleResult[1];
        toolInventoryService.mutateToolStack(player, toolId, stack -> {
            var records = new java.util.ArrayList<>(linkMutationService.readLinkedNpcRecords(stack));
            var existing = linkMutationService.findLinkedNpcRecord(records, npcUuid);
            if (existing == null) records.add(owned.withActive(false).withBreedingEnabled(entry.breedingEnabled()));
            else records.set(records.indexOf(existing), existing.withBreedingEnabled(entry.breedingEnabled()));
            stack = linkMutationService.writeLinkedNpcRecords(stack, records);
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
                        : "tamework.ui.notifications.command.toggleBreeding.disabled",
                resolveFeedbackName(player, result.updatedItem, npcUuid)
        );
    }

    String resolveFeedbackName(Player player, ItemStack stack, UUID npcUuid) {
        String language = player.getPlayerRef() != null ? player.getPlayerRef().getLanguage() : null;
        for (LinkedNpcRecord record : linkMutationService.readLinkedNpcRecords(stack)) {
            if (npcUuid.equals(record.npcUuid)) {
                return resolveFeedbackName(language, record);
            }
        }
        return LocalizedText.resolve(language, "tamework.ui.nameInput.defaultNpcName");
    }

    private static String resolveFeedbackName(String language, LinkedNpcRecord record) {
        if (record.cachedDisplayName != null && !record.cachedDisplayName.isBlank()
                && !record.cachedDisplayName.equals(record.cachedNameKey)) {
            return record.cachedDisplayName;
        }
        String fallback = record.cachedRoleId != null && !record.cachedRoleId.isBlank()
                ? record.cachedRoleId
                : LocalizedText.resolve(language, "tamework.ui.nameInput.defaultNpcName");
        return LocalizedText.resolveConfigValue(language, record.cachedNameKey, fallback);
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
        if (toolInventoryService.resolveOwnedSelectionRecord(player, toolId, config, npcUuid) == null) return;
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
        groupActionService.applyDeleteGroup(player, toolId, groupId);
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
