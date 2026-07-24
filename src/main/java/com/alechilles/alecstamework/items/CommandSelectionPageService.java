package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds and opens the command selection page from focused panel services and bound actions.
 */
final class CommandSelectionPageService {
    private final CommandToolInventoryService toolInventoryService;
    private final CommandGroupAssignPageService groupAssignPageService;
    private final CommandResolutionService resolutionService;
    private final CommandPanelActionService panelActionService;
    private final CommandTalentPageService talentPageService;

    CommandSelectionPageService(CommandToolInventoryService toolInventoryService,
                                CommandGroupAssignPageService groupAssignPageService,
                                CommandResolutionService resolutionService,
                                CommandPanelActionService panelActionService,
                                CommandTalentPageService talentPageService) {
        this.toolInventoryService = toolInventoryService;
        this.groupAssignPageService = groupAssignPageService;
        this.resolutionService = resolutionService;
        this.panelActionService = panelActionService;
        this.talentPageService = talentPageService;
    }

    boolean open(Player player,
                 Store<EntityStore> store,
                 TwCommandItemConfig config,
                 ItemStack working,
                 String toolId,
                 Actions actions) {
        if (!canOpen(player, store, config, toolId, actions)) {
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return false;
        }
        TameworkCommandSelectionPage page = createPage(
                player, uiPlayerRef, config, working, toolId, actions
        );
        return openPage(player, playerRef, store, page);
    }

    private boolean canOpen(Player player,
                            Store<EntityStore> store,
                            TwCommandItemConfig config,
                            String toolId,
                            Actions actions) {
        if (player == null || store == null || config == null || actions == null
                || toolId == null || toolId.isBlank() || player.getPageManager() == null) {
            return false;
        }
        TwCommandItemConfig.CommandEntry[] commands = config.getCommandList();
        return commands != null && commands.length > 0;
    }

    private TameworkCommandSelectionPage createPage(Player player,
                                                    PlayerRef uiPlayerRef,
                                                    TwCommandItemConfig config,
                                                    ItemStack working,
                                                    String toolId,
                                                    Actions actions) {
        String selectedId = working != null
                ? working.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING)
                : null;
        boolean requireUnlinkConfirm = resolveRequireUnlinkConfirm();
        boolean recallTeleportingEnabled = CommandTravelSettings.isRecallTeleportingEnabled();
        return new TameworkCommandSelectionPage(
                uiPlayerRef,
                config,
                selectedId,
                requireUnlinkConfirm,
                () -> toolInventoryService.buildLinkedPanelEntriesForTool(player, toolId, config),
                () -> toolInventoryService.buildLinkedPanelBaseEntriesForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelModeValueForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelAutoLinkEnabledForTool(player, toolId),
                () -> toolInventoryService.resolvePanelRadiusLabelForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelSortValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterModeValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterInputForTool(player, toolId),
                () -> groupAssignPageService.resolveGroupActivationDropdownEntries(player, toolId),
                () -> groupAssignPageService.resolveGroupActivationValue(player, toolId),
                () -> groupAssignPageService.resolveGroupDropdownEntries(player, toolId),
                command -> recallTeleportingEnabled || !resolutionService.isRecallCommand(command),
                recallTeleportingEnabled,
                npcUuid -> panelActionService.applyLink(player, toolId, config, npcUuid),
                actions.unlink(),
                npcUuid -> panelActionService.applyToggleActive(player, toolId, config, npcUuid),
                npcUuid -> panelActionService.applyToggleBreeding(player, toolId, npcUuid),
                actions.release(),
                actions.cull(),
                actions.respawn(),
                actions.locate(),
                actions.recall(),
                actions.setHome(),
                actions.returnHome(),
                npcUuid -> talentPageService.openTalentPage(
                        player, toolId, npcUuid, actions.reopenMenu()),
                value -> panelActionService.applySetPanelMode(player, toolId, value),
                enabled -> panelActionService.applySetAutoLinkEnabled(player, toolId, enabled),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, false),
                () -> panelActionService.applyAdjustPanelRadius(player, toolId, config, true),
                actions.manageGroups(),
                value -> panelActionService.applySetSort(player, toolId, value),
                value -> panelActionService.applySetFilterMode(player, toolId, value),
                value -> panelActionService.applySetSelectedFilterText(player, toolId, value),
                () -> panelActionService.applyClearFilters(player, toolId),
                value -> groupAssignPageService.applyGroupActivation(player, toolId, value),
                (npcUuid, groupId) -> groupAssignPageService.applyGroupAssignment(
                        player, toolId, config, npcUuid, groupId),
                actions.selectCommand()
        );
    }

    private boolean resolveRequireUnlinkConfirm() {
        com.alechilles.alecstamework.config.assets.TwGlobalConfig globalConfig =
                com.alechilles.alecstamework.config.assets.TwGlobalConfig.resolveActive();
        return globalConfig == null || globalConfig.isCommandLinkedPanelRequireUnlinkConfirm();
    }

    private boolean openPage(Player player,
                             Ref<EntityStore> playerRef,
                             Store<EntityStore> store,
                             TameworkCommandSelectionPage page) {
        try {
            player.getPageManager().openCustomPage(playerRef, store, page);
            return true;
        } catch (Throwable throwable) {
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_open_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkCommandSelectionPage",
                            "command_item",
                            "open",
                            "Failed to open command selection page."
                    ).build()
            );
            return false;
        }
    }

    record Actions(Consumer<UUID> unlink,
                   Consumer<UUID> release,
                   Consumer<UUID> cull,
                   Consumer<UUID> respawn,
                   Consumer<UUID> locate,
                   Consumer<UUID> recall,
                   Consumer<UUID> setHome,
                   Consumer<UUID> returnHome,
                   Runnable manageGroups,
                   Runnable reopenMenu,
                   Consumer<String> selectCommand) {
    }
}
