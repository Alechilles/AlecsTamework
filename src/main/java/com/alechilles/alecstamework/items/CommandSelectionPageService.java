package com.alechilles.alecstamework.items;

import static com.alechilles.alecstamework.items.CommandSelectionCallbackGuards.*;

import com.alechilles.alecstamework.api.commandui.CommandUiActionHandle;
import com.alechilles.alecstamework.api.commandui.CommandUiActionResult;
import com.alechilles.alecstamework.api.commandui.CommandUiCommandOption;
import com.alechilles.alecstamework.api.commandui.CommandUiOpenContext;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiProviderId;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiUpdate;
import com.alechilles.alecstamework.api.internal.CommandUiProviderRegistry;
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
import com.alechilles.alecstamework.ui.CommandUiHostPage;
import com.alechilles.alecstamework.ui.StandardCommandUiController;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
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
    @Nullable private volatile CommandUiPageCoordinator commandUiCoordinator;
    private final CommandUiHostPage.WorldDispatcher commandUiWorldDispatcher =
            CommandUiCurrentWorldDispatcher.production();

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

    /** Enables the shared host with Tamework's live provider registry. */
    void configureCommandUi(@Nullable CommandUiProviderRegistry registry) {
        commandUiCoordinator = registry == null
                ? null : new CommandUiPageCoordinator(registry, this);
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
        return open(player, store, config, working, toolId, actions,
                genericCallbackAuthority, bondedLifecycleAuthority, false);
    }

    private boolean open(Player player,
                 Store<EntityStore> store,
                 TwCommandItemConfig config,
                 ItemStack working,
                 String toolId,
                 Actions actions,
                 BooleanSupplier genericCallbackAuthority,
                 BondedLifecycleAuthority bondedLifecycleAuthority,
                 boolean forceStandard) {
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
        BooleanSupplier genericAuthority = genericCallbackAuthority != null
                ? genericCallbackAuthority : () -> false;
        BondedLifecycleAuthority bondedAuthority = bondedLifecycleAuthority != null
                ? bondedLifecycleAuthority : ignored -> false;
        CommandUiPageCoordinator coordinator = commandUiCoordinator;
        if (coordinator == null) {
            TameworkCommandSelectionPage page = createPage(
                    player, store, uiPlayerRef, config, working, toolId, actions,
                    genericAuthority, bondedAuthority);
            return openPage(player, playerRef, store, page);
        }
        return openHostedPage(coordinator, player, store, playerRef, uiPlayerRef,
                config, working, toolId, actions, genericAuthority,
                bondedAuthority, forceStandard);
    }

    private boolean openHostedPage(
            CommandUiPageCoordinator coordinator,
            Player player,
            Store<EntityStore> store,
            Ref<EntityStore> playerRef,
            PlayerRef uiPlayerRef,
            TwCommandItemConfig config,
            ItemStack working,
            String toolId,
            Actions actions,
            BooleanSupplier genericAuthority,
            BondedLifecycleAuthority bondedAuthority,
            boolean forceStandard
    ) {
        UUID sessionId = UUID.randomUUID();
        String selected = working == null ? null : working.getFromMetadataOrNull(
                TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
        CommandUiProviderId configuredProvider = forceStandard ? null
                : CommandUiProviderId.tryParse(config.getUiProviderId()).orElse(null);
        String rosterMode = config.usesBondedCompanionRoster()
                ? "bonded" : "generic";
        CommandUiOpenContext openContext = new CommandUiOpenContext(
                uiPlayerRef.getUuid(), uiPlayerRef.getLanguage(), toolId,
                config.getId(), configuredProvider, rosterMode);
        PageContext pageContext = pageContext(
                player, uiPlayerRef, config, working, toolId, actions,
                genericAuthority, bondedAuthority);
        InitialUiState initial = initialSnapshot(
                sessionId, configuredProvider, player, config, working,
                toolId, selected, rosterMode, pageContext.snapshot());
        CommandUiSnapshot snapshot = initial.snapshot();
        CommandUiActionCatalog actionCatalog = commandActions(
                pageContext, snapshot);
        AtomicReference<CommandUiPageCoordinator.Created> createdRef =
                new AtomicReference<>();
        Runnable refreshRequest = () -> refreshHosted(
                createdRef.get(), uiPlayerRef.getUuid(), config, toolId, actions,
                genericAuthority, bondedAuthority);
        CommandUiPageCoordinator.Created created = coordinator.create(
                uiPlayerRef, openContext, snapshot,
                () -> new StandardCommandUiController(createSelectionPage(
                        pageContext, buildNpcCallbacks(pageContext),
                        buildFeatureCallbacks(pageContext),
                        buildPanelCallbacks(pageContext))),
                actionCatalog.genericBindings(), actionCatalog.bondedBindings(),
                actionCatalog::attach,
                refreshRequest,
                commandUiWorldDispatcher,
                currentWorld -> openStandardFallback(
                        currentWorld, config, toolId, actions,
                        genericAuthority, bondedAuthority));
        createdRef.set(created);
        if (!created.host().isOpen()) {
            return open(player, store, config, working, toolId, actions,
                    genericAuthority, bondedAuthority, true);
        }
        try {
            created.host().own(pageContext.refreshSignals().subscribe(
                    ignored -> created.session().requestRefresh()));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING,
                    "[tw-command-menu] refresh subscription failed", failure);
        }
        if (created.custom()) {
            CommandUiAutomaticRefresh automaticRefresh =
                    new CommandUiAutomaticRefresh(created.session());
            if (created.host().own(automaticRefresh)) automaticRefresh.start();
        }
        boolean opened = openPage(player, playerRef, store, created.host());
        if (!opened) {
            created.host().closeSession(
                    com.alechilles.alecstamework.api.commandui
                            .CommandUiCloseReason.FAILURE);
        }
        return opened;
    }

    private void refreshHosted(
            @Nullable CommandUiPageCoordinator.Created created,
            UUID ownerUuid,
            TwCommandItemConfig config,
            String toolId,
            Actions actions,
            BooleanSupplier genericAuthority,
            BondedLifecycleAuthority bondedAuthority
    ) {
        if (created == null || !created.host().isOpen()
                || !created.session().isOpen()) return;
        try {
            PlayerRef currentRef = ownerUuid == null || Universe.get() == null
                    ? null : Universe.get().getPlayer(ownerUuid);
            Ref<EntityStore> entityRef = currentRef == null ? null
                    : currentRef.getReference();
            Store<EntityStore> store = entityRef == null ? null
                    : entityRef.getStore();
            Player player = entityRef == null || !entityRef.isValid()
                    || store == null ? null
                    : store.getComponent(entityRef, Player.getComponentType());
            ItemStack working = player == null ? null
                    : toolInventoryService.findUniqueToolStack(player, toolId);
            if (player == null || currentRef == null || working == null
                    || working.isEmpty()) {
                created.host().closeSession(
                        com.alechilles.alecstamework.api.commandui
                                .CommandUiCloseReason.AUTHORITY_LOST);
                return;
            }
            CommandUiSnapshot previous = created.session().snapshot();
            String selected = working.getFromMetadataOrNull(
                    TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING);
            PageContext context = pageContext(player, currentRef, config, working,
                    toolId, actions, genericAuthority, bondedAuthority);
            CommandUiSnapshot fresh = initialSnapshot(
                    previous.sessionId(), previous.providerId(), player, config,
                    working, toolId, selected, previous.rosterMode(),
                    context.snapshot()).snapshot()
                    .withPresentationRevision(previous.presentationRevision() + 1L);
            CommandUiActionCatalog catalog = commandActions(context, fresh);
            CommandUiSnapshot next;
            if (created.session().consumeActionRebindRequired()
                    || !catalog.matchesActions(previous)) {
                fresh = fresh.withActionGeneration(
                        previous.actionGeneration() + 1L);
                created.session().publishInternal(fresh,
                        com.alechilles.alecstamework.api.commandui
                                .CommandUiChangeSet.empty());
                List<CommandUiActionHandle> handles = issueHandles(
                        created.session(), catalog);
                next = catalog.attach(fresh, handles);
            } else {
                next = CommandUiActionCatalog.retainActions(fresh, previous);
            }
            var changes = CommandUiSnapshotDiffer.diff(previous, next);
            created.session().publishInternal(next, changes);
            CommandUiSnapshot published = created.session().snapshot();
            created.host().applyUpdate(new CommandUiUpdate(
                    published, previous,
                    CommandUiSnapshotDiffer.diff(previous, published)));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.log(Level.WARNING,
                    "[tw-command-menu] hosted refresh failed", failure);
        }
    }

    private List<CommandUiActionHandle> issueHandles(
            CommandUiSessionImpl session,
            CommandUiActionCatalog catalog
    ) {
        java.util.ArrayList<CommandUiActionHandle> handles =
                new java.util.ArrayList<>();
        for (GenericUiActionBinding binding : catalog.genericBindings()) {
            handles.add(bindGenericUiAction(session, binding));
        }
        for (BondedUiActionBinding binding : catalog.bondedBindings()) {
            CommandUiActionHandle handle = bindBondedUiAction(session, binding);
            if (handle != null) handles.add(handle);
        }
        return List.copyOf(handles);
    }

    private InitialUiState initialSnapshot(
            UUID sessionId,
            @Nullable CommandUiProviderId providerId,
            Player player,
            TwCommandItemConfig config,
            @Nullable ItemStack working,
            String toolId,
            @Nullable String selected,
            String rosterMode,
            CommandPanelSnapshotState panelSnapshot
    ) {
        panelSnapshot.refreshSnapshot();
        String mode = config.usesBondedCompanionRoster()
                ? TameworkCommandSelectionPage.PANEL_MODE_LINKED
                : toolInventoryService.resolvePanelModeValueForTool(
                        player, toolId, config);
        CommandUiPanelState panelState = new CommandUiPanelState(
                mode,
                !config.usesBondedCompanionRoster()
                        && toolInventoryService.resolvePanelAutoLinkEnabledForTool(
                                player, toolId),
                !config.usesBondedCompanionRoster()
                        && toolInventoryService.resolvePanelActiveHighlightEnabledForTool(
                                player, toolId),
                Math.max(0.0, config.getRadius()),
                toolInventoryService.resolvePanelRadiusLabelForTool(
                        player, toolId, config),
                toolInventoryService.resolvePanelSortValueForTool(player, toolId),
                toolInventoryService.resolvePanelFilterModeValueForTool(player, toolId),
                toolInventoryService.resolvePanelFilterInputForTool(player, toolId),
                panelSnapshot.emptyStateKey(), Map.of(), Map.of());
        List<CommandUiCommandOption> commandOptions =
                CommandUiSnapshotAssembler.commandOptions(
                        config, selected, Map.of());
        CommandUiSnapshot snapshot = CommandUiSnapshotAssembler.assemble(
                sessionId, 1L, 1L,
                providerId == null ? null : providerId.value(),
                toolId, working == null ? null : working.getItemId(),
                config.getId(), rosterMode,
                Set.of("commands", "companions", "panel", "partial-updates"),
                selected, commandOptions, panelSnapshot, panelState,
                Map.of(), Map.of(), System.currentTimeMillis(), Map.of(), null);
        return new InitialUiState(withAssignments(snapshot, working), panelSnapshot);
    }

    private CommandUiSnapshot withAssignments(
            CommandUiSnapshot base,
            @Nullable ItemStack working
    ) {
        CommandHotswapAssignmentStore assignments =
                new CommandHotswapAssignmentStore();
        Map<String, String> assigned = new java.util.LinkedHashMap<>();
        Map<String, List<CommandUiCommandOption>> choices =
                new java.util.LinkedHashMap<>();
        for (CommandHotswapAssignmentStore.Slot slot
                : CommandHotswapAssignmentStore.Slot.values()) {
            String slotName = slot.name();
            String selected = assignments.read(working, slot);
            if (selected != null) assigned.put(slotName, selected);
            choices.put(slotName, base.commandOptions().stream()
                    .map(option -> new CommandUiCommandOption(
                            option.commandId(), option.label(),
                            option.localizationSource(), option.iconAssetId(),
                            option.radialVisible(),
                            option.commandId().equals(selected), null))
                    .toList());
        }
        Map<String, String> groups = new java.util.LinkedHashMap<>();
        for (var row : base.companionRows()) {
            String groupId = row.presentation().get("groupId");
            if (groupId != null && !groupId.isBlank()) {
                groups.putIfAbsent(groupId,
                        row.presentation().getOrDefault("groupName", groupId));
            }
        }
        return new CommandUiSnapshot(
                base.sessionId(), base.presentationRevision(),
                base.actionGeneration(), base.providerId(), base.toolId(),
                base.itemId(), base.configId(), base.rosterMode(),
                base.enabledCapabilities(), base.selectedCommand(),
                base.commandOptions(), base.companionRows(), base.panelState(),
                base.globalActions(), base.commandActions(), assigned, choices,
                groups, base.serverTimeMillis(), base.deadlines(),
                base.emptyStateKey(), base.disabledReason());
    }

    private CommandUiActionCatalog commandActions(
            PageContext context,
            CommandUiSnapshot snapshot
    ) {
        CommandUiActionCatalog catalog = new CommandUiActionCatalog();
        TwCommandItemConfig config = context.config();
        Actions actions = context.actions();
        if (config == null || config.getCommandList() == null) return catalog;
        for (TwCommandItemConfig.CommandEntry command : config.getCommandList()) {
            if (command == null || command.getId() == null
                    || command.getId().isBlank()) continue;
            String commandId = command.getId().trim();
            String label = command.getDisplayName() == null
                    || command.getDisplayName().isBlank()
                    ? commandId : command.getDisplayName();
            catalog.addCommand(commandId, label,
                    new GenericUiActionBinding(
                            new CommandUiAction("SELECT_COMMAND", null,
                                    commandId, false),
                            context.toolAuthority(),
                            () -> apply(actions.selectCommand(), commandId),
                            false));
        }
        addHotswapActions(catalog, context, snapshot);
        addPanelActions(catalog, context);
        addCompanionActions(catalog, context, snapshot,
                buildNpcCallbacks(context), buildFeatureCallbacks(context));
        return catalog;
    }

    private void addHotswapActions(
            CommandUiActionCatalog catalog,
            PageContext context,
            CommandUiSnapshot snapshot
    ) {
        for (var slotEntry : snapshot.hotswapChoices().entrySet()) {
            CommandHotswapAssignmentStore.Slot slot;
            try {
                slot = CommandHotswapAssignmentStore.Slot.valueOf(slotEntry.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (CommandUiCommandOption option : slotEntry.getValue()) {
                String commandId = option.commandId();
                catalog.addHotswap(slot.name(), commandId, option.label(),
                        new GenericUiActionBinding(
                                new CommandUiAction("ASSIGN_HOTSWAP", null,
                                        slot.name() + ":" + commandId, false),
                                context.toolAuthority(),
                                () -> applyHotswap(context, slot, commandId), false));
            }
        }
    }

    private CompletionStage<CommandUiActionResult> applyHotswap(
            PageContext context,
            CommandHotswapAssignmentStore.Slot slot,
            String commandId
    ) {
        try {
            Player currentPlayer = resolveCurrentPlayer(context.ownerUuid());
            if (currentPlayer == null) {
                return CompletableFuture.completedFuture(
                        CommandUiActionResult.unavailable(
                                "current command player is unavailable"));
            }
            boolean changed = toolInventoryService.mutateActiveToolStack(
                    currentPlayer, context.toolId(), stack ->
                            new CommandHotswapAssignmentStore().write(
                                    stack, slot, commandId));
            return CompletableFuture.completedFuture(changed
                    ? CommandUiActionResult.applied()
                    : CommandUiActionResult.conflict(
                            "command item changed before assignment"));
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.failed("hotswap assignment failed"));
        }
    }

    private void addPanelActions(
            CommandUiActionCatalog catalog,
            PageContext context
    ) {
        PanelCallbacks panel = buildPanelCallbacks(context);
        addPanel(catalog, "MODE_LINKED", "Show linked companions",
                "LinkedMode", context.preferenceAuthority(),
                () -> panel.setMode().accept("LinkedMode"));
        if (!context.config().usesBondedCompanionRoster()) {
            addPanel(catalog, "MODE_NEARBY", "Show nearby companions",
                    "NearbyMode", context.preferenceAuthority(),
                    () -> panel.setMode().accept("NearbyMode"));
            addPanel(catalog, "TOGGLE_AUTO_LINK", "Toggle automatic linking",
                    Boolean.toString(!toolInventoryService
                            .resolvePanelAutoLinkEnabledForTool(
                                    context.player(), context.toolId())),
                    context.genericAuthority(), () -> panel.setAutoLinkEnabled()
                            .accept(!toolInventoryService
                                    .resolvePanelAutoLinkEnabledForTool(
                                            context.player(), context.toolId())));
            addPanel(catalog, "TOGGLE_ACTIVE_HIGHLIGHT",
                    "Toggle active highlight",
                    Boolean.toString(!toolInventoryService
                            .resolvePanelActiveHighlightEnabledForTool(
                                    context.player(), context.toolId())),
                    context.genericAuthority(), () -> panel
                            .setActiveHighlightEnabled().accept(!toolInventoryService
                                    .resolvePanelActiveHighlightEnabledForTool(
                                            context.player(), context.toolId())));
            catalog.addGlobal("MANAGE_GROUPS", "Manage groups",
                    genericBinding("MANAGE_GROUPS", null,
                            context.genericAuthority(), panel.manageGroups(), false));
        }
        addPanel(catalog, "RADIUS_DECREASE", "Decrease radius", "decrease",
                context.toolAuthority(), panel.decreaseRadius());
        addPanel(catalog, "RADIUS_INCREASE", "Increase radius", "increase",
                context.toolAuthority(), panel.increaseRadius());
        addPanel(catalog, "CLEAR_FILTERS", "Clear filters", "clear",
                context.preferenceAuthority(), panel.clearFilters());
        for (String sort : List.of("Default", "Name", "Species", "Group")) {
            addPanel(catalog, "SORT_" + sort.toUpperCase(java.util.Locale.ROOT),
                    "Sort by " + sort.toLowerCase(java.util.Locale.ROOT),
                    "sort:" + sort, context.preferenceAuthority(),
                    () -> panel.setSort().accept(sort));
        }
        for (String filter : List.of("None", "Name", "Species", "Group")) {
            addPanel(catalog,
                    "FILTER_" + filter.toUpperCase(java.util.Locale.ROOT),
                    "Filter by " + filter.toLowerCase(java.util.Locale.ROOT),
                    "filter:" + filter, context.preferenceAuthority(),
                    () -> panel.setFilterMode().accept(filter));
        }
        if (context.genericRosterActions()) {
            addPanel(catalog, "GROUP_ALL", "Use all companion groups",
                    "group:", context.genericAuthority(),
                    () -> panel.setGroupActivation().accept(""));
            snapshotGroups(context).forEach((groupId, label) -> addPanel(
                    catalog, "GROUP_" + groupId, "Use group " + label,
                    "group:" + groupId, context.genericAuthority(),
                    () -> panel.setGroupActivation().accept(groupId)));
        }
    }

    private void addPanel(
            CommandUiActionCatalog catalog,
            String key,
            String label,
            String value,
            BooleanSupplier authority,
            Runnable operation
    ) {
        catalog.addPanel(key, label, genericBinding(
                "PANEL_PREFERENCE", value, authority, operation, false));
    }

    private GenericUiActionBinding genericBinding(
            String kind,
            @Nullable String value,
            BooleanSupplier authority,
            Runnable operation,
            boolean confirmation
    ) {
        return new GenericUiActionBinding(
                new CommandUiAction(kind, null, value, confirmation), authority,
                () -> apply(operation), confirmation);
    }

    private static CompletionStage<CommandUiActionResult> apply(Runnable action) {
        if (action == null) return CompletableFuture.completedFuture(
                CommandUiActionResult.unavailable("action is unavailable"));
        try {
            action.run();
            return CompletableFuture.completedFuture(CommandUiActionResult.applied());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.failed("action failed"));
        }
    }

    private static <T> CompletionStage<CommandUiActionResult> apply(
            Consumer<T> action,
            T value
    ) {
        if (action == null) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.unavailable("action is unavailable"));
        }
        try {
            action.accept(value);
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.applied());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.failed("action failed"));
        }
    }

    private void addCompanionActions(
            CommandUiActionCatalog catalog,
            PageContext context,
            CommandUiSnapshot snapshot,
            NpcCallbacks npc,
            FeatureCallbacks features
    ) {
        for (com.alechilles.alecstamework.ui.LinkedNpcEntry entry
                : context.snapshot().snapshot().entries()) {
            if (entry == null || entry.npcUuid() == null) continue;
            CommandPanelFeaturePresentation feature =
                    context.snapshot().presentation(entry.npcUuid());
            UUID rowId = rowId(snapshot, entry.npcUuid());
            if (rowId == null) continue;
            if (feature != null && feature.bonded() != null) {
                addBondedActions(catalog, context, rowId, entry.npcUuid(),
                        feature, features);
            } else {
                addGenericActions(catalog, context, rowId, entry, feature,
                        npc, features);
            }
        }
    }

    private void addGenericActions(
            CommandUiActionCatalog catalog,
            PageContext context,
            UUID rowId,
            com.alechilles.alecstamework.ui.LinkedNpcEntry entry,
            @Nullable CommandPanelFeaturePresentation feature,
            NpcCallbacks npc,
            FeatureCallbacks features
    ) {
        UUID npcId = entry.npcUuid();
        boolean managed = context.config().usesOwnerCommandFamilyRoster()
                || feature != null && feature.managesRosterRow();
        boolean linked = entry.linked() && !managed;
        boolean releasable = !linked && !managed && entry.loaded()
                && !entry.dead() && !entry.captured() && !entry.inCoop()
                && !entry.lost();
        if (!linked && !managed) addGenericRow(catalog, rowId, "LINK", "Link",
                npcId, null, npc.link(), context.genericAuthority(), false);
        if (linked) addGenericRow(catalog, rowId, "UNLINK", "Unlink",
                npcId, null, npc.unlink(), context.genericAuthority(),
                context.requireUnlinkConfirm());
        if (releasable) {
            addGenericRow(catalog, rowId, "RELEASE", "Release", npcId, null,
                    npc.release(), context.genericAuthority(), true);
            addGenericRow(catalog, rowId, "CULL", "Cull", npcId, null,
                    npc.cull(), context.genericAuthority(), true);
        }
        if (linked) addGenericRow(catalog, rowId, "TOGGLE_ACTIVE",
                entry.active() ? "Set inactive" : "Set active", npcId, null,
                npc.toggleActive(), context.genericAuthority(), false);
        if (linked && entry.loaded() && entry.breedingAvailable()) {
            addGenericRow(catalog, rowId, "TOGGLE_BREEDING",
                    entry.breedingEnabled() ? "Disable breeding" : "Enable breeding",
                    npcId, null, npc.toggleBreeding(), context.genericAuthority(), false);
        }
        boolean revive = linked && (entry.dead() || entry.lost())
                && entry.deadRespawnRemainingMs() == 0L
                && (feature == null || !feature.managesPaidRevival());
        if (revive) addGenericRow(catalog, rowId, "RESPAWN", "Respawn",
                npcId, null, npc.respawn(), context.genericAuthority(), false);
        if (linked && !entry.dead() && !entry.lost()) {
            addGenericRow(catalog, rowId, "LOCATE", "Locate", npcId, null,
                    npc.locate(), context.genericAuthority(), false);
        }
        if (linked && context.recallTeleportingEnabled() && !entry.dead()
                && !entry.captured() && !entry.inCoop() && !entry.lost()) {
            addGenericRow(catalog, rowId, "RECALL", "Recall", npcId, null,
                    npc.recall(), context.genericAuthority(), false);
        }
        if (linked && entry.loaded() && !entry.dead() && !entry.captured()
                && !entry.inCoop() && !entry.lost()) {
            addGenericRow(catalog, rowId, "SET_HOME", "Set home", npcId, null,
                    npc.setHome(), context.genericAuthority(), false);
        }
        if (linked && entry.hasHome() && !entry.dead() && !entry.captured()
                && !entry.inCoop() && !entry.lost()) {
            addGenericRow(catalog, rowId, "RETURN_HOME", "Return home",
                    npcId, null, npc.returnHome(), context.genericAuthority(), false);
        }
        if (linked && entry.loaded() && entry.flightToggleAvailable()) {
            addFeatureRow(catalog, rowId, "TOGGLE_FLIGHT", "Toggle flight",
                    npcId, features.flightToggle(), context, false);
        }
        if (linked && entry.loaded() && entry.shoulderRideAvailable()) {
            addFeatureRow(catalog, rowId, "TOGGLE_SHOULDER_RIDE",
                    "Toggle shoulder ride", npcId, shoulderRideCallback(context),
                    context, false);
        }
        if (feature != null && feature.roster() != null) {
            if (feature.roster().summonEnabled()) addFeatureRow(catalog, rowId,
                    "SUMMON", "Summon", npcId, features.summon(), context, false);
            if (feature.roster().dismissEnabled()) addFeatureRow(catalog, rowId,
                    "DISMISS", "Dismiss", npcId, features.dismiss(), context, false);
            if (feature.revival() != null
                    && feature.revival().status()
                    == com.alechilles.alecstamework.api.PaidCommandRevivalQuote.Status.READY) {
                addFeatureRow(catalog, rowId, "REVIVE", "Revive", npcId,
                        features.revive(), context, true);
            }
        }
        if (linked && context.genericRosterActions()) {
            PanelCallbacks panel = buildPanelCallbacks(context);
            addGroupAssignment(catalog, context, rowId, npcId, "", "No group",
                    panel.assignGroup());
            snapshotGroups(context).forEach((groupId, label) ->
                    addGroupAssignment(catalog, context, rowId, npcId, groupId,
                            "Assign to " + label, panel.assignGroup()));
        }
    }

    private Map<String, String> snapshotGroups(PageContext context) {
        Map<String, String> groups = new java.util.LinkedHashMap<>();
        for (var entry : context.snapshot().snapshot().entries()) {
            if (entry == null || entry.groupId() == null
                    || entry.groupId().isBlank()) continue;
            groups.putIfAbsent(entry.groupId(), entry.groupName() == null
                    || entry.groupName().isBlank()
                    ? entry.groupId() : entry.groupName());
        }
        return Map.copyOf(groups);
    }

    private void addGroupAssignment(
            CommandUiActionCatalog catalog,
            PageContext context,
            UUID rowId,
            UUID target,
            String groupId,
            String label,
            BiConsumer<UUID, String> operation
    ) {
        catalog.addRow(rowId, "ASSIGN_GROUP:" + groupId, label,
                new GenericUiActionBinding(
                        new CommandUiAction("ASSIGN_GROUP", target, groupId, false),
                        context.genericAuthority(),
                        () -> {
                            operation.accept(target, groupId);
                            return CompletableFuture.completedFuture(
                                    CommandUiActionResult.applied());
                        }, false));
    }

    private void addBondedActions(
            CommandUiActionCatalog catalog,
            PageContext context,
            UUID rowId,
            UUID presentationId,
            CommandPanelFeaturePresentation feature,
            FeatureCallbacks features
    ) {
        if (bondedActions == null || feature.bonded() == null) return;
        var bonded = feature.bonded();
        var status = bonded.status();
        String kind = switch (status.action()) {
            case SUMMON -> "SUMMON";
            case DISMISS -> "DISMISS";
            case REVIVE -> "REVIVE";
            case NONE -> null;
        };
        if (kind != null && status.actionEnabled()) {
            addBondedRow(catalog, context, rowId, feature, kind,
                    switch (kind) {
                        case "SUMMON" -> "Summon";
                        case "DISMISS" -> "Dismiss";
                        default -> "Revive";
                    }, "REVIVE".equals(kind));
        }
        addBondedRow(catalog, context, rowId, feature, "ABANDON",
                "Abandon", true);
        if (BondedCompanionFlightToggleActionService
                .isFlightToggleAvailable(feature.bonded())) {
            addFeatureRow(catalog, rowId, "TOGGLE_FLIGHT", "Toggle flight",
                    presentationId, features.flightToggle(), context, false);
        }
        if (BondedCompanionShoulderRideActionService
                .isAvailable(feature.bonded())) {
            addFeatureRow(catalog, rowId, "TOGGLE_SHOULDER_RIDE",
                    "Toggle shoulder ride", presentationId,
                    shoulderRideCallback(context), context, false);
        }
    }

    private void addGenericRow(
            CommandUiActionCatalog catalog,
            UUID rowId,
            String kind,
            String label,
            UUID target,
            @Nullable String value,
            Consumer<UUID> operation,
            BooleanSupplier authority,
            boolean confirmation
    ) {
        catalog.addRow(rowId, kind, label, new GenericUiActionBinding(
                new CommandUiAction(kind, target, value, confirmation),
                authority, () -> apply(operation, target), confirmation));
    }

    private void addFeatureRow(
            CommandUiActionCatalog catalog,
            UUID rowId,
            String kind,
            String label,
            UUID target,
            LinkedNpcPanelFeatureAction operation,
            PageContext context,
            boolean confirmation
    ) {
        catalog.addRow(rowId, kind, label, new GenericUiActionBinding(
                new CommandUiAction(kind, target, null, confirmation),
                context.config().usesBondedCompanionRoster()
                        ? context.toolAuthority() : context.genericAuthority(),
                () -> apply(operation, target, context.ownerUuid()), confirmation));
    }

    private void addBondedRow(
            CommandUiActionCatalog catalog,
            PageContext context,
            UUID rowId,
            CommandPanelFeaturePresentation feature,
            String kind,
            String label,
            boolean confirmation
    ) {
        var bonded = feature.bonded();
        catalog.addBondedRow(rowId, kind, label, new BondedUiActionBinding(
                new CommandUiAction(kind, null, null, confirmation),
                context.ownerUuid(), bonded.rosterId(), bonded.profileId(),
                (owner, roster, profile) -> resolveBondedContext(
                        context, owner, roster, profile), confirmation));
    }

    private CompletionStage<CommandUiActionResult> apply(
            LinkedNpcPanelFeatureAction action,
            UUID target,
            UUID ownerUuid
    ) {
        if (action == null) return CompletableFuture.completedFuture(
                CommandUiActionResult.unavailable("action is unavailable"));
        try {
            PlayerRef playerRef = Universe.get() == null ? null
                    : Universe.get().getPlayer(ownerUuid);
            Ref<EntityStore> ref = playerRef == null ? null
                    : playerRef.getReference();
            Store<EntityStore> store = ref == null ? null : ref.getStore();
            if (ref == null || !ref.isValid() || store == null) {
                return CompletableFuture.completedFuture(
                        CommandUiActionResult.unavailable(
                                "current command world is unavailable"));
            }
            action.accept(target, ref, store);
            return CompletableFuture.completedFuture(CommandUiActionResult.applied());
        } catch (RuntimeException | LinkageError failure) {
            return CompletableFuture.completedFuture(
                    CommandUiActionResult.failed("action failed"));
        }
    }

    @Nullable
    private BondedCompanionPanelActionRouter.CurrentUiContext resolveBondedContext(
            PageContext opened,
            UUID ownerUuid,
            String rosterId,
            String profileId
    ) {
        try {
            PlayerRef playerRef = Universe.get() == null ? null
                    : Universe.get().getPlayer(ownerUuid);
            Ref<EntityStore> ref = playerRef == null ? null
                    : playerRef.getReference();
            Store<EntityStore> store = ref == null ? null : ref.getStore();
            Player player = ref == null || !ref.isValid() || store == null
                    ? null : store.getComponent(ref, Player.getComponentType());
            if (player == null) return null;
            var snapshot = toolInventoryService.buildLinkedPanelSnapshotForTool(
                    player, opened.toolId(), opened.config());
            CommandPanelFeaturePresentation current = snapshot == null ? null
                    : snapshot.featurePresentations().values().stream()
                    .filter(candidate -> candidate != null
                            && candidate.bonded() != null
                            && rosterId.equals(candidate.bonded().rosterId())
                            && profileId.equals(candidate.bonded().profileId()))
                    .findFirst().orElse(null);
            return new BondedCompanionPanelActionRouter.CurrentUiContext(
                    ref, store, opened.config(), current,
                    opened.bondedLifecycleAuthority());
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    @Nullable
    private static UUID rowId(CommandUiSnapshot snapshot, UUID companionUuid) {
        for (var row : snapshot.companionRows()) {
            if (Objects.equals(companionUuid, row.companionUuid())) {
                return row.rowId();
            }
        }
        return null;
    }

    private void openStandardFallback(
            CommandUiHostPage.CurrentWorld currentWorld,
            TwCommandItemConfig config,
            String toolId,
            Actions actions,
            BooleanSupplier genericAuthority,
            BondedLifecycleAuthority bondedAuthority
    ) {
        Ref<EntityStore> ref = currentWorld.playerRef();
        Store<EntityStore> store = currentWorld.store();
        Player currentPlayer = ref == null || store == null || !ref.isValid()
                ? null : store.getComponent(ref, Player.getComponentType());
        ItemStack currentTool = currentPlayer == null ? null
                : toolInventoryService.findUniqueToolStack(currentPlayer, toolId);
        if (currentPlayer == null || currentTool == null || currentTool.isEmpty()) return;
        open(currentPlayer, store, config, currentTool, toolId, actions,
                genericAuthority, bondedAuthority, true);
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
        PageContext context = pageContext(player, uiPlayerRef, config, working,
                toolId, actions, genericCallbackAuthority,
                bondedLifecycleAuthority);
        return createSelectionPage(context, buildNpcCallbacks(context),
                buildFeatureCallbacks(context), buildPanelCallbacks(context));
    }

    private PageContext pageContext(
            Player player,
            PlayerRef uiPlayerRef,
            TwCommandItemConfig config,
            @Nullable ItemStack working,
            String toolId,
            Actions actions,
            BooleanSupplier genericCallbackAuthority,
            BondedLifecycleAuthority bondedLifecycleAuthority
    ) {
        boolean genericRosterActions = CommandRosterStorageBoundary
                .allowsGenericRosterActions(config);
        CommandPanelSnapshotState panelSnapshot = new CommandPanelSnapshotState(
                () -> toolInventoryService.buildLinkedPanelSnapshotForTool(
                        player, toolId, config
                )
        );
        LinkedPanelRefreshSignalSource pageSignals = pageSignals(player, config);
        BooleanSupplier toolAuthority = config.usesBondedCompanionRoster()
                ? () -> {
                    Player current = resolveCurrentPlayer(player.getUuid());
                    return current != null
                            && bondedLifecycleAuthority.allows(current);
                }
                : genericCallbackAuthority;
        return new PageContext(player, uiPlayerRef, config,
                toolId, actions, player.getUuid(), genericRosterActions,
                toolAuthority,
                genericRosterActions ? genericCallbackAuthority : () -> false,
                toolAuthority,
                bondedLifecycleAuthority, panelSnapshot,
                pageSignals,
                working == null ? null : working.getFromMetadataOrNull(
                        TameworkMetadataKeys.COMMAND_SELECTED_ID, Codec.STRING),
                resolveRequireUnlinkConfirm(),
                CommandTravelSettings.isRecallTeleportingEnabled());
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
        String toolId = context.toolId();
        TwCommandItemConfig config = context.config();
        BooleanSupplier genericAuthority = context.genericAuthority();
        BooleanSupplier toolAuthority = context.toolAuthority();
        BooleanSupplier preferenceAuthority = context.preferenceAuthority();
        return new PanelCallbacks(
                context.config().usesBondedCompanionRoster() ? ignoredString
                        : guardedString(preferenceAuthority, value -> withCurrentPlayer(
                                context.ownerUuid(), player -> panelActionService
                                        .applySetPanelMode(player, toolId, value))),
                context.genericRosterActions() ? guardedBoolean(genericAuthority,
                        enabled -> withCurrentPlayer(context.ownerUuid(), player ->
                                panelActionService.applySetAutoLinkEnabled(
                                        player, toolId, config, enabled))) : ignoredBoolean,
                context.genericRosterActions() ? guardedBoolean(genericAuthority,
                        enabled -> withCurrentPlayer(context.ownerUuid(), player ->
                                panelActionService.applySetActiveHighlightEnabled(
                                        player, toolId, config, enabled))) : ignoredBoolean,
                guardedAction(toolAuthority, () -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applyAdjustPanelRadius(player, toolId, config, false))),
                guardedAction(toolAuthority, () -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applyAdjustPanelRadius(player, toolId, config, true))),
                context.genericRosterActions() ? guardedAction(genericAuthority,
                        context.actions().manageGroups()) : ignoredAction,
                guardedString(preferenceAuthority, value -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applySetSort(player, toolId, value))),
                guardedString(preferenceAuthority, value -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applySetFilterMode(player, toolId, value))),
                guardedString(preferenceAuthority, value -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applySetSelectedFilterText(player, toolId, value))),
                guardedAction(preferenceAuthority, () -> withCurrentPlayer(
                        context.ownerUuid(), player -> panelActionService
                                .applyClearFilters(player, toolId))),
                context.genericRosterActions() ? guardedString(genericAuthority,
                        value -> withCurrentPlayer(context.ownerUuid(), player ->
                                groupAssignPageService.applyGroupActivation(
                                        player, toolId, config, value))) : ignoredString,
                context.genericRosterActions() ? guardedPair(genericAuthority,
                        (uuid, group) -> withCurrentPlayer(context.ownerUuid(), player ->
                                groupAssignPageService.applyGroupAssignment(
                                        player, toolId, config, uuid, group)))
                        : (ignoredUuid, ignoredGroup) -> { });
    }

    private static void withCurrentPlayer(
            UUID ownerUuid,
            Consumer<Player> action
    ) {
        Player player = resolveCurrentPlayer(ownerUuid);
        if (player != null) action.accept(player);
    }

    @Nullable
    private static Player resolveCurrentPlayer(UUID ownerUuid) {
        PlayerRef playerRef = Universe.get() == null ? null
                : Universe.get().getPlayer(ownerUuid);
        Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
        Store<EntityStore> store = ref == null || !ref.isValid()
                ? null : ref.getStore();
        return store == null ? null
                : store.getComponent(ref, Player.getComponentType());
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
                             CustomUIPage page) {
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
            BooleanSupplier toolAuthority,
            BooleanSupplier genericAuthority,
            BooleanSupplier preferenceAuthority,
            BondedLifecycleAuthority bondedLifecycleAuthority,
            CommandPanelSnapshotState snapshot,
            LinkedPanelRefreshSignalSource refreshSignals,
            String selectedId,
            boolean requireUnlinkConfirm,
            boolean recallTeleportingEnabled) {
    }

    private record InitialUiState(
            CommandUiSnapshot snapshot,
            CommandPanelSnapshotState panelSnapshot
    ) {
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
