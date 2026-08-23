package com.alechilles.alecstamework.items;

import static com.alechilles.alecstamework.items.CommandSelectionCallbackGuards.*;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.ui.CommandPanelFeaturePresentation;
import com.alechilles.alecstamework.ui.LinkedNpcPanelFeatureAction;
import com.alechilles.alecstamework.ui.LinkedPanelRefreshSignalSource;
import com.alechilles.alecstamework.ui.TameworkCommandSelectionPage;
import com.alechilles.alecstamework.ui.CommandActiveHighlightBinding;
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
import java.util.concurrent.CompletionStage;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Builds and opens the command selection page from focused panel services and bound actions.
 */
final class CommandSelectionPageService {
    private static final Logger LOGGER = Logger.getLogger(CommandSelectionPageService.class.getName());
    private final CommandToolInventoryService toolInventoryService;
    private final CommandGroupAssignPageService groupAssignPageService;
    private final CommandResolutionService resolutionService;
    private final CommandPanelActionService panelActionService;
    private final CommandTalentPageService talentPageService;
    private final CommandPanelFeatureActionService featureActions;
    private final BondedCompanionPanelActionRouter bondedActions;
    private final BondedCompanionTalentPageService bondedTalentPages;
    private final FlightToggleAction flightToggleActions;
    private final LinkedFlightToggleAction linkedFlightToggleActions;
    private final EventPlayerResolver flightTogglePlayers;
    private ShoulderRideAction shoulderRideActions;
    private LinkedShoulderRideAction linkedShoulderRideActions;
    @javax.annotation.Nullable private final BondedCompanionPanelRefreshSignalSource bondedRefreshSignals;

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
                featureActions, null, null);
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
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, bondedActions, null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions,
            BondedCompanionTalentPageService bondedTalentPages
    ) {
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, bondedActions, bondedTalentPages, null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions,
            BondedCompanionTalentPageService bondedTalentPages,
            FlightToggleAction flightToggleActions
    ) {
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, bondedActions, bondedTalentPages,
                flightToggleActions,
                BondedCompanionPanelActionRouter::resolvePlayerFromEvent, null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions,
            BondedCompanionTalentPageService bondedTalentPages,
            FlightToggleAction flightToggleActions,
            EventPlayerResolver flightTogglePlayers
    ) {
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, bondedActions, bondedTalentPages,
                flightToggleActions, flightTogglePlayers, null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions,
            BondedCompanionTalentPageService bondedTalentPages,
            FlightToggleAction flightToggleActions,
            EventPlayerResolver flightTogglePlayers,
            @javax.annotation.Nullable BondedCompanionPanelRefreshSignalSource bondedRefreshSignals
    ) {
        this(toolInventoryService, groupAssignPageService, resolutionService,
                panelActionService, talentPageService, featurePresentations,
                featureActions, bondedActions, bondedTalentPages,
                flightToggleActions, flightTogglePlayers, bondedRefreshSignals,
                null);
    }

    CommandSelectionPageService(
            CommandToolInventoryService toolInventoryService,
            CommandGroupAssignPageService groupAssignPageService,
            CommandResolutionService resolutionService,
            CommandPanelActionService panelActionService,
            CommandTalentPageService talentPageService,
            CommandPanelFeaturePresentationSource featurePresentations,
            CommandPanelFeatureActionService featureActions,
            BondedCompanionPanelActionRouter bondedActions,
            BondedCompanionTalentPageService bondedTalentPages,
            FlightToggleAction flightToggleActions,
            EventPlayerResolver flightTogglePlayers,
            @javax.annotation.Nullable BondedCompanionPanelRefreshSignalSource bondedRefreshSignals,
            LinkedFlightToggleAction linkedFlightToggleActions
    ) {
        this.toolInventoryService = toolInventoryService;
        this.groupAssignPageService = groupAssignPageService;
        this.resolutionService = resolutionService;
        this.panelActionService = panelActionService;
        this.talentPageService = talentPageService;
        this.featureActions = featureActions;
        this.bondedActions = bondedActions;
        this.bondedTalentPages = bondedTalentPages;
        this.flightToggleActions = flightToggleActions;
        this.linkedFlightToggleActions = linkedFlightToggleActions;
        this.flightTogglePlayers = flightTogglePlayers;
        this.bondedRefreshSignals = bondedRefreshSignals;
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
                 BooleanSupplier genericCallbackAuthority,
                 BondedLifecycleAuthority bondedLifecycleAuthority) {
        if (!canOpen(player, store, config, toolId, actions)) {
            LOGGER.warning("[tw-command-menu] selection page prerequisites failed: config="
                    + (config == null ? "null" : config.getId())
                    + " commands=" + commandCount(config)
                    + " toolId=" + toolId);
            return false;
        }
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (playerRef == null || !playerRef.isValid()
                || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            LOGGER.warning("[tw-command-menu] selection page player references are invalid: toolId="
                    + toolId);
            return false;
        }
        TameworkCommandSelectionPage page = createPage(
                player, store, uiPlayerRef, config, working, toolId, actions,
                genericCallbackAuthority != null
                        ? genericCallbackAuthority : () -> false,
                bondedLifecycleAuthority != null
                        ? bondedLifecycleAuthority : ignored -> false
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
                                                    BooleanSupplier genericCallbackAuthority,
                                                    BondedLifecycleAuthority bondedLifecycleAuthority) {
        return buildPage(player, store, uiPlayerRef, config, working, toolId,
                actions, genericCallbackAuthority, bondedLifecycleAuthority);
    }

    /**
     * Builds the bound presentation after {@link #createPage} has kept the
     * page-opening orchestration separate from its callback wiring.
     */
    private TameworkCommandSelectionPage buildPage(Player player,
                                                   Store<EntityStore> store,
                                                   PlayerRef uiPlayerRef,
                                                   TwCommandItemConfig config,
                                                   ItemStack working,
                                                   String toolId,
                                                   Actions actions,
                                                   BooleanSupplier genericCallbackAuthority,
                                                   BondedLifecycleAuthority bondedLifecycleAuthority) {
        boolean genericRosterActions = CommandRosterStorageBoundary
                .allowsGenericRosterActions(config);
        CommandPanelSnapshotState panelSnapshot = new CommandPanelSnapshotState(
                () -> toolInventoryService.buildLinkedPanelSnapshotForTool(
                        player, toolId, config
                )
        );
        LinkedPanelRefreshSignalSource pageSignals = pageSignals(player, config);
        PageContext context = new PageContext(player, uiPlayerRef, config,
                toolId, actions, player.getUuid(), genericRosterActions,
                genericRosterActions ? genericCallbackAuthority : () -> false,
                genericRosterActions ? genericCallbackAuthority : () -> true,
                bondedLifecycleAuthority, panelSnapshot,
                pageSignals,
                working == null ? null : working.getFromMetadataOrNull(
                        TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING),
                resolveRequireUnlinkConfirm(),
                CommandTravelSettings.isRecallTeleportingEnabled());
        return createSelectionPage(context, buildNpcCallbacks(context),
                buildFeatureCallbacks(context), buildPanelCallbacks(context));
    }

    LinkedPanelRefreshSignalSource pageSignals(Player player,
                                                TwCommandItemConfig config) {
        return pageSignals(player == null ? null : player.getUuid(), config);
    }

    LinkedPanelRefreshSignalSource pageSignals(UUID ownerUuid,
                                                TwCommandItemConfig config) {
        return ownerUuid != null && config != null && config.usesBondedCompanionRoster()
                && bondedRefreshSignals != null
                ? bondedRefreshSignals.forRoster(ownerUuid, config.getBondedRosterId())
                : LinkedPanelRefreshSignalSource.none();
    }

    private TameworkCommandSelectionPage createSelectionPage(
            PageContext context, NpcCallbacks npcCallbacks,
            FeatureCallbacks featureCallbacks, PanelCallbacks panelCallbacks) {
        TameworkCommandSelectionPage page = new TameworkCommandSelectionPage(
                context.uiPlayerRef(), context.config(), context.selectedId(),
                context.requireUnlinkConfirm(), context.snapshot()::refreshEntries,
                context.snapshot()::refreshEntries, context.snapshot()::featurePresentations,
                context.snapshot()::emptyStateKey,
                context.config().usesBondedCompanionRoster()
                        ? () -> TameworkCommandSelectionPage.PANEL_MODE_LINKED
                        : () -> toolInventoryService.resolvePanelModeValueForTool(
                        context.player(), context.toolId(), context.config()),
                context.config().usesBondedCompanionRoster()
                        ? () -> true : context.genericRosterActions()
                        ? () -> toolInventoryService.resolvePanelAutoLinkEnabledForTool(
                                context.player(), context.toolId())
                        : () -> false,
                () -> toolInventoryService.resolvePanelRadiusLabelForTool(
                        context.player(), context.toolId(), context.config()),
                () -> toolInventoryService.resolvePanelSortValueForTool(
                        context.player(), context.toolId()),
                () -> toolInventoryService.resolvePanelFilterModeValueForTool(
                        context.player(), context.toolId()),
                () -> toolInventoryService.resolvePanelFilterInputForTool(
                        context.player(), context.toolId()),
                context.genericRosterActions()
                        ? () -> groupAssignPageService.resolveGroupActivationDropdownEntries(
                                context.player(), context.toolId())
                        : java.util.List::of,
                context.genericRosterActions()
                        ? () -> groupAssignPageService.resolveGroupActivationValue(
                                context.player(), context.toolId())
                        : () -> "",
                context.genericRosterActions()
                        ? () -> groupAssignPageService.resolveGroupDropdownEntries(
                                context.player(), context.toolId())
                        : java.util.List::of,
                command -> context.recallTeleportingEnabled()
                        || !resolutionService.isRecallCommand(command),
                context.recallTeleportingEnabled(), npcCallbacks.link(),
                npcCallbacks.unlink(), npcCallbacks.toggleActive(),
                npcCallbacks.toggleBreeding(), npcCallbacks.release(),
                npcCallbacks.cull(), npcCallbacks.respawn(),
                featureCallbacks.summon(), featureCallbacks.dismiss(),
                featureCallbacks.revive(), featureCallbacks.abandon(),
                featureCallbacks.flightToggle(),
                npcCallbacks.locate(),
                npcCallbacks.recall(), npcCallbacks.setHome(),
                npcCallbacks.returnHome(), npcCallbacks.openTalents(),
                panelCallbacks.setMode(), panelCallbacks.setAutoLinkEnabled(),
                panelCallbacks.decreaseRadius(), panelCallbacks.increaseRadius(),
                panelCallbacks.manageGroups(), panelCallbacks.setSort(),
                panelCallbacks.setFilterMode(), panelCallbacks.setFilterText(),
                panelCallbacks.clearFilters(), panelCallbacks.setGroupActivation(),
                panelCallbacks.assignGroup(), npcCallbacks.selectCommand(),
                context.refreshSignals()
        );
        page.configureActiveHighlight(new CommandActiveHighlightBinding(
                context.genericRosterActions() && HytaleApiLevel.isUpdate6OrLater(),
                context.genericRosterActions()
                        ? () -> toolInventoryService.resolvePanelActiveHighlightEnabledForTool(
                                context.player(), context.toolId())
                        : () -> false,
                panelCallbacks.setActiveHighlightEnabled()
        ));
        page.configureShoulderRideCallback(shoulderRideCallback(context));
        page.configureHotswapAssignments(
                () -> toolInventoryService.findActiveToolStack(context.player(), context.toolId()),
                (slot, commandId) -> toolInventoryService.mutateActiveToolStack(
                        context.player(), context.toolId(), stack ->
                                new CommandHotswapAssignmentStore().write(stack, slot, commandId))
        );
        return page;
    }

    private NpcCallbacks buildNpcCallbacks(PageContext context) {
        Consumer<UUID> ignoredUuid = ignored -> { };
        Consumer<String> ignoredString = ignored -> { };
        BooleanSupplier authority = context.genericAuthority();
        if (!context.genericRosterActions()) {
            Consumer<UUID> openBondedTalents = context.config()
                    .usesBondedCompanionRoster()
                    ? uuid -> openBondedTalentPage(context, uuid)
                    : ignoredUuid;
            return new NpcCallbacks(ignoredUuid, ignoredUuid, ignoredUuid,
                    ignoredUuid, ignoredUuid, ignoredUuid, ignoredUuid,
                    ignoredUuid, ignoredUuid, ignoredUuid, ignoredUuid,
                    openBondedTalents, context.actions().selectCommand());
        }
        Player player = context.player();
        String toolId = context.toolId();
        TwCommandItemConfig config = context.config();
        return new NpcCallbacks(
                guardedUuid(authority, uuid -> panelActionService.applyLink(player, toolId, config, uuid)),
                guardedUuid(authority, context.actions().unlink()),
                guardedUuid(authority, uuid -> panelActionService.applyToggleActive(player, toolId, config, uuid)),
                guardedUuid(authority, uuid -> panelActionService.applyToggleBreeding(player, toolId, config, uuid)),
                guardedUuid(authority, context.actions().release()),
                guardedUuid(authority, context.actions().cull()),
                guardedUuid(authority, context.actions().respawn()),
                guardedUuid(authority, context.actions().locate()),
                guardedUuid(authority, context.actions().recall()),
                guardedUuid(authority, context.actions().setHome()),
                guardedUuid(authority, context.actions().returnHome()),
                guardedUuid(authority, uuid -> talentPageService.openTalentPage(
                        player, toolId, uuid, context.actions().reopenMenu())),
                guardedString(authority, context.actions().selectCommand()));
    }

    private void openBondedTalentPage(PageContext context, UUID presentationUuid) {
        if (bondedTalentPages == null || presentationUuid == null) {
            return;
        }
        CommandPanelFeaturePresentation feature = context.snapshot()
                .presentation(presentationUuid);
        if (feature != null && feature.bonded() != null) {
            bondedTalentPages.open(context.player(), feature.bonded(),
                    context.actions().reopenMenu());
        }
    }

    private FeatureCallbacks buildFeatureCallbacks(PageContext context) {
        return new FeatureCallbacks(
                featureCallback(context, FeatureAction.SUMMON),
                featureCallback(context, FeatureAction.DISMISS),
                featureCallback(context, FeatureAction.REVIVE),
                featureCallback(context, FeatureAction.ABANDON),
                flightToggleCallback(context));
    }

    private LinkedNpcPanelFeatureAction flightToggleCallback(PageContext context) {
        return (uuid, eventRef, eventStore) -> {
            if (context.config().usesBondedCompanionRoster()) {
                CommandPanelFeaturePresentation feature = context.snapshot().presentation(uuid);
                if (feature == null || feature.bonded() == null
                        || !BondedCompanionFlightToggleActionService
                        .isFlightToggleAvailable(feature.bonded())) return;
                routeFlightToggle(context.ownerUuid(), eventRef, eventStore,
                        context.config(), context.toolId(), feature.bonded(),
                        context.bondedLifecycleAuthority());
                return;
            }
            com.alechilles.alecstamework.ui.LinkedNpcEntry entry = context.snapshot()
                    .entry(uuid);
            if (!context.genericRosterActions() || !context.genericAuthority().getAsBoolean()
                    || entry == null || !entry.linked() || !entry.loaded()
                    || !entry.flightToggleAvailable()
                    || linkedFlightToggleActions == null) return;
            linkedFlightToggleActions.toggle(context.ownerUuid(), eventRef, eventStore,
                    context.toolId(), uuid);
        };
    }

    boolean routeFlightToggle(UUID ownerUuid, Ref<EntityStore> eventRef,
                              Store<EntityStore> eventStore,
                              TwCommandItemConfig config, String itemId,
                              com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation row,
                              BondedLifecycleAuthority authority) {
        if (flightToggleActions == null || config == null
                || !config.usesBondedCompanionRoster() || row == null
                || !BondedCompanionFlightToggleActionService.isFlightToggleAvailable(row)) return false;
        Player player = flightTogglePlayers == null ? null
                : flightTogglePlayers.resolve(ownerUuid, eventRef, eventStore);
        if (player == null || authority == null || !authority.allows(player)) return false;
        return flightToggleActions.toggle(ownerUuid, eventRef, eventStore, itemId, row);
    }

    private LinkedNpcPanelFeatureAction featureCallback(
            PageContext context, FeatureAction action) {
        return (uuid, eventRef, eventStore) -> applyFeatureAction(
                context.ownerUuid(), eventRef, eventStore, context.config(), uuid,
                action, context.snapshot(), context.bondedLifecycleAuthority());
    }

    private PanelCallbacks buildPanelCallbacks(PageContext context) {
        Consumer<String> ignoredString = ignored -> { };
        Consumer<Boolean> ignoredBoolean = ignored -> { };
        Runnable ignoredAction = () -> { };
        Player player = context.player();
        String toolId = context.toolId();
        BooleanSupplier genericAuthority = context.genericAuthority();
        BooleanSupplier preferenceAuthority = context.preferenceAuthority();
        return new PanelCallbacks(
                context.config().usesBondedCompanionRoster() ? ignoredString
                        : guardedString(preferenceAuthority, value -> panelActionService.applySetPanelMode(player, toolId, value)),
                context.genericRosterActions() ? guardedBoolean(genericAuthority,
                        enabled -> panelActionService.applySetAutoLinkEnabled(
                                player, toolId, context.config(), enabled)) : ignoredBoolean,
                context.genericRosterActions() ? guardedBoolean(genericAuthority,
                        enabled -> panelActionService.applySetActiveHighlightEnabled(
                                player, toolId, context.config(), enabled)) : ignoredBoolean,
                guardedAction(genericAuthority, () -> panelActionService.applyAdjustPanelRadius(
                        player, toolId, context.config(), false)),
                guardedAction(genericAuthority, () -> panelActionService.applyAdjustPanelRadius(
                        player, toolId, context.config(), true)),
                context.genericRosterActions() ? guardedAction(genericAuthority,
                        context.actions().manageGroups()) : ignoredAction,
                guardedString(preferenceAuthority,
                        value -> panelActionService.applySetSort(player, toolId, value)),
                guardedString(preferenceAuthority,
                        value -> panelActionService.applySetFilterMode(player, toolId, value)),
                guardedString(preferenceAuthority, value -> panelActionService
                        .applySetSelectedFilterText(player, toolId, value)),
                guardedAction(preferenceAuthority,
                        () -> panelActionService.applyClearFilters(player, toolId)),
                context.genericRosterActions() ? guardedString(genericAuthority,
                        value -> groupAssignPageService.applyGroupActivation(
                                player, toolId, context.config(), value)) : ignoredString,
                context.genericRosterActions() ? guardedPair(genericAuthority,
                        (uuid, group) -> groupAssignPageService.applyGroupAssignment(
                                player, toolId, context.config(), uuid, group))
                        : (ignoredUuid, ignoredGroup) -> { });
    }

    private void applyFeatureAction(
            UUID ownerUuid,
            Ref<EntityStore> eventPlayerRef,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            FeatureAction action,
            CommandPanelSnapshotState snapshot,
            BondedLifecycleAuthority bondedLifecycleAuthority
    ) {
        CommandPanelFeaturePresentation feature = snapshot == null
                ? null : snapshot.presentation(presentationUuid);
        routeFeatureAction(ownerUuid, eventPlayerRef, store, config,
                presentationUuid,
                feature, action, bondedLifecycleAuthority);
    }

    FeatureRoute routeFeatureAction(
            Player player,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            CommandPanelFeaturePresentation feature,
            FeatureAction action
    ) {
        return routeFeatureAction(player, store, config, presentationUuid,
                feature, action, ignored -> true);
    }

    private FeatureRoute routeFeatureAction(
            UUID ownerUuid,
            Ref<EntityStore> eventPlayerRef,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            CommandPanelFeaturePresentation feature,
            FeatureAction action,
            BondedLifecycleAuthority bondedLifecycleAuthority
    ) {
        if (config != null && config.usesBondedCompanionRoster()) {
            if (bondedActions != null) {
                bondedActions.route(ownerUuid, eventPlayerRef, store, config,
                        feature, switch (action) {
                    case SUMMON -> BondedCompanionPanelActionService.Action.SUMMON;
                    case DISMISS -> BondedCompanionPanelActionService.Action.STORE;
                    case REVIVE -> BondedCompanionPanelActionService.Action.REVIVE;
                    case ABANDON -> BondedCompanionPanelActionService.Action.ABANDON;
                        }, bondedLifecycleAuthority);
            }
            return FeatureRoute.BONDED;
        }
        Player eventPlayer = BondedCompanionPanelActionRouter.resolvePlayerFromEvent(
                ownerUuid, eventPlayerRef, store);
        if (eventPlayer == null) {
            return FeatureRoute.IGNORED;
        }
        return dispatchGenericFeatureAction(
                eventPlayer, config, presentationUuid, action);
    }

    private FeatureRoute routeFeatureAction(
            Player player,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            UUID presentationUuid,
            CommandPanelFeaturePresentation feature,
            FeatureAction action,
            BondedLifecycleAuthority bondedLifecycleAuthority
    ) {
        if (config != null && config.usesBondedCompanionRoster()) {
            if (bondedActions != null) {
                bondedActions.route(player, store, config, feature,
                        switch (action) {
                    case SUMMON -> BondedCompanionPanelActionService.Action.SUMMON;
                    case DISMISS -> BondedCompanionPanelActionService.Action.STORE;
                    case REVIVE -> BondedCompanionPanelActionService.Action.REVIVE;
                    case ABANDON -> BondedCompanionPanelActionService.Action.ABANDON;
                        }, bondedLifecycleAuthority);
            }
            return FeatureRoute.BONDED;
        }
        return dispatchGenericFeatureAction(
                player, config, presentationUuid, action);
    }

    private FeatureRoute dispatchGenericFeatureAction(
            Player player,
            TwCommandItemConfig config,
            UUID presentationUuid,
            FeatureAction action
    ) {
        if (featureActions == null) {
            return FeatureRoute.IGNORED;
        }
        switch (action) {
            case SUMMON -> featureActions.summon(player, config, presentationUuid);
            case DISMISS -> featureActions.dismiss(player, config, presentationUuid);
            case REVIVE -> featureActions.revive(player, config, presentationUuid);
            case ABANDON -> { }
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
            LOGGER.log(Level.WARNING,
                    "[tw-command-menu] selection page open failed", throwable);
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

    private static int commandCount(TwCommandItemConfig config) {
        if (config == null || config.getCommandList() == null) {
            return 0;
        }
        return config.getCommandList().length;
    }

    /** Binds one generic action only when its caller supplies a real outcome. */
    CommandUiActionHandle bindGenericUiAction(
            CommandUiSessionImpl session,
            GenericUiActionBinding binding
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(binding, "binding");
        return session.issueGeneric(binding.action(), binding.authority(),
                binding.operation(), binding.confirmationRequired());
    }

    /** Binds one bonded action to stable IDs and a current-world resolver. */
    @Nullable
    CommandUiActionHandle bindBondedUiAction(
            CommandUiSessionImpl session,
            BondedUiActionBinding binding
    ) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(binding, "binding");
        BondedCompanionPanelActionService.Action bondedAction = switch (
                binding.action().builtInKind()) {
            case SUMMON -> BondedCompanionPanelActionService.Action.SUMMON;
            case DISMISS -> BondedCompanionPanelActionService.Action.STORE;
            case REVIVE -> BondedCompanionPanelActionService.Action.REVIVE;
            case ABANDON -> BondedCompanionPanelActionService.Action.ABANDON;
            default -> null;
        };
        if (bondedActions == null || bondedAction == null) return null;
        // The bonded gateway binding carries profile/roster identity. Do not
        // retain a replaceable current-NPC UUID from a rendered row.
        CommandUiAction boundAction = new CommandUiAction(
                binding.action().kind(), null, binding.action().value(),
                binding.action().confirmationRequired());
        return session.issueBonded(boundAction, () -> true, () ->
                bondedActions.routeForUi(binding.ownerUuid(), binding.rosterId(),
                        binding.profileId(), bondedAction, binding.contextResolver()),
                binding.confirmationRequired());
    }

    record GenericUiActionBinding(
            CommandUiAction action,
            BooleanSupplier authority,
            java.util.function.Supplier<CompletionStage<CommandUiActionResult>> operation,
            boolean confirmationRequired
    ) {
        GenericUiActionBinding {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(operation, "operation");
        }
    }

    record BondedUiActionBinding(
            CommandUiAction action,
            UUID ownerUuid,
            String rosterId,
            String profileId,
            BondedCompanionPanelActionRouter.CurrentUiContextResolver contextResolver,
            boolean confirmationRequired
    ) {
        BondedUiActionBinding {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            if (rosterId == null || rosterId.isBlank()) {
                throw new IllegalArgumentException("rosterId is required");
            }
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId is required");
            }
            Objects.requireNonNull(contextResolver, "contextResolver");
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

    private record PageContext(
            Player player,
            PlayerRef uiPlayerRef,
            TwCommandItemConfig config,
            String toolId,
            Actions actions,
            UUID ownerUuid,
            boolean genericRosterActions,
            BooleanSupplier genericAuthority,
            BooleanSupplier preferenceAuthority,
            BondedLifecycleAuthority bondedLifecycleAuthority,
            CommandPanelSnapshotState snapshot,
            LinkedPanelRefreshSignalSource refreshSignals,
            String selectedId,
            boolean requireUnlinkConfirm,
            boolean recallTeleportingEnabled) {
    }

    private record NpcCallbacks(
            Consumer<UUID> link,
            Consumer<UUID> unlink,
            Consumer<UUID> toggleActive,
            Consumer<UUID> toggleBreeding,
            Consumer<UUID> release,
            Consumer<UUID> cull,
            Consumer<UUID> respawn,
            Consumer<UUID> locate,
            Consumer<UUID> recall,
            Consumer<UUID> setHome,
            Consumer<UUID> returnHome,
            Consumer<UUID> openTalents,
            Consumer<String> selectCommand) {
    }

    private record FeatureCallbacks(
            LinkedNpcPanelFeatureAction summon,
            LinkedNpcPanelFeatureAction dismiss,
            LinkedNpcPanelFeatureAction revive,
            LinkedNpcPanelFeatureAction abandon,
            LinkedNpcPanelFeatureAction flightToggle) {
    }

    private record PanelCallbacks(
            Consumer<String> setMode,
            Consumer<Boolean> setAutoLinkEnabled,
            Consumer<Boolean> setActiveHighlightEnabled,
            Runnable decreaseRadius,
            Runnable increaseRadius,
            Runnable manageGroups,
            Consumer<String> setSort,
            Consumer<String> setFilterMode,
            Consumer<String> setFilterText,
            Runnable clearFilters,
            Consumer<String> setGroupActivation,
            BiConsumer<UUID, String> assignGroup) {
    }

    enum FeatureAction {
        SUMMON,
        DISMISS,
        REVIVE,
        ABANDON
    }

    enum FeatureRoute { BONDED, GENERIC, IGNORED }

    /** Validates bonded tool authority against the player resolved by an event. */
    @FunctionalInterface
    interface BondedLifecycleAuthority {
        boolean allows(Player player);
    }

    @FunctionalInterface
    interface FlightToggleAction {
        boolean toggle(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                       Store<EntityStore> eventStore, String itemId,
                       com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation row);
    }

    private LinkedNpcPanelFeatureAction shoulderRideCallback(PageContext context) {
        return (uuid, eventRef, eventStore) -> {
            if (!context.config().usesBondedCompanionRoster()) {
                com.alechilles.alecstamework.ui.LinkedNpcEntry entry =
                        context.snapshot().entry(uuid);
                if (context.genericRosterActions()
                        && context.genericAuthority().getAsBoolean()
                        && linkedShoulderRideActions != null && entry != null
                        && entry.linked() && entry.loaded()
                        && entry.shoulderRideAvailable()) {
                    linkedShoulderRideActions.toggle(context.ownerUuid(), eventRef,
                            eventStore, context.toolId(), uuid);
                }
                return;
            }
            if (shoulderRideActions == null) return;
            CommandPanelFeaturePresentation feature =
                    context.snapshot().presentation(uuid);
            if (feature == null || feature.bonded() == null
                    || !BondedCompanionShoulderRideActionService
                    .isAvailable(feature.bonded())) return;
            Player player = flightTogglePlayers == null ? null
                    : flightTogglePlayers.resolve(context.ownerUuid(), eventRef,
                    eventStore);
            if (player == null || context.bondedLifecycleAuthority() == null
                    || !context.bondedLifecycleAuthority().allows(player)) return;
            shoulderRideActions.toggle(context.ownerUuid(), eventRef, eventStore,
                    feature.bonded());
        };
    }

    void configureShoulderRideAction(
            @javax.annotation.Nullable ShoulderRideAction action) {
        shoulderRideActions = action;
    }

    void configureLinkedShoulderRideAction(
            @javax.annotation.Nullable LinkedShoulderRideAction action) {
        linkedShoulderRideActions = action;
    }

    @FunctionalInterface
    interface LinkedFlightToggleAction {
        boolean toggle(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                       Store<EntityStore> eventStore, String itemId,
                       UUID npcUuid);
    }

    @FunctionalInterface
    interface ShoulderRideAction {
        boolean toggle(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                       Store<EntityStore> eventStore,
                       com.alechilles.alecstamework.ui.BondedCompanionPanelPresentation row);
    }

    @FunctionalInterface
    interface LinkedShoulderRideAction {
        boolean toggle(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                       Store<EntityStore> eventStore, String itemId,
                       UUID npcUuid);
    }

    @FunctionalInterface
    interface EventPlayerResolver {
        Player resolve(UUID ownerUuid, Ref<EntityStore> eventPlayerRef,
                       Store<EntityStore> eventStore);
    }
}
