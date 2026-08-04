package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.*;

/** Interactive command-selection page for command items and linked NPC cards. */
public final class TameworkCommandSelectionPage
        extends InteractiveCustomUIPage<CommandSelectionEventData> {
    public static final String UI_PATH = "TameworkCommandRadialMenu.ui";
    public static final String LINKED_PANEL_UI_PATH = "TameworkLinkedNpcPanel.ui";
    public static final String LINKED_PANEL_CARD_UI_PATH = "TameworkLinkedNpcPanelCard.ui";
    public static final String PANEL_MODE_LINKED = "LinkedMode";
    static final String PANEL_MODE_NEARBY = "NearbyMode";
    static final String PANEL_SORT_DEFAULT = "Default";
    static final String PANEL_FILTER_NONE = "None";
    private static final int MAX_COMMAND_BUTTONS = 8;
    private static final long PANEL_FILTER_INPUT_DEBOUNCE_MS = 500L;
    private static final long PAGE_NAVIGATION_DRAIN_DELAY_MS = 100L;
    private static final AtomicLong NEXT_LINKED_PANEL_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<UUID, Long> ACTIVE_LINKED_PANEL_GENERATIONS = new ConcurrentHashMap<>();
    private final CommandSelectionOptionSource.Option[] options;
    private final LinkedNpcPanelCardBinder.CardBindingConfig cardBindingConfig;
    private final CommandSelectionRosterEventBoundary rosterEventBoundary;
    private final boolean requireUnlinkConfirm;
    private final UUID playerUuid;
    private final long linkedPanelGeneration;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier;
    private final LinkedNpcPanelFeatureController featureController;
    private final Supplier<String> panelEmptyStateKeySupplier;
    private final Supplier<String> panelModeValueSupplier;
    private final Supplier<Boolean> panelAutoLinkEnabledSupplier;
    private final Supplier<String> panelRadiusLabelSupplier;
    private final Supplier<String> panelSortValueSupplier;
    private final Supplier<String> panelFilterModeValueSupplier;
    private final Supplier<String> panelFilterInputValueSupplier;
    private final Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier;
    private final Supplier<String> panelGroupActivationValueSupplier;
    private final Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier;
    private final LinkedNpcPanelRefreshLifecycle refreshLifecycle;
    private final LinkedNpcPanelPacketSender packetSender;
    private final LinkedNpcPanelDeferredNavigator deferredNavigator;
    private LinkedNpcEntry[] baseLinkedNpcEntries;
    private LinkedNpcEntry[] linkedNpcEntries;
    private final LinkedNpcPanelCardRenderState cardRenderState;
    private final LinkedNpcPanelRefreshTransaction refreshTransaction = new LinkedNpcPanelRefreshTransaction();
    private UUID pendingUnlinkNpcUuid;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final Consumer<UUID> linkCallback;
    private final Consumer<UUID> unlinkCallback;
    private final Consumer<UUID> toggleActiveCallback;
    private final Consumer<UUID> toggleBreedingCallback;
    private final Consumer<UUID> releaseCallback;
    private final Consumer<UUID> cullCallback;
    private final Consumer<UUID> respawnCallback;
    private final LinkedNpcPanelFeatureAction rosterAbandonCallback;
    private final LinkedNpcPanelFeatureAction flightToggleCallback;
    private LinkedNpcPanelFeatureAction shoulderRideCallback = (id, ref, store) -> { };
    private final Consumer<UUID> locateCallback;
    private final Consumer<UUID> recallCallback;
    private final Consumer<UUID> setHomeCallback;
    private final Consumer<UUID> returnHomeCallback;
    private final Consumer<UUID> openTalentsCallback;
    private final Consumer<String> panelSetModeCallback;
    private final Consumer<Boolean> panelSetAutoLinkEnabledCallback;
    private final Runnable panelRadiusDecreaseCallback;
    private final Runnable panelRadiusIncreaseCallback;
    private final Runnable panelManageGroupsCallback;
    private final Consumer<String> panelSetSortCallback;
    private final Consumer<String> panelSetFilterModeCallback;
    private final Consumer<String> panelSetFilterTextCallback;
    private final Runnable panelClearFiltersCallback;
    private final Consumer<String> panelSetGroupActivationCallback;
    private final BiConsumer<UUID, String> panelAssignGroupCallback;
    private final LinkedNpcPanelGroupAssignOverlayState groupAssignOverlay;
    private volatile boolean dismissed;
    private volatile boolean navigationPending;
    private volatile long pendingFilterTextApplyVersion;
    private String pendingFilterTextInput;

    /** Compatibility constructor retained for pre-flight-toggle callers. */
    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef, @Nonnull TwCommandItemConfig config, String selectedCommandId, boolean requireUnlinkConfirm,
            @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier, @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier,
            @Nonnull Supplier<Map<UUID, CommandPanelFeaturePresentation>> featurePresentationSupplier, @Nonnull Supplier<String> panelEmptyStateKeySupplier,
            @Nonnull Supplier<String> panelModeValueSupplier, @Nonnull Supplier<Boolean> panelAutoLinkEnabledSupplier, @Nonnull Supplier<String> panelRadiusLabelSupplier,
            @Nonnull Supplier<String> panelSortValueSupplier, @Nonnull Supplier<String> panelFilterModeValueSupplier, @Nonnull Supplier<String> panelFilterInputValueSupplier,
            @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier, @Nonnull Supplier<String> panelGroupActivationValueSupplier,
            @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier, @Nonnull Predicate<CommandEntry> commandOptionPredicate,
            boolean recallActionEnabled, @Nonnull Consumer<UUID> linkCallback, @Nonnull Consumer<UUID> unlinkCallback,
            @Nonnull Consumer<UUID> toggleActiveCallback, @Nonnull Consumer<UUID> toggleBreedingCallback, @Nonnull Consumer<UUID> releaseCallback,
            @Nonnull Consumer<UUID> cullCallback, @Nonnull Consumer<UUID> respawnCallback, @Nonnull LinkedNpcPanelFeatureAction rosterSummonCallback,
            @Nonnull LinkedNpcPanelFeatureAction rosterDismissCallback, @Nonnull LinkedNpcPanelFeatureAction paidReviveCallback,
            @Nonnull LinkedNpcPanelFeatureAction rosterAbandonCallback, @Nonnull Consumer<UUID> locateCallback, @Nonnull Consumer<UUID> recallCallback,
            @Nonnull Consumer<UUID> setHomeCallback, @Nonnull Consumer<UUID> returnHomeCallback, @Nonnull Consumer<UUID> openTalentsCallback,
            @Nonnull Consumer<String> panelSetModeCallback, @Nonnull Consumer<Boolean> panelSetAutoLinkEnabledCallback,
            @Nonnull Runnable panelRadiusDecreaseCallback, @Nonnull Runnable panelRadiusIncreaseCallback, @Nonnull Runnable panelManageGroupsCallback,
            @Nonnull Consumer<String> panelSetSortCallback, @Nonnull Consumer<String> panelSetFilterModeCallback,
            @Nonnull Consumer<String> panelSetFilterTextCallback, @Nonnull Runnable panelClearFiltersCallback,
            @Nonnull Consumer<String> panelSetGroupActivationCallback, @Nonnull BiConsumer<UUID, String> panelAssignGroupCallback,
            @Nonnull Consumer<String> selectionCallback) {
        this(playerRef, config, selectedCommandId, requireUnlinkConfirm,
                linkedNpcEntriesSupplier, linkedNpcBaseEntriesSupplier,
                featurePresentationSupplier, panelEmptyStateKeySupplier,
                panelModeValueSupplier, panelAutoLinkEnabledSupplier,
                panelRadiusLabelSupplier, panelSortValueSupplier,
                panelFilterModeValueSupplier, panelFilterInputValueSupplier,
                panelGroupActivationEntriesSupplier, panelGroupActivationValueSupplier,
                panelGroupAssignEntriesSupplier, commandOptionPredicate,
                recallActionEnabled, linkCallback, unlinkCallback,
                toggleActiveCallback, toggleBreedingCallback, releaseCallback,
                cullCallback, respawnCallback, rosterSummonCallback,
                rosterDismissCallback, paidReviveCallback, rosterAbandonCallback,
                (ignored, ref, store) -> { }, locateCallback, recallCallback, setHomeCallback,
                returnHomeCallback, openTalentsCallback, panelSetModeCallback,
                panelSetAutoLinkEnabledCallback, panelRadiusDecreaseCallback,
                panelRadiusIncreaseCallback, panelManageGroupsCallback,
                panelSetSortCallback, panelSetFilterModeCallback,
                panelSetFilterTextCallback, panelClearFiltersCallback,
                panelSetGroupActivationCallback, panelAssignGroupCallback,
                selectionCallback, LinkedPanelRefreshSignalSource.none());
    }
    /** Compatibility constructor retained for existing flight-toggle callers. */
    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        boolean requireUnlinkConfirm,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier,
                                         @Nonnull Supplier<Map<UUID, CommandPanelFeaturePresentation>> featurePresentationSupplier,
                                         @Nonnull Supplier<String> panelEmptyStateKeySupplier,
                                         @Nonnull Supplier<String> panelModeValueSupplier,
                                         @Nonnull Supplier<Boolean> panelAutoLinkEnabledSupplier,
                                         @Nonnull Supplier<String> panelRadiusLabelSupplier,
                                        @Nonnull Supplier<String> panelSortValueSupplier,
                                        @Nonnull Supplier<String> panelFilterModeValueSupplier,
                                        @Nonnull Supplier<String> panelFilterInputValueSupplier,
                                        @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier,
                                        @Nonnull Supplier<String> panelGroupActivationValueSupplier,
                                        @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier,
                                        @Nonnull Predicate<CommandEntry> commandOptionPredicate,
                                        boolean recallActionEnabled,
                                        @Nonnull Consumer<UUID> linkCallback,
                                        @Nonnull Consumer<UUID> unlinkCallback,
                                        @Nonnull Consumer<UUID> toggleActiveCallback,
                                        @Nonnull Consumer<UUID> toggleBreedingCallback,
                                         @Nonnull Consumer<UUID> releaseCallback,
                                         @Nonnull Consumer<UUID> cullCallback,
                                         @Nonnull Consumer<UUID> respawnCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterSummonCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterDismissCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction paidReviveCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterAbandonCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction flightToggleCallback,
                                         @Nonnull Consumer<UUID> locateCallback,
                                         @Nonnull Consumer<UUID> recallCallback,
                                         @Nonnull Consumer<UUID> setHomeCallback,
                                          @Nonnull Consumer<UUID> returnHomeCallback,
                                          @Nonnull Consumer<UUID> openTalentsCallback,
                                          @Nonnull Consumer<String> panelSetModeCallback,
                                          @Nonnull Consumer<Boolean> panelSetAutoLinkEnabledCallback,
                                         @Nonnull Runnable panelRadiusDecreaseCallback,
                                        @Nonnull Runnable panelRadiusIncreaseCallback,
                                        @Nonnull Runnable panelManageGroupsCallback,
                                        @Nonnull Consumer<String> panelSetSortCallback,
                                        @Nonnull Consumer<String> panelSetFilterModeCallback,
                                        @Nonnull Consumer<String> panelSetFilterTextCallback,
                                        @Nonnull Runnable panelClearFiltersCallback,
                                        @Nonnull Consumer<String> panelSetGroupActivationCallback,
                                        @Nonnull BiConsumer<UUID, String> panelAssignGroupCallback,
                                        @Nonnull Consumer<String> selectionCallback) {
        this(playerRef, config, selectedCommandId, requireUnlinkConfirm,
                linkedNpcEntriesSupplier, linkedNpcBaseEntriesSupplier,
                featurePresentationSupplier, panelEmptyStateKeySupplier,
                panelModeValueSupplier, panelAutoLinkEnabledSupplier,
                panelRadiusLabelSupplier, panelSortValueSupplier,
                panelFilterModeValueSupplier, panelFilterInputValueSupplier,
                panelGroupActivationEntriesSupplier, panelGroupActivationValueSupplier,
                panelGroupAssignEntriesSupplier, commandOptionPredicate,
                recallActionEnabled, linkCallback, unlinkCallback,
                toggleActiveCallback, toggleBreedingCallback, releaseCallback,
                cullCallback, respawnCallback, rosterSummonCallback,
                rosterDismissCallback, paidReviveCallback, rosterAbandonCallback,
                flightToggleCallback, locateCallback, recallCallback, setHomeCallback,
                returnHomeCallback, openTalentsCallback, panelSetModeCallback,
                panelSetAutoLinkEnabledCallback, panelRadiusDecreaseCallback,
                panelRadiusIncreaseCallback, panelManageGroupsCallback,
                panelSetSortCallback, panelSetFilterModeCallback,
                panelSetFilterTextCallback, panelClearFiltersCallback,
                panelSetGroupActivationCallback, panelAssignGroupCallback,
                selectionCallback, LinkedPanelRefreshSignalSource.none());
    }
    /** Creates the page with its scoped linked-panel refresh signal source. */
    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        boolean requireUnlinkConfirm,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier,
                                         @Nonnull Supplier<Map<UUID, CommandPanelFeaturePresentation>> featurePresentationSupplier,
                                         @Nonnull Supplier<String> panelEmptyStateKeySupplier,
                                         @Nonnull Supplier<String> panelModeValueSupplier,
                                         @Nonnull Supplier<Boolean> panelAutoLinkEnabledSupplier,
                                         @Nonnull Supplier<String> panelRadiusLabelSupplier,
                                        @Nonnull Supplier<String> panelSortValueSupplier,
                                        @Nonnull Supplier<String> panelFilterModeValueSupplier,
                                        @Nonnull Supplier<String> panelFilterInputValueSupplier,
                                        @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier,
                                        @Nonnull Supplier<String> panelGroupActivationValueSupplier,
                                        @Nonnull Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier,
                                        @Nonnull Predicate<CommandEntry> commandOptionPredicate,
                                        boolean recallActionEnabled,
                                        @Nonnull Consumer<UUID> linkCallback,
                                        @Nonnull Consumer<UUID> unlinkCallback,
                                        @Nonnull Consumer<UUID> toggleActiveCallback,
                                        @Nonnull Consumer<UUID> toggleBreedingCallback,
                                         @Nonnull Consumer<UUID> releaseCallback,
                                         @Nonnull Consumer<UUID> cullCallback,
                                         @Nonnull Consumer<UUID> respawnCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterSummonCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterDismissCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction paidReviveCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction rosterAbandonCallback,
                                         @Nonnull LinkedNpcPanelFeatureAction flightToggleCallback,
                                         @Nonnull Consumer<UUID> locateCallback,
                                         @Nonnull Consumer<UUID> recallCallback,
                                         @Nonnull Consumer<UUID> setHomeCallback,
                                          @Nonnull Consumer<UUID> returnHomeCallback,
                                          @Nonnull Consumer<UUID> openTalentsCallback,
                                          @Nonnull Consumer<String> panelSetModeCallback,
                                          @Nonnull Consumer<Boolean> panelSetAutoLinkEnabledCallback,
                                         @Nonnull Runnable panelRadiusDecreaseCallback,
                                        @Nonnull Runnable panelRadiusIncreaseCallback,
                                        @Nonnull Runnable panelManageGroupsCallback,
                                        @Nonnull Consumer<String> panelSetSortCallback,
                                        @Nonnull Consumer<String> panelSetFilterModeCallback,
                                        @Nonnull Consumer<String> panelSetFilterTextCallback,
                                        @Nonnull Runnable panelClearFiltersCallback,
                                        @Nonnull Consumer<String> panelSetGroupActivationCallback,
                                        @Nonnull BiConsumer<UUID, String> panelAssignGroupCallback,
                                        @Nonnull Consumer<String> selectionCallback,
                                        @Nonnull LinkedPanelRefreshSignalSource refreshSignalSource) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.playerUuid = playerRef.getUuid();
        this.linkedPanelGeneration = NEXT_LINKED_PANEL_GENERATION.incrementAndGet();
        markLinkedPanelOwner();
        this.options = CommandSelectionOptionSource.build(
                config,
                commandOptionPredicate,
                resolveLanguage(),
                MAX_COMMAND_BUTTONS
        );
        this.cardBindingConfig = LinkedNpcPanelCardBindingFactory.create(
                recallActionEnabled,
                config != null && config.usesOwnerCommandFamilyRoster()
        );
        this.rosterEventBoundary = new CommandSelectionRosterEventBoundary(config);
        this.requireUnlinkConfirm = requireUnlinkConfirm;
        this.linkedNpcEntriesSupplier = linkedNpcEntriesSupplier;
        this.linkedNpcBaseEntriesSupplier = linkedNpcBaseEntriesSupplier;
        this.featureController = new LinkedNpcPanelFeatureController(
                featurePresentationSupplier,
                rosterSummonCallback,
                rosterDismissCallback,
                paidReviveCallback
        );
        this.panelEmptyStateKeySupplier = panelEmptyStateKeySupplier;
        this.panelModeValueSupplier = panelModeValueSupplier;
        this.panelAutoLinkEnabledSupplier = panelAutoLinkEnabledSupplier;
        this.panelRadiusLabelSupplier = panelRadiusLabelSupplier;
        this.panelSortValueSupplier = panelSortValueSupplier;
        this.panelFilterModeValueSupplier = panelFilterModeValueSupplier;
        this.panelFilterInputValueSupplier = panelFilterInputValueSupplier;
        this.panelGroupActivationEntriesSupplier = new LinkedNpcPanelDropdownEntries(panelGroupActivationEntriesSupplier);
        this.panelGroupActivationValueSupplier = panelGroupActivationValueSupplier;
        this.panelGroupAssignEntriesSupplier = panelGroupAssignEntriesSupplier;
        this.baseLinkedNpcEntries = new LinkedNpcEntry[0];
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.cardRenderState = new LinkedNpcPanelCardRenderState();
        this.pendingUnlinkNpcUuid = null;
        this.selectedCommandId = selectedCommandId;
        this.linkCallback = linkCallback;
        this.unlinkCallback = unlinkCallback;
        this.toggleActiveCallback = toggleActiveCallback;
        this.toggleBreedingCallback = toggleBreedingCallback;
        this.releaseCallback = releaseCallback;
        this.cullCallback = cullCallback;
        this.respawnCallback = respawnCallback;
        this.rosterAbandonCallback = rosterAbandonCallback;
        this.flightToggleCallback = flightToggleCallback;
        this.locateCallback = locateCallback;
        this.recallCallback = recallCallback;
        this.setHomeCallback = setHomeCallback;
        this.returnHomeCallback = returnHomeCallback;
        this.openTalentsCallback = openTalentsCallback;
        this.panelSetModeCallback = panelSetModeCallback;
        this.panelSetAutoLinkEnabledCallback = panelSetAutoLinkEnabledCallback;
        this.panelRadiusDecreaseCallback = panelRadiusDecreaseCallback;
        this.panelRadiusIncreaseCallback = panelRadiusIncreaseCallback;
        this.panelManageGroupsCallback = panelManageGroupsCallback;
        this.panelSetSortCallback = panelSetSortCallback;
        this.panelSetFilterModeCallback = panelSetFilterModeCallback;
        this.panelSetFilterTextCallback = panelSetFilterTextCallback;
        this.panelClearFiltersCallback = panelClearFiltersCallback;
        this.panelSetGroupActivationCallback = panelSetGroupActivationCallback;
        this.panelAssignGroupCallback = panelAssignGroupCallback;
        this.selectionCallback = selectionCallback;
        this.groupAssignOverlay = new LinkedNpcPanelGroupAssignOverlayState(resolveLanguage());
        this.refreshLifecycle = new LinkedNpcPanelRefreshLifecycle(
                Objects.requireNonNull(refreshSignalSource, "refreshSignalSource"),
                new LinkedPanelRefreshCoordinator(System::currentTimeMillis,
                        LinkedPanelRefreshCoordinator.DelayedScheduler.production(),
                        this::dispatchRefreshPermit));
        LinkedNpcPanelPacketSender testSender = LinkedNpcPanelRefreshTestSeam.takePacketSender();
        this.packetSender = testSender != null
                ? testSender : (commands, events) -> sendUpdate(commands, events, false);
        LinkedNpcPanelDeferredNavigator testNavigator = LinkedNpcPanelRefreshTestSeam.takeDeferredNavigator();
        this.deferredNavigator = testNavigator != null ? testNavigator : (owner, action) ->
                CompletableFuture.runAsync(() -> CommandPageWorldDispatcher.dispatch(owner.getReference(), () -> {
                    Ref<EntityStore> activeRef = owner.getReference();
                    if (activeRef != null && activeRef.isValid()) action.run();
                }), CompletableFuture.delayedExecutor(PAGE_NAVIGATION_DRAIN_DELAY_MS, TimeUnit.MILLISECONDS));
        this.dismissed = false;
        this.navigationPending = false;
        this.pendingFilterTextApplyVersion = 0L;
        this.pendingFilterTextInput = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        try {
            refreshLinkedNpcEntries();
            commandBuilder.append(UI_PATH);
            commandBuilder.append(LINKED_PANEL_UI_PATH);
            BondedCompanionPanelChrome.bind(commandBuilder, rosterEventBoundary.bondedRoster());
            commandBuilder.set("#TameworkCommandMenuWheel.Visible", true);
            commandBuilder.set("#TameworkCommandMenuTitle.Text", LocalizedText.resolve(playerRef, "tamework.ui.commandMenu.title"));
            commandBuilder.set("#TameworkCommandMenuSubtitle.Text", LocalizedText.resolve(playerRef, "tamework.ui.commandMenu.subtitle"));
            commandBuilder.set(
                    "#TameworkCommandMenuCurrent.Text",
                    CommandSelectionOptionSource.currentLabel(
                            options, selectedCommandId, resolveLanguage()
                    )
            );
            commandBuilder.set("#TameworkLinkedPanelRoot.Visible", true);
            commandBuilder.set("#TameworkLinkedPanelTitle.Text", LinkedNpcPanelPresentationSupport.title(panelModeValueSupplier, linkedNpcEntries, resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Entries", LinkedNpcPanelPresentationSupport.entries(panelGroupActivationEntriesSupplier));
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Value", LinkedNpcPanelPresentationSupport.value(panelGroupActivationValueSupplier, ""));
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", CommandSelectionPanelOptions.resolveModeDropdownEntries(resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", LinkedNpcPanelPresentationSupport.mode(panelModeValueSupplier));
            commandBuilder.set("#TameworkLinkedPanelAutoLinkCheck.Value", LinkedNpcPanelPresentationSupport.autoLink(panelAutoLinkEnabledSupplier));
            commandBuilder.set("#TameworkLinkedPanelSubtitleRadiusControls.Visible", LinkedNpcPanelPresentationSupport.nearby(panelModeValueSupplier));
            commandBuilder.set("#TameworkLinkedPanelRadiusValue.Text", LinkedNpcPanelPresentationSupport.radius(panelRadiusLabelSupplier, resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelSortDropdown.Entries", CommandSelectionPanelOptions.resolveSortDropdownEntries(resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelSortDropdown.Value", LinkedNpcPanelPresentationSupport.sort(panelSortValueSupplier));
            commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Entries", CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Value", LinkedNpcPanelPresentationSupport.filterMode(panelFilterModeValueSupplier));
            boolean showFilterInputControls = LinkedNpcPanelPresentationSupport.showFilter(panelFilterModeValueSupplier);
            commandBuilder.set("#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
            commandBuilder.set("#TameworkLinkedPanelFilterInput.Value", pendingFilterTextInput != null ? pendingFilterTextInput : LinkedNpcPanelPresentationSupport.input(panelFilterInputValueSupplier));
            refreshTransaction.applyGroupOverlay(groupAssignOverlay, commandBuilder, resolveLanguage());
            refreshTransaction.applyReviveOverlay(featureController, commandBuilder, resolveLanguage());
            CommandPageButtonBinder.bind(commandBuilder, eventBuilder, options);
            buildLinkedNpcPanel(commandBuilder, eventBuilder);
            CommandSelectionPageEventBinder.bindPanelControls(
                    eventBuilder, featureController
            );
            CommandSelectionPageEventBinder.bindClose(eventBuilder);
            seedRefreshValues();
            refreshTransaction.seedOverlayRevisions(groupAssignOverlay.revision(), featureController.reviveOverlayRevision());
            refreshLifecycle.start(true, shortestVisibleCountdownRemainingMs(),
                    !rosterEventBoundary.bondedRoster());
        } catch (Throwable throwable) {
            dismissed = true;
            clearLinkedPanelOwner();
            refreshLifecycle.close();
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_page_build_failed",
                    throwable,
                    TameworkTelemetryContext.uiPage(
                            "TameworkCommandSelectionPage",
                            "command_item",
                            "build",
                            "Failed to build command selection page."
                    ).build()
            );
            throw throwable;
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull CommandSelectionEventData data) {
        String receivedCommandId = data.commandId == null ? "" : data.commandId.trim();
        if (dismissed && !navigationPending) {
            return;
        }
        if (navigationPending) {
            return;
        }
        if (!isCurrentLinkedPanelOwner()) {
            return;
        }
        String commandId = receivedCommandId;
        if (handleShoulderRide(commandId, ref, store)) {
            return;
        }
        if (handleLinkedFlightToggle(commandId, ref, store)) {
            return;
        }
        if (handleBondedFlightToggle(commandId, ref, store)) {
            return;
        }
        if (handleBondedAbandon(commandId, ref, store)) {
            return;
        }
        LinkedNpcPanelFeatureController.Outcome featureOutcome =
                featureController.handle(
                        commandId, ref, store, this::resolveLinkedNpcEntry
                );
        if (featureOutcome
                == LinkedNpcPanelFeatureController.Outcome.REFRESH) {
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (featureOutcome
                == LinkedNpcPanelFeatureController.Outcome.HANDLED) {
            return;
        }
        if (commandId.startsWith(OPEN_TALENTS_COMMAND_PREFIX)) {
            if (openTalentsCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId,
                    OPEN_TALENTS_COMMAND_PREFIX);
            if (npcUuid != null) {
                if (!beginPageNavigation()) {
                    return;
                }
                navigateAfterUiDrain(() -> {
                    try {
                        openTalentsCallback.accept(npcUuid);
                    } finally {
                        navigationPending = false;
                    }
                });
            }
            return;
        }
        if (rosterEventBoundary.blocks(data, commandId)) {
            return;
        }
        if (data.panelGroupAssignValue != null) {
            groupAssignOverlay.updateSelectedValue(data.panelGroupAssignValue);
        }
        if (!commandId.isBlank() && commandId.startsWith(OPEN_GROUP_PICKER_COMMAND_PREFIX)) {
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, OPEN_GROUP_PICKER_COMMAND_PREFIX);
            if (npcUuid != null) {
                pendingUnlinkNpcUuid = null;
                openGroupAssignOverlay(npcUuid);
                sendCardRefreshUpdate();
            }
            return;
        }
        if (groupAssignOverlay.isVisible()) {
            if (PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID.equals(commandId)) {
                groupAssignOverlay.clear(resolveLanguage());
                sendCardRefreshUpdate();
                return;
            }
            if (PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID.equals(commandId)) {
                applyGroupAssignSelection();
                sendCardRefreshUpdate();
                return;
            }
            if (CLOSE_COMMAND_ID.equals(commandId)) {
                pendingUnlinkNpcUuid = null;
                closePage();
                return;
            }
            return;
        }
        if (data.panelGroupActiveValue != null) {
            cancelPendingFilterTextApply();
            if (panelSetGroupActivationCallback != null) {
                panelSetGroupActivationCallback.accept(data.panelGroupActiveValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelAutoLinkEnabled != null) {
            cancelPendingFilterTextApply();
            if (panelSetAutoLinkEnabledCallback != null) {
                panelSetAutoLinkEnabledCallback.accept(data.panelAutoLinkEnabled);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelModeValue != null) {
            cancelPendingFilterTextApply();
            if (panelSetModeCallback != null) {
                panelSetModeCallback.accept(data.panelModeValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelSortValue != null) {
            cancelPendingFilterTextApply();
            if (panelSetSortCallback != null) {
                panelSetSortCallback.accept(data.panelSortValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterModeValue != null) {
            cancelPendingFilterTextApply();
            if (panelSetFilterModeCallback != null) {
                panelSetFilterModeCallback.accept(data.panelFilterModeValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterTextInput != null) {
            pendingFilterTextInput = data.panelFilterTextInput;
            scheduleDebouncedFilterTextApply();
            return;
        }
        if (commandId.isBlank()) {
            return;
        }
        if (CLOSE_COMMAND_ID.equals(commandId)) {
            pendingUnlinkNpcUuid = null;
            closePage();
            return;
        }
        if (PANEL_RADIUS_DECREASE_COMMAND_ID.equals(commandId)) {
            if (panelRadiusDecreaseCallback != null) {
                panelRadiusDecreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_RADIUS_INCREASE_COMMAND_ID.equals(commandId)) {
            if (panelRadiusIncreaseCallback != null) {
                panelRadiusIncreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_MANAGE_GROUPS_COMMAND_ID.equals(commandId)) {
            if (panelManageGroupsCallback != null) {
                if (!beginPageNavigation()) {
                    return;
                }
                pendingUnlinkNpcUuid = null;
                navigateAfterUiDrain(() -> {
                    try {
                        panelManageGroupsCallback.run();
                    } finally {
                        navigationPending = false;
                    }
                });
            }
            return;
        }
        if (PANEL_FILTER_CLEAR_COMMAND_ID.equals(commandId)) {
            cancelPendingFilterTextApply();
            if (panelClearFiltersCallback != null) {
                panelClearFiltersCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(LINK_COMMAND_PREFIX)) {
            if (linkCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, LINK_COMMAND_PREFIX);
            if (npcUuid != null) {
                linkCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(UNLINK_COMMAND_PREFIX)) {
            if (unlinkCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, UNLINK_COMMAND_PREFIX);
            if (npcUuid != null) {
                LinkedNpcEntry entry = resolveLinkedNpcEntry(npcUuid);
                boolean linkedEntry = entry != null && entry.linked();
                if (!linkedEntry) {
                    pendingUnlinkNpcUuid = npcUuid;
                    sendCardRefreshUpdate();
                    return;
                }
                if (requireUnlinkConfirm && !isPendingUnlink(npcUuid)) {
                    pendingUnlinkNpcUuid = npcUuid;
                    sendCardRefreshUpdate();
                    return;
                }
                unlinkCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(RELEASE_COMMAND_PREFIX)) {
            if (releaseCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, RELEASE_COMMAND_PREFIX);
            if (npcUuid != null) {
                releaseCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(CULL_COMMAND_PREFIX)) {
            if (cullCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, CULL_COMMAND_PREFIX);
            if (npcUuid != null) {
                cullCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(TOGGLE_ACTIVE_COMMAND_PREFIX)) {
            if (toggleActiveCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, TOGGLE_ACTIVE_COMMAND_PREFIX);
            if (npcUuid != null) {
                toggleActiveCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(TOGGLE_BREEDING_COMMAND_PREFIX)) {
            if (toggleBreedingCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, TOGGLE_BREEDING_COMMAND_PREFIX);
            if (npcUuid != null) {
                toggleBreedingCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(RESPAWN_COMMAND_PREFIX)) {
            if (respawnCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, RESPAWN_COMMAND_PREFIX);
            if (npcUuid != null) {
                respawnCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                refreshLifecycle.requestInteractionFeedback();
            }
            return;
        }
        if (commandId.startsWith(LOCATE_COMMAND_PREFIX)) {
            if (locateCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, LOCATE_COMMAND_PREFIX);
            if (npcUuid != null) {
                locateCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
            }
            return;
        }
        if (commandId.startsWith(RECALL_COMMAND_PREFIX)) {
            if (recallCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, RECALL_COMMAND_PREFIX);
            if (npcUuid != null) {
                recallCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(SET_HOME_COMMAND_PREFIX)) {
            if (setHomeCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, SET_HOME_COMMAND_PREFIX);
            if (npcUuid != null) {
                setHomeCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (commandId.startsWith(RETURN_HOME_COMMAND_PREFIX)) {
            if (returnHomeCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, RETURN_HOME_COMMAND_PREFIX);
            if (npcUuid != null) {
                returnHomeCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (!CommandSelectionOptionSource.contains(options, commandId)) {
            pendingUnlinkNpcUuid = null;
            closePage();
            return;
        }
        pendingUnlinkNpcUuid = null;
        closePage();
        selectionCallback.accept(commandId);
    }

    private boolean handleBondedFlightToggle(
            String commandId, Ref<EntityStore> ref, Store<EntityStore> store) {
        return finishAuxiliaryCommand(BondedCompanionAuxiliaryCommandHandler.handleFlight(commandId,
                rosterEventBoundary.bondedRoster(), featureController::presentation,
                flightToggleCallback, ref, store));
    }

    private boolean handleShoulderRide(
            String commandId, Ref<EntityStore> ref, Store<EntityStore> store) {
        boolean handled = BondedCompanionAuxiliaryCommandHandler.handleShoulderRide(commandId,
                rosterEventBoundary.bondedRoster(), featureController::presentation,
                shoulderRideCallback, ref, store)
                || BondedCompanionAuxiliaryCommandHandler.handleLinkedShoulderRide(commandId,
                rosterEventBoundary.bondedRoster(), this::resolveLinkedNpcEntry,
                shoulderRideCallback, ref, store);
        return finishAuxiliaryCommand(handled);
    }

    /** Installs the bonded shoulder action before the page is opened. */
    public void configureShoulderRideCallback(@Nonnull LinkedNpcPanelFeatureAction callback) {
        shoulderRideCallback = Objects.requireNonNull(callback, "callback");
    }

    private boolean handleLinkedFlightToggle(
            String commandId, Ref<EntityStore> ref, Store<EntityStore> store) {
        return finishAuxiliaryCommand(BondedCompanionAuxiliaryCommandHandler.handleLinkedFlight(commandId,
                rosterEventBoundary.bondedRoster(), this::resolveLinkedNpcEntry,
                flightToggleCallback, ref, store));
    }

    private boolean finishAuxiliaryCommand(boolean handled) {
        if (!handled) return false;
        refreshLinkedNpcEntries();
        refreshLifecycle.requestInteractionFeedback();
        return true;
    }

    private boolean handleBondedAbandon(
            String commandId,
            Ref<EntityStore> ref,
            Store<EntityStore> store
    ) {
        BondedCompanionUnlinkDecision.Decision decision =
                BondedCompanionUnlinkDecision.resolve(commandId,
                        UNLINK_COMMAND_PREFIX, pendingUnlinkNpcUuid,
                        featureController);
        if (!decision.handled()) {
            return false;
        }
        if (!decision.confirmed()) {
            pendingUnlinkNpcUuid = decision.npcUuid();
            sendCardRefreshUpdate();
            return true;
        }
        rosterAbandonCallback.accept(decision.npcUuid(), ref, store);
        pendingUnlinkNpcUuid = null;
        refreshLinkedNpcEntries();
        sendCardRefreshUpdate();
        return true;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true;
        navigationPending = false;
        clearLinkedPanelOwner();
        refreshLifecycle.close();
    }

    private void buildLinkedNpcPanel(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull UIEventBuilder eventBuilder) {
        cardRenderState.markRendered(linkedNpcEntries, pendingUnlinkNpcUuid,
                featureController.presentations());
        commandBuilder.clear("#TameworkLinkedPanelList");
        boolean hasEntries = linkedNpcEntries.length > 0;
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Text",
                LinkedNpcPanelPresentationSupport.empty(panelEmptyStateKeySupplier, resolveLanguage()));
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commandBuilder.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        if (!hasEntries) {
            return;
        }
        for (int i = 0; i < linkedNpcEntries.length; i++) {
            bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], true);
        }
    }

    private void dispatchRefreshPermit(LinkedPanelRefreshCoordinator.RenderPermit permit) {
        if (dismissed || !isCurrentLinkedPanelOwner()) {
            completeRefreshPermit(permit, false);
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            completeRefreshPermit(permit, false);
            return;
        }
        LinkedNpcPanelRefreshPermitDispatch.dispatch(
                permit,
                task -> CommandPageWorldDispatcher.tryDispatch(ref, task),
                () -> runRefreshOnWorldThread(permit),
                rejectedPermit -> completeRefreshPermit(rejectedPermit, false));
    }

    private void scheduleDebouncedFilterTextApply() {
        long version = ++pendingFilterTextApplyVersion;
        CompletableFuture.runAsync(
                () -> dispatchDebouncedFilterTextApply(version),
                CompletableFuture.delayedExecutor(PANEL_FILTER_INPUT_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void dispatchDebouncedFilterTextApply(long version) {
        if (dismissed || !isCurrentLinkedPanelOwner() || version != pendingFilterTextApplyVersion) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) {
            return;
        }
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }
        CommandPageWorldDispatcher.dispatch(ref, () -> runDebouncedFilterTextApplyOnWorldThread(version));
    }

    private void runDebouncedFilterTextApplyOnWorldThread(long version) {
        if (dismissed || !isCurrentLinkedPanelOwner() || version != pendingFilterTextApplyVersion) {
            return;
        }
        if (panelSetFilterTextCallback != null) {
            panelSetFilterTextCallback.accept(pendingFilterTextInput);
        }
        pendingFilterTextInput = null;
        pendingUnlinkNpcUuid = null;
        applyLocalFilter();
        sendCardRefreshUpdate();
    }

    private void cancelPendingFilterTextApply() {
        pendingFilterTextApplyVersion++;
        pendingFilterTextInput = null;
    }

    private void runRefreshOnWorldThread(LinkedPanelRefreshCoordinator.RenderPermit permit) {
        if (dismissed || !isCurrentLinkedPanelOwner() || isFilterEditPending()) {
            completeRefreshPermit(permit, false);
            return;
        }
        try {
            refreshLinkedNpcEntries();
            LinkedNpcPanelRefreshOutcome outcome = sendCardRefreshUpdate(permit.progressionEligible());
            completeRefreshPermit(permit, outcome.progressionIncluded(), outcome.shortestCountdownRemainingMs());
        } catch (Throwable exception) {
            completeRefreshPermit(permit, false);
            TameworkTelemetryEvents.recordErrorIfAvailable(
                    "ui_linked_panel_refresh_failed", exception,
                    TameworkTelemetryContext.uiPage("TameworkCommandSelectionPage",
                            "command_item", "refresh",
                            "Failed to refresh linked panel.").build());
        }
    }
    private void completeRefreshPermit(LinkedPanelRefreshCoordinator.RenderPermit permit,
                                       boolean progressionIncluded) {
        completeRefreshPermit(permit, progressionIncluded,
                LinkedPanelRefreshCoordinator.NO_COUNTDOWN_REMAINING_MS);
    }
    private void completeRefreshPermit(LinkedPanelRefreshCoordinator.RenderPermit permit,
                                       boolean progressionIncluded,
                                       long shortestCountdownRemainingMs) {
        refreshLifecycle.recordRendered(permit, progressionIncluded, shortestCountdownRemainingMs);
    }
    private void sendCardRefreshUpdate() {
        refreshLifecycle.requestStateMutation();
    }
    private LinkedNpcPanelRefreshOutcome sendCardRefreshUpdate(boolean progressionEligible) {
        if (dismissed || !isCurrentLinkedPanelOwner()) {
            return LinkedNpcPanelRefreshOutcome.notSent(shortestVisibleCountdownRemainingMs());
        }
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        LinkedNpcPanelRefreshValues candidateValues = refreshTransaction.stagedValues();
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelTitle.Text", LinkedNpcPanelPresentationSupport.title(panelModeValueSupplier, linkedNpcEntries, resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelGroupSelectorDropdown.Entries", LinkedNpcPanelPresentationSupport.entries(panelGroupActivationEntriesSupplier));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelGroupSelectorDropdown.Value", LinkedNpcPanelPresentationSupport.value(panelGroupActivationValueSupplier, ""));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelModeDropdown.Entries", CommandSelectionPanelOptions.resolveModeDropdownEntries(resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelModeDropdown.Value", LinkedNpcPanelPresentationSupport.mode(panelModeValueSupplier));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelAutoLinkCheck.Value", LinkedNpcPanelPresentationSupport.autoLink(panelAutoLinkEnabledSupplier));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelSubtitleRadiusControls.Visible", LinkedNpcPanelPresentationSupport.nearby(panelModeValueSupplier));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelRadiusValue.Text", LinkedNpcPanelPresentationSupport.radius(panelRadiusLabelSupplier, resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelSortDropdown.Entries", CommandSelectionPanelOptions.resolveSortDropdownEntries(resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelSortDropdown.Value", LinkedNpcPanelPresentationSupport.sort(panelSortValueSupplier));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelFilterDropdown.Entries", CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelFilterDropdown.Value", LinkedNpcPanelPresentationSupport.filterMode(panelFilterModeValueSupplier));
        boolean showFilterInputControls = LinkedNpcPanelPresentationSupport.showFilter(panelFilterModeValueSupplier);
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
        if (!isFilterEditPending()) {
            candidateValues.set(commandBuilder, "#TameworkLinkedPanelFilterInput.Value", LinkedNpcPanelPresentationSupport.input(panelFilterInputValueSupplier));
        }
        long groupAssignOverlayRevision = refreshTransaction.applyGroupOverlay(groupAssignOverlay, commandBuilder, resolveLanguage());
        long reviveOverlayRevision = refreshTransaction.applyReviveOverlay(featureController, commandBuilder, resolveLanguage());
        boolean hasEntries = linkedNpcEntries.length > 0;
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelEmptyState.Text",
                LinkedNpcPanelPresentationSupport.empty(panelEmptyStateKeySupplier, resolveLanguage()));
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        candidateValues.set(commandBuilder, "#TameworkLinkedPanelListViewport.Visible", hasEntries);
        Map<UUID, CommandPanelFeaturePresentation> featurePresentations = new java.util.HashMap<>();
        for (Map.Entry<UUID, CommandPanelFeaturePresentation> entry : featureController.presentations().entrySet()) {
            featurePresentations.put(entry.getKey(), BondedCompanionProgressionProjection.project(
                    cardRenderState.presentation(entry.getKey()), entry.getValue(), progressionEligible));
        }
        boolean structureChanged = cardRenderState.requiresRebuild(
                linkedNpcEntries, featurePresentations);
        if (structureChanged) {
            commandBuilder.clear("#TameworkLinkedPanelList");
            if (hasEntries) {
                for (int i = 0; i < linkedNpcEntries.length; i++) {
                    bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], true,
                            featurePresentations.get(linkedNpcEntries[i].npcUuid()));
                }
            }
        } else if (hasEntries) {
            for (int i = 0; i < linkedNpcEntries.length; i++) {
                LinkedNpcPanelCardRenderState.Update update =
                        cardRenderState.updateAt(i, linkedNpcEntries,
                                pendingUnlinkNpcUuid, featurePresentations);
                if (update == LinkedNpcPanelCardRenderState.Update.FULL) {
                    bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], false,
                            featurePresentations.get(linkedNpcEntries[i].npcUuid()));
                } else if (update == LinkedNpcPanelCardRenderState.Update.DYNAMIC) {
                    UUID npcUuid = linkedNpcEntries[i].npcUuid();
                    CommandPanelFeaturePresentation current =
                            featurePresentations.get(npcUuid);
                    String selector = "#TameworkLinkedPanelList[" + i + "]";
                    LinkedNpcPanelCardDynamicPresenter.refresh(
                            commandBuilder, eventBuilder, selector, npcUuid,
                            cardRenderState.entryAt(i), linkedNpcEntries[i],
                            cardRenderState.presentation(npcUuid), current,
                            isPendingUnlink(npcUuid), cardBindingConfig, resolveLanguage());
                }
            }
        }
        if (commandBuilder.getCommands().length == 0 && eventBuilder.getEvents().length == 0) {
            return LinkedNpcPanelRefreshOutcome.evaluated(progressionEligible, shortestVisibleCountdownRemainingMs());
        }
        packetSender.send(commandBuilder, eventBuilder);
        refreshTransaction.commit(candidateValues, groupAssignOverlayRevision, reviveOverlayRevision);
        cardRenderState.markRendered(linkedNpcEntries, pendingUnlinkNpcUuid, featurePresentations);
        return LinkedNpcPanelRefreshOutcome.sent(progressionEligible, shortestVisibleCountdownRemainingMs());
    }
    private long shortestVisibleCountdownRemainingMs() {
        return LinkedNpcPanelCountdowns.shortest(
                featureController.presentations(), linkedNpcEntries
        );
    }

    /** Seeds chrome deduplication from the initial page packet. */
    private void seedRefreshValues() {
        LinkedNpcPanelRefreshValueSeeder.seed(refreshTransaction.values(), resolveLanguage(), linkedNpcEntries,
                pendingFilterTextInput, panelEmptyStateKeySupplier, panelModeValueSupplier,
                panelAutoLinkEnabledSupplier, panelRadiusLabelSupplier, panelSortValueSupplier,
                panelFilterModeValueSupplier, panelFilterInputValueSupplier,
                panelGroupActivationEntriesSupplier, panelGroupActivationValueSupplier);
    }
    private void closePage() {
        dismissed = true;
        navigationPending = false;
        clearLinkedPanelOwner();
        refreshLifecycle.close();
        close();
    }
    private boolean beginPageNavigation() {
        if (navigationPending) {
            return false;
        }
        navigationPending = true;
        dismissed = true;
        clearLinkedPanelOwner();
        cancelPendingFilterTextApply();
        refreshLifecycle.close();
        return true;
    }

    private void markLinkedPanelOwner() {
        if (playerUuid != null) {
            ACTIVE_LINKED_PANEL_GENERATIONS.put(playerUuid, linkedPanelGeneration);
        }
    }

    private void clearLinkedPanelOwner() {
        if (playerUuid != null) {
            ACTIVE_LINKED_PANEL_GENERATIONS.remove(playerUuid, linkedPanelGeneration);
        }
    }

    private boolean isCurrentLinkedPanelOwner() {
        if (playerUuid == null) {
            return true;
        }
        return Long.valueOf(linkedPanelGeneration).equals(ACTIVE_LINKED_PANEL_GENERATIONS.get(playerUuid));
    }

    private void navigateAfterUiDrain(@Nonnull Runnable action) {
        deferredNavigator.navigate(playerRef, action);
    }

    private void bindLinkedNpcCard(@Nonnull UICommandBuilder commandBuilder,
                                   @Nonnull UIEventBuilder eventBuilder,
                                   int index,
                                   LinkedNpcEntry entry,
                                   boolean appendCard) {
        boolean pendingUnlink = isPendingUnlink(entry.npcUuid());
        LinkedNpcPanelCardBinder.bind(
                commandBuilder,
                eventBuilder,
                index,
                entry,
                appendCard,
                pendingUnlink,
                cardBindingConfig,
                resolveLanguage(),
                featureController.presentation(entry.npcUuid())
        );
    }
    private void bindLinkedNpcCard(@Nonnull UICommandBuilder commandBuilder,
                                   @Nonnull UIEventBuilder eventBuilder, int index,
                                   LinkedNpcEntry entry, boolean appendCard,
                                   CommandPanelFeaturePresentation presentation) {
        LinkedNpcPanelCardBinder.bind(commandBuilder, eventBuilder, index, entry, appendCard,
                isPendingUnlink(entry.npcUuid()), cardBindingConfig, resolveLanguage(), presentation);
    }
    private void openGroupAssignOverlay(@Nonnull UUID npcUuid) {
        LinkedNpcEntry entry = resolveLinkedNpcEntry(npcUuid);
        if (entry == null) {
            refreshLinkedNpcEntries();
            entry = resolveLinkedNpcEntry(npcUuid);
        }
        if (entry == null) {
            return;
        }
        groupAssignOverlay.open(npcUuid, entry, resolveGroupAssignEntries(), resolveLanguage());
    }

    private void applyGroupAssignSelection() {
        LinkedNpcPanelGroupAssignOverlayState.AppliedSelection selection =
                groupAssignOverlay.consumeSelection(resolveLanguage());
        if (selection.npcUuid() == null || panelAssignGroupCallback == null) {
            return;
        }
        panelAssignGroupCallback.accept(selection.npcUuid(), selection.groupId());
        pendingUnlinkNpcUuid = null;
        refreshLinkedNpcEntries();
    }

    private List<DropdownEntryInfo> resolveGroupAssignEntries() {
        List<DropdownEntryInfo> resolved = panelGroupAssignEntriesSupplier != null
                ? panelGroupAssignEntriesSupplier.get()
                : List.of();
        return resolved != null ? resolved : List.of();
    }

    private LinkedNpcEntry resolveLinkedNpcEntry(@Nonnull UUID npcUuid) {
        for (LinkedNpcEntry entry : linkedNpcEntries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            if (entry.npcUuid().equals(npcUuid)) {
                return entry;
            }
        }
        return null;
    }

    private void refreshLinkedNpcEntries() {
        List<LinkedNpcEntry> entries = linkedNpcBaseEntriesSupplier != null ? linkedNpcBaseEntriesSupplier.get()
                : linkedNpcEntriesSupplier != null ? linkedNpcEntriesSupplier.get() : List.of();
        baseLinkedNpcEntries = LinkedNpcEntrySnapshotMapper.build(
                entries,
                LocalizedText.resolve(resolveLanguage(), "tamework.ui.linkedPanel.subtitle.defaultNpcName")
        );
        applyLocalFilter();
        featureController.refresh();
        if (pendingUnlinkNpcUuid != null && resolveLinkedNpcEntry(pendingUnlinkNpcUuid) == null) {
            pendingUnlinkNpcUuid = null;
        }
    }

    private void applyLocalFilter() {
        linkedNpcEntries = LinkedNpcPanelPresentationSupport.filter(
                baseLinkedNpcEntries, LinkedNpcPanelPresentationSupport.filterMode(panelFilterModeValueSupplier),
                LinkedNpcPanelPresentationSupport.input(panelFilterInputValueSupplier));
    }

    private boolean isFilterEditPending() { return pendingFilterTextInput != null; }
    private String resolveLanguage() { return playerRef != null ? playerRef.getLanguage() : null; }
    private boolean isPendingUnlink(UUID npcUuid) { return npcUuid != null && npcUuid.equals(pendingUnlinkNpcUuid); }

}
