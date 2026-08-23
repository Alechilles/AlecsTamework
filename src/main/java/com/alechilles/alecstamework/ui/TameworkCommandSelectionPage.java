package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.items.CommandHotswapAssignmentStore.Slot;
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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
    static final long PANEL_FILTER_INPUT_DEBOUNCE_MS = 500L;
    private static final long PAGE_NAVIGATION_DRAIN_DELAY_MS = 100L;
    private static final AtomicLong NEXT_LINKED_PANEL_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<UUID, Long> ACTIVE_LINKED_PANEL_GENERATIONS = new ConcurrentHashMap<>();
    private final CommandSelectionOptionSource.Option[] options;
    final TwCommandItemConfig config;
    final LinkedNpcPanelCardBinder.CardBindingConfig cardBindingConfig;
    private final CommandSelectionRosterEventBoundary rosterEventBoundary;
    private final boolean requireUnlinkConfirm;
    private final UUID playerUuid;
    private final long linkedPanelGeneration;
    final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    final Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier;
    final LinkedNpcPanelFeatureController featureController;
    final Supplier<String> panelEmptyStateKeySupplier;
    final Supplier<String> panelModeValueSupplier;
    final Supplier<Boolean> panelAutoLinkEnabledSupplier;
    CommandActiveHighlightBinding activeHighlightBinding =
            new CommandActiveHighlightBinding(false, () -> false, ignored -> { });
    final Supplier<String> panelRadiusLabelSupplier;
    final Supplier<String> panelSortValueSupplier;
    final Supplier<String> panelFilterModeValueSupplier;
    final Supplier<String> panelFilterInputValueSupplier;
    final Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier;
    final Supplier<String> panelGroupActivationValueSupplier;
    final Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier;
    final LinkedNpcPanelRefreshLifecycle refreshLifecycle;
    LinkedNpcPanelPacketSender packetSender;
    private final LinkedNpcPanelDeferredNavigator deferredNavigator;
    LinkedNpcEntry[] baseLinkedNpcEntries;
    LinkedNpcEntry[] linkedNpcEntries;
    final LinkedNpcPanelCardRenderState cardRenderState;
    final LinkedNpcPanelRefreshTransaction refreshTransaction = new LinkedNpcPanelRefreshTransaction();
    UUID pendingUnlinkNpcUuid;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final CommandSelectionHotswapController hotswapController;
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
    final Consumer<String> panelSetFilterTextCallback;
    private final Runnable panelClearFiltersCallback;
    private final Consumer<String> panelSetGroupActivationCallback;
    final BiConsumer<UUID, String> panelAssignGroupCallback;
    final LinkedNpcPanelGroupAssignOverlayState groupAssignOverlay;
    volatile boolean dismissed;
    private volatile boolean navigationPending;
    volatile long pendingFilterTextApplyVersion;
    String pendingFilterTextInput;
    private final CommandSelectionLinkedPanelRuntime linkedPanelRuntime =
            new CommandSelectionLinkedPanelRuntime(this);

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
        this.config = config;
        this.hotswapController = new CommandSelectionHotswapController(config, this::resolveLanguage);
        this.linkedPanelGeneration = NEXT_LINKED_PANEL_GENERATION.incrementAndGet();
        markLinkedPanelOwner();
        this.options = CommandSelectionOptionSource.build(
                config,
                entry -> entry.isShowInRadial()
                        && (commandOptionPredicate == null || commandOptionPredicate.test(entry)),
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

    /** Configures the generic-roster active highlight preference. */
    public void configureActiveHighlight(@Nonnull CommandActiveHighlightBinding binding) {
        this.activeHighlightBinding = binding;
    }

    /** Routes background panel updates through the owning command UI host. */
    void configureHostPacketSender(@Nonnull LinkedNpcPanelPacketSender sender) {
        this.packetSender = Objects.requireNonNull(sender, "sender");
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
            hotswapController.build(commandBuilder);
            commandBuilder.set("#TameworkLinkedPanelRoot.Visible", true);
            commandBuilder.set("#TameworkLinkedPanelTitle.Text", LinkedNpcPanelPresentationSupport.title(panelModeValueSupplier, linkedNpcEntries, resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Entries", LinkedNpcPanelPresentationSupport.entries(panelGroupActivationEntriesSupplier));
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Value", LinkedNpcPanelPresentationSupport.value(panelGroupActivationValueSupplier, ""));
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", CommandSelectionPanelOptions.resolveModeDropdownEntries(resolveLanguage()));
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", LinkedNpcPanelPresentationSupport.mode(panelModeValueSupplier));
            commandBuilder.set("#TameworkLinkedPanelAutoLinkCheck.Value", LinkedNpcPanelPresentationSupport.autoLink(panelAutoLinkEnabledSupplier));
            commandBuilder.set("#TameworkLinkedPanelActiveHighlightControls.Visible",
                    activeHighlightBinding.supported());
            commandBuilder.set("#TameworkLinkedPanelActiveHighlightCheck.Value", LinkedNpcPanelPresentationSupport.activeHighlight(activeHighlightBinding.enabledSupplier()));
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
            CommandSelectionPageEventBinder.bindHotswapControls(eventBuilder);
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
        if (data.hotswapQValue != null) {
            hotswapController.apply(Slot.Q, data.hotswapQValue);
            return;
        }
        if (data.hotswapEValue != null) {
            hotswapController.apply(Slot.E, data.hotswapEValue);
            return;
        }
        if (data.hotswapRValue != null) {
            hotswapController.apply(Slot.R, data.hotswapRValue);
            return;
        }
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
        if (data.panelActiveHighlightEnabled != null && activeHighlightBinding.supported()) {
            cancelPendingFilterTextApply();
            activeHighlightBinding.setEnabledCallback().accept(data.panelActiveHighlightEnabled);
            pendingUnlinkNpcUuid = null;
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

    /** Supplies the physical flute snapshot and mutation authority after page construction. */
    public void configureHotswapAssignments(
            @Nonnull Supplier<com.hypixel.hytale.server.core.inventory.ItemStack> stackSupplier,
            @Nonnull BiConsumer<Slot, String> assignmentCallback) {
        hotswapController.configure(stackSupplier, assignmentCallback);
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
        closeForHost();
    }

    /** Releases standard renderer state when its owning host closes. */
    void closeForHost() {
        dismissed = true;
        navigationPending = false;
        clearLinkedPanelOwner();
        refreshLifecycle.close();
    }

    private void buildLinkedNpcPanel(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull UIEventBuilder eventBuilder) {
        linkedPanelRuntime.build(commandBuilder, eventBuilder);
    }

    private void dispatchRefreshPermit(LinkedPanelRefreshCoordinator.RenderPermit permit) { linkedPanelRuntime.dispatch(permit); }
    private void runRefreshOnWorldThread(LinkedPanelRefreshCoordinator.RenderPermit permit) { linkedPanelRuntime.runRefresh(permit); }
    private void scheduleDebouncedFilterTextApply() { linkedPanelRuntime.scheduleFilterApply(); }
    private void cancelPendingFilterTextApply() { linkedPanelRuntime.cancelFilterApply(); }
    private void sendCardRefreshUpdate() { linkedPanelRuntime.requestRefresh(); }
    private LinkedNpcPanelRefreshOutcome sendCardRefreshUpdate(boolean progressionEligible) {
        return linkedPanelRuntime.refresh(progressionEligible);
    }
    private long shortestVisibleCountdownRemainingMs() { return linkedPanelRuntime.shortestCountdown(); }
    /** Seeds chrome deduplication from the initial page packet. */
    private void seedRefreshValues() { linkedPanelRuntime.seedRefreshValues(); }
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

    boolean isCurrentLinkedPanelOwner() {
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
        linkedPanelRuntime.bindCard(commandBuilder, eventBuilder, index,
                entry, appendCard, featureController.presentation(entry.npcUuid()));
    }
    private void bindLinkedNpcCard(@Nonnull UICommandBuilder commandBuilder,
                                   @Nonnull UIEventBuilder eventBuilder, int index,
                                   LinkedNpcEntry entry, boolean appendCard,
                                   CommandPanelFeaturePresentation presentation) {
        linkedPanelRuntime.bindCard(commandBuilder, eventBuilder, index,
                entry, appendCard, presentation);
    }
    private void openGroupAssignOverlay(@Nonnull UUID npcUuid) { linkedPanelRuntime.openGroupAssignOverlay(npcUuid); }
    private void applyGroupAssignSelection() { linkedPanelRuntime.applyGroupAssignSelection(); }
    private LinkedNpcEntry resolveLinkedNpcEntry(@Nonnull UUID npcUuid) { return linkedPanelRuntime.resolveEntry(npcUuid); }
    private void refreshLinkedNpcEntries() { linkedPanelRuntime.refreshEntries(); }
    private void applyLocalFilter() { linkedPanelRuntime.applyLocalFilter(); }

    boolean isFilterEditPending() { return pendingFilterTextInput != null; }
    String resolveLanguage() { return playerRef != null ? playerRef.getLanguage() : null; }
    boolean isPendingUnlink(UUID npcUuid) { return npcUuid != null && npcUuid.equals(pendingUnlinkNpcUuid); }
    PlayerRef currentPlayerRef() { return playerRef; }

}
