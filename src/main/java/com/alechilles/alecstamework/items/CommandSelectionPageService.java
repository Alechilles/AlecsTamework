package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.Map;
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
    private final CommandPanelFeatureActionService featureActions;
    private final BondedCompanionPanelActionRouter bondedActions;

    CommandSelectionPageService(CommandToolInventoryService toolInventoryService,
                                CommandGroupAssignPageService groupAssignPageService,
                                CommandResolutionService resolutionService,
                                CommandPanelActionService panelActionService,
                                CommandTalentPageService talentPageService) {
        this(
                toolInventoryService,
                groupAssignPageService,
                resolutionService,
                panelActionService,
                talentPageService,
                null,
                null,
                null
        );
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions
    ) {
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions
    ) {
        this.toolInventoryService = toolInventoryService;
        this.groupAssignPageService = groupAssignPageService;
        this.resolutionService = resolutionService;
        this.panelActionService = panelActionService;
        this.talentPageService = talentPageService;
        this.featureActions = featureActions;
        this.bondedActions = bondedActions;
    }

    /**
     * Opens a page whose generic callbacks are checked against the physical
     * tool again when the player presses them.
     */
    boolean open(Player player,
                 Store<EntityStore> store,
                 TwCommandItemConfig config,
                 ItemStack working,
                 String toolId,
                 Actions actions,
                 BooleanSupplier genericCallbackAuthority) {
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
                player, store, uiPlayerRef, config, working, toolId, actions,
                genericCallbackAuthority != null
                        ? genericCallbackAuthority : () -> false
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
                                                    Store<EntityStore> store,
                                                    PlayerRef uiPlayerRef,
                                                    TwCommandItemConfig config,
                                                    ItemStack working,
                                                    String toolId,
                                                    Actions actions,
                                                    BooleanSupplier genericCallbackAuthority) {
        String selectedId = working != null
                ? working.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING)
                : null;
        boolean requireUnlinkConfirm = resolveRequireUnlinkConfirm();
        boolean recallTeleportingEnabled = CommandTravelSettings.isRecallTeleportingEnabled();
        boolean genericRosterActions =
                CommandRosterStorageBoundary.allowsGenericRosterActions(config);
        BooleanSupplier guardedGenericCallbacks = genericRosterActions
                ? genericCallbackAuthority : () -> false;
        // Sort, filter, and panel-mode metadata are safe local presentation
        // preferences for both roster types. Only a stale generic page needs
        // the physical-tool revalidation gate.
        BooleanSupplier panelPreferenceAuthority = genericRosterActions
                ? genericCallbackAuthority : () -> true;
        Consumer<UUID> ignoreUuid = ignored -> { };
        Consumer<Boolean> ignoreBoolean = ignored -> { };
        Consumer<String> ignoreString = ignored -> { };
        Runnable ignoreAction = () -> { };
        CoherentPanelSnapshot panelSnapshot = new CoherentPanelSnapshot(
                () -> toolInventoryService.buildLinkedPanelSnapshotForTool(
                        player, toolId, config
                )
        );
        return new TameworkCommandSelectionPage(
                uiPlayerRef,
                config,
                selectedId,
                requireUnlinkConfirm,
                panelSnapshot::refreshEntries,
                panelSnapshot::refreshEntries,
                panelSnapshot::featurePresentations,
                () -> toolInventoryService.resolvePanelModeValueForTool(player, toolId, config),
                genericRosterActions
                        ? () -> toolInventoryService.resolvePanelAutoLinkEnabledForTool(player, toolId)
                        : () -> false,
                () -> toolInventoryService.resolvePanelRadiusLabelForTool(player, toolId, config),
                () -> toolInventoryService.resolvePanelSortValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterModeValueForTool(player, toolId),
                () -> toolInventoryService.resolvePanelFilterInputForTool(player, toolId),
                genericRosterActions
                        ? () -> groupAssignPageService.resolveGroupActivationDropdownEntries(player, toolId)
                        : java.util.List::of,
                genericRosterActions
                        ? () -> groupAssignPageService.resolveGroupActivationValue(player, toolId)
                        : () -> "",
                genericRosterActions
                        ? () -> groupAssignPageService.resolveGroupDropdownEntries(player, toolId)
                        : java.util.List::of,
                command -> recallTeleportingEnabled || !resolutionService.isRecallCommand(command),
                recallTeleportingEnabled,
                genericRosterActions
                        ? guardedUuid(guardedGenericCallbacks,
                                npcUuid -> panelActionService.applyLink(
                                        player, toolId, config, npcUuid))
                        : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.unlink()) : ignoreUuid,
                genericRosterActions
                        ? guardedUuid(guardedGenericCallbacks,
                                npcUuid -> panelActionService.applyToggleActive(
                                        player, toolId, config, npcUuid))
                        : ignoreUuid,
                genericRosterActions
                        ? guardedUuid(guardedGenericCallbacks,
                                npcUuid -> panelActionService.applyToggleBreeding(
                                        player, toolId, config, npcUuid))
                        : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.release()) : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.cull()) : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.respawn()) : ignoreUuid,
                npcUuid -> applyFeatureAction(
                        player, store, config, npcUuid, FeatureAction.SUMMON,
                        panelSnapshot
                ),
                npcUuid -> applyFeatureAction(
                        player, store, config, npcUuid, FeatureAction.DISMISS,
                        panelSnapshot
                ),
                npcUuid -> applyFeatureAction(
                        player, store, config, npcUuid, FeatureAction.REVIVE,
                        panelSnapshot
                ),
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.locate()) : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.recall()) : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.setHome()) : ignoreUuid,
                genericRosterActions ? guardedUuid(guardedGenericCallbacks,
                        actions.returnHome()) : ignoreUuid,
                genericRosterActions
                        ? guardedUuid(guardedGenericCallbacks,
                                npcUuid -> talentPageService.openTalentPage(
                                player, toolId, npcUuid, actions.reopenMenu())
                        )
                        : ignoreUuid,
                guardedString(panelPreferenceAuthority,
                        value -> panelActionService.applySetPanelMode(
                                player, toolId, value)),
                genericRosterActions
                        ? guardedBoolean(guardedGenericCallbacks,
                                enabled -> panelActionService.applySetAutoLinkEnabled(
                                player, toolId, config, enabled)
                        )
                        : ignoreBoolean,
                guardedAction(guardedGenericCallbacks,
                        () -> panelActionService.applyAdjustPanelRadius(
                                player, toolId, config, false)),
                guardedAction(guardedGenericCallbacks,
                        () -> panelActionService.applyAdjustPanelRadius(
                                player, toolId, config, true)),
                genericRosterActions ? guardedAction(guardedGenericCallbacks,
                        actions.manageGroups()) : ignoreAction,
                guardedString(panelPreferenceAuthority,
                        value -> panelActionService.applySetSort(player, toolId, value)),
                guardedString(panelPreferenceAuthority,
                        value -> panelActionService.applySetFilterMode(player, toolId, value)),
                guardedString(panelPreferenceAuthority,
                        value -> panelActionService.applySetSelectedFilterText(player, toolId, value)),
                guardedAction(panelPreferenceAuthority,
                        () -> panelActionService.applyClearFilters(player, toolId)),
                genericRosterActions
                        ? guardedString(guardedGenericCallbacks,
                                value -> groupAssignPageService.applyGroupActivation(
                                player, toolId, config, value)
                        )
                        : ignoreString,
                genericRosterActions
                        ? guardedPair(guardedGenericCallbacks,
                                (npcUuid, groupId) -> groupAssignPageService.applyGroupAssignment(
                                player, toolId, config, npcUuid, groupId)
                        )
                        : (ignoredUuid, ignoredGroup) -> { },
                genericRosterActions ? guardedString(guardedGenericCallbacks,
                        actions.selectCommand()) : ignoreString
        );
    }

    private static Consumer<UUID> guardedUuid(BooleanSupplier authority,
                                               Consumer<UUID> callback) {
        return uuid -> {
            if (authority.getAsBoolean()) {
                callback.accept(uuid);
            }
        };
    }

    private static Consumer<Boolean> guardedBoolean(BooleanSupplier authority,
                                                     Consumer<Boolean> callback) {
        return value -> {
            if (authority.getAsBoolean()) {
                callback.accept(value);
            }
        };
    }

    private static Consumer<String> guardedString(BooleanSupplier authority,
                                                   Consumer<String> callback) {
        return value -> {
            if (authority.getAsBoolean()) {
                callback.accept(value);
            }
        };
    }

    private static BiConsumer<UUID, String> guardedPair(
            BooleanSupplier authority, BiConsumer<UUID, String> callback) {
        return (uuid, value) -> {
            if (authority.getAsBoolean()) {
                callback.accept(uuid, value);
            }
        };
    }

    private static Runnable guardedAction(BooleanSupplier authority,
                                          Runnable callback) {
        return () -> {
            if (authority.getAsBoolean()) {
                callback.run();
            }
        };
    }

    /**
     * Keeps card data and roster actions aligned for one UI refresh pass.
     * The page requests entries before feature presentation, so refreshing
     * entries atomically replaces both views.
     */
    private static final class CoherentPanelSnapshot {
        private final java.util.function.Supplier<
                CommandPanelEntrySourceService.CommandPanelSnapshot> supplier;
        private CommandPanelEntrySourceService.CommandPanelSnapshot current =
                new CommandPanelEntrySourceService.CommandPanelSnapshot(
                        java.util.List.of(), Map.of()
                );

        private CoherentPanelSnapshot(
                java.util.function.Supplier<
                        CommandPanelEntrySourceService.CommandPanelSnapshot> supplier
        ) {
            this.supplier = supplier;
        }

        private java.util.List<com.alechilles.alecstamework.ui.LinkedNpcEntry>
        refreshEntries() {
            CommandPanelEntrySourceService.CommandPanelSnapshot next =
                    supplier.get();
            current = next != null
                    ? next
                    : new CommandPanelEntrySourceService.CommandPanelSnapshot(
                            java.util.List.of(), Map.of()
                    );
            return current.entries();
        }

        private Map<UUID, CommandPanelFeaturePresentation>
        featurePresentations() {
            return current.featurePresentations();
        }

        private CommandPanelFeaturePresentation presentation(UUID id) {
            return id == null ? null : current.featurePresentations().get(id);
        }
    }

    private void applyFeatureAction(
            Player player,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            FeatureAction action,
            CoherentPanelSnapshot snapshot
    ) {
        CommandPanelFeaturePresentation feature = snapshot == null
                ? null : snapshot.presentation(presentationUuid);
        routeFeatureAction(player, store, config, presentationUuid,
                feature, action);
    }

    FeatureRoute routeFeatureAction(
            Player player,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            CommandPanelFeaturePresentation feature,
            FeatureAction action
    ) {
        if (config != null && config.usesBondedCompanionRoster()) {
            if (bondedActions != null) {
                bondedActions.route(player, store, config, feature,
                        switch (action) {
                    case SUMMON -> BondedCompanionPanelActionService.Action.SUMMON;
                    case DISMISS -> BondedCompanionPanelActionService.Action.STORE;
                    case REVIVE -> BondedCompanionPanelActionService.Action.REVIVE;
                        });
            }
            return FeatureRoute.BONDED;
        }
        if (featureActions == null) {
            return FeatureRoute.IGNORED;
        }
        switch (action) {
            case SUMMON -> featureActions.summon(
                    player, config, presentationUuid
            );
            case DISMISS -> featureActions.dismiss(
                    player, config, presentationUuid
            );
            case REVIVE -> featureActions.revive(
                    player, config, presentationUuid
            );
        }
        return FeatureRoute.GENERIC;
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

    enum FeatureAction {
        SUMMON,
        DISMISS,
        REVIVE
    }

    enum FeatureRoute { BONDED, GENERIC, IGNORED }
}
