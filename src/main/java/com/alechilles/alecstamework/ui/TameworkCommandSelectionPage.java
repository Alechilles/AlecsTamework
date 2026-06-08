package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
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

/**
 * Interactive command-selection page for command items.
 * Presents a radial-style set of clickable command buttons and returns the selected command id.
 */
public final class TameworkCommandSelectionPage
        extends InteractiveCustomUIPage<TameworkCommandSelectionPage.CommandSelectionEventData> {
    public static final String UI_PATH = "TameworkCommandRadialMenu.ui";
    public static final String LINKED_PANEL_UI_PATH = "TameworkLinkedNpcPanel.ui";
    public static final String LINKED_PANEL_CARD_UI_PATH = "TameworkLinkedNpcPanelCard.ui";
    private static final String EVENT_COMMAND_ID = "CommandId";
    private static final String KEY_PANEL_MODE_VALUE = "@PanelModeValue";
    private static final String KEY_PANEL_AUTO_LINK_ENABLED = "@PanelAutoLinkEnabled";
    private static final String KEY_PANEL_SORT_VALUE = "@PanelSortValue";
    private static final String KEY_PANEL_FILTER_MODE_VALUE = "@PanelFilterModeValue";
    private static final String KEY_PANEL_FILTER_TEXT_INPUT = "@PanelFilterTextInput";
    private static final String KEY_PANEL_GROUP_ACTIVE_VALUE = "@PanelGroupActiveValue";
    private static final String KEY_PANEL_GROUP_ASSIGN_VALUE = "@PanelGroupAssignValue";
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final String LINK_COMMAND_PREFIX = "__link__:";
    private static final String UNLINK_COMMAND_PREFIX = "__unlink__:";
    private static final String OPEN_GROUP_PICKER_COMMAND_PREFIX = "__opengroup__:";
    private static final String TOGGLE_ACTIVE_COMMAND_PREFIX = "__active__:";
    private static final String TOGGLE_BREEDING_COMMAND_PREFIX = "__breeding__:";
    private static final String RELEASE_COMMAND_PREFIX = "__release__:";
    private static final String CULL_COMMAND_PREFIX = "__cull__:";
    private static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    private static final String LOCATE_COMMAND_PREFIX = "__locate__:";
    private static final String RECALL_COMMAND_PREFIX = "__recall__:";
    private static final String SET_HOME_COMMAND_PREFIX = "__sethome__:";
    private static final String RETURN_HOME_COMMAND_PREFIX = "__returnhome__:";
    private static final String OPEN_TALENTS_COMMAND_PREFIX = "__talents__:";
    private static final String PANEL_RADIUS_DECREASE_COMMAND_ID = "__panel_radius_dec__";
    private static final String PANEL_RADIUS_INCREASE_COMMAND_ID = "__panel_radius_inc__";
    private static final String PANEL_MANAGE_GROUPS_COMMAND_ID = "__panel_manage_groups__";
    private static final String PANEL_FILTER_CLEAR_COMMAND_ID = "__panel_filter_clear__";
    private static final String PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID = "__panel_group_assign_apply__";
    private static final String PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID = "__panel_group_assign_cancel__";
    static final String PANEL_MODE_LINKED = "LinkedMode";
    static final String PANEL_MODE_NEARBY = "NearbyMode";
    static final String PANEL_SORT_DEFAULT = "Default";
    static final String PANEL_FILTER_NONE = "None";
    private static final int MAX_COMMAND_BUTTONS = 8;
    private static final long PANEL_FILTER_INPUT_DEBOUNCE_MS = 500L;
    private static final long LINKED_PANEL_REFRESH_INTERVAL_MS = 1000L;
    private static final long PAGE_NAVIGATION_DRAIN_DELAY_MS = 100L;
    private static final AtomicLong NEXT_LINKED_PANEL_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<UUID, Long> ACTIVE_LINKED_PANEL_GENERATIONS = new ConcurrentHashMap<>();
    private final CommandOption[] options;
    private final LinkedNpcPanelCardBinder.CardBindingConfig cardBindingConfig;
    private final boolean requireUnlinkConfirm;
    private final UUID playerUuid;
    private final long linkedPanelGeneration;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier;
    private final Supplier<String> panelModeValueSupplier;
    private final Supplier<Boolean> panelAutoLinkEnabledSupplier;
    private final Supplier<String> panelRadiusLabelSupplier;
    private final Supplier<String> panelSortValueSupplier;
    private final Supplier<String> panelFilterModeValueSupplier;
    private final Supplier<String> panelFilterInputValueSupplier;
    private final Supplier<List<DropdownEntryInfo>> panelGroupActivationEntriesSupplier;
    private final Supplier<String> panelGroupActivationValueSupplier;
    private final Supplier<List<DropdownEntryInfo>> panelGroupAssignEntriesSupplier;
    private LinkedNpcEntry[] baseLinkedNpcEntries;
    private LinkedNpcEntry[] linkedNpcEntries;
    private LinkedNpcEntry[] renderedLinkedNpcEntries;
    private int renderedLinkedNpcCardCount;
    private UUID pendingUnlinkNpcUuid;
    private UUID renderedPendingUnlinkNpcUuid;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final Consumer<UUID> linkCallback;
    private final Consumer<UUID> unlinkCallback;
    private final Consumer<UUID> toggleActiveCallback;
    private final Consumer<UUID> toggleBreedingCallback;
    private final Consumer<UUID> releaseCallback;
    private final Consumer<UUID> cullCallback;
    private final Consumer<UUID> respawnCallback;
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
    private volatile boolean refreshLoopStarted;
    private volatile boolean dismissed;
    private volatile boolean navigationPending;
    private volatile long pendingFilterTextApplyVersion;
    private String pendingFilterTextInput;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        boolean requireUnlinkConfirm,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                         @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcBaseEntriesSupplier,
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
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.playerUuid = playerRef.getUuid();
        this.linkedPanelGeneration = NEXT_LINKED_PANEL_GENERATION.incrementAndGet();
        markLinkedPanelOwner();
        this.options = buildOptions(config, commandOptionPredicate, resolveLanguage());
        this.cardBindingConfig = buildCardBindingConfig(recallActionEnabled);
        this.requireUnlinkConfirm = requireUnlinkConfirm;
        this.linkedNpcEntriesSupplier = linkedNpcEntriesSupplier;
        this.linkedNpcBaseEntriesSupplier = linkedNpcBaseEntriesSupplier;
        this.panelModeValueSupplier = panelModeValueSupplier;
        this.panelAutoLinkEnabledSupplier = panelAutoLinkEnabledSupplier;
        this.panelRadiusLabelSupplier = panelRadiusLabelSupplier;
        this.panelSortValueSupplier = panelSortValueSupplier;
        this.panelFilterModeValueSupplier = panelFilterModeValueSupplier;
        this.panelFilterInputValueSupplier = panelFilterInputValueSupplier;
        this.panelGroupActivationEntriesSupplier = panelGroupActivationEntriesSupplier;
        this.panelGroupActivationValueSupplier = panelGroupActivationValueSupplier;
        this.panelGroupAssignEntriesSupplier = panelGroupAssignEntriesSupplier;
        this.baseLinkedNpcEntries = new LinkedNpcEntry[0];
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.renderedLinkedNpcEntries = new LinkedNpcEntry[0];
        this.renderedLinkedNpcCardCount = 0;
        this.pendingUnlinkNpcUuid = null;
        this.renderedPendingUnlinkNpcUuid = null;
        this.selectedCommandId = selectedCommandId;
        this.linkCallback = linkCallback;
        this.unlinkCallback = unlinkCallback;
        this.toggleActiveCallback = toggleActiveCallback;
        this.toggleBreedingCallback = toggleBreedingCallback;
        this.releaseCallback = releaseCallback;
        this.cullCallback = cullCallback;
        this.respawnCallback = respawnCallback;
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
        this.refreshLoopStarted = false;
        this.dismissed = false;
        this.navigationPending = false;
        this.pendingFilterTextApplyVersion = 0L;
        this.pendingFilterTextInput = null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        try {
            refreshLinkedNpcEntries();
            commandBuilder.append(UI_PATH);
            commandBuilder.append(LINKED_PANEL_UI_PATH);
            commandBuilder.set("#TameworkCommandMenuWheel.Visible", true);
            commandBuilder.set("#TameworkCommandMenuTitle.Text", LocalizedText.resolve(playerRef, "tamework.ui.commandMenu.title"));
            commandBuilder.set("#TameworkCommandMenuSubtitle.Text", LocalizedText.resolve(playerRef, "tamework.ui.commandMenu.subtitle"));
            commandBuilder.set("#TameworkCommandMenuCurrent.Text", resolveCurrentLabel());
            commandBuilder.set("#TameworkLinkedPanelRoot.Visible", true);
            commandBuilder.set("#TameworkLinkedPanelTitle.Text", resolvePanelTitleText());
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Entries", resolveGroupActivationDropdownEntries());
            commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Value", resolveGroupActivationValue());
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", resolveModeDropdownEntries());
            commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", resolvePanelModeValue());
            commandBuilder.set("#TameworkLinkedPanelAutoLinkCheck.Value", resolvePanelAutoLinkEnabled());
            commandBuilder.set("#TameworkLinkedPanelSubtitleRadiusControls.Visible", shouldShowNearbyRadiusControls());
            commandBuilder.set("#TameworkLinkedPanelRadiusValue.Text", resolvePanelRadiusLabel());
            commandBuilder.set("#TameworkLinkedPanelSortDropdown.Entries", resolveSortDropdownEntries());
            commandBuilder.set("#TameworkLinkedPanelSortDropdown.Value", resolvePanelSortValue());
            commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Entries", resolveFilterModeDropdownEntries());
            commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Value", resolvePanelFilterModeValue());
            boolean showFilterInputControls = shouldShowFilterInputControls();
            commandBuilder.set("#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
            commandBuilder.set("#TameworkLinkedPanelFilterInput.Value", resolvePanelFilterInputValue());
            applyGroupAssignOverlayState(commandBuilder);

            buildCommandButtons(commandBuilder, eventBuilder);
            buildLinkedNpcPanel(commandBuilder, eventBuilder);
            bindPanelControlEvents(eventBuilder);
            bindCloseButtonEvent(eventBuilder);
            startRefreshLoop();
        } catch (Throwable throwable) {
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
        if (dismissed && !navigationPending) {
            return;
        }
        if (navigationPending) {
            return;
        }
        if (!isCurrentLinkedPanelOwner()) {
            return;
        }
        if (data.panelGroupAssignValue != null) {
            groupAssignOverlay.updateSelectedValue(data.panelGroupAssignValue);
        }
        String commandId = data.commandId == null ? "" : data.commandId.trim();
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
                sendCardRefreshUpdate();
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
        if (commandId.startsWith(OPEN_TALENTS_COMMAND_PREFIX)) {
            if (openTalentsCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, OPEN_TALENTS_COMMAND_PREFIX);
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
        if (!containsOption(commandId)) {
            pendingUnlinkNpcUuid = null;
            closePage();
            return;
        }
        pendingUnlinkNpcUuid = null;
        closePage();
        selectionCallback.accept(commandId);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true;
        navigationPending = false;
        clearLinkedPanelOwner();
    }

    private void buildCommandButtons(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull UIEventBuilder eventBuilder) {
        for (int i = 0; i < MAX_COMMAND_BUTTONS; i++) {
            String selector = "#CommandButton" + i;
            String labelSelector = "#CommandLabel" + i;
            if (i >= options.length) {
                commandBuilder.set(selector + ".Visible", false);
                commandBuilder.set(labelSelector + ".Visible", false);
                continue;
            }
            CommandOption option = options[i];
            commandBuilder.set(selector + ".Visible", true);
            commandBuilder.set(selector + ".Text", "");
            commandBuilder.set(labelSelector + ".Visible", true);
            commandBuilder.set(labelSelector + ".Text", option.label);
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector,
                    EventData.of(EVENT_COMMAND_ID, option.id),
                    false
            );
        }
    }

    private void buildLinkedNpcPanel(@Nonnull UICommandBuilder commandBuilder,
                                     @Nonnull UIEventBuilder eventBuilder) {
        renderedLinkedNpcCardCount = linkedNpcEntries.length;
        commandBuilder.clear("#TameworkLinkedPanelList");
        boolean hasEntries = linkedNpcEntries.length > 0;
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commandBuilder.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        if (!hasEntries) {
            return;
        }
        for (int i = 0; i < linkedNpcEntries.length; i++) {
            bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], true);
        }
    }

    private void startRefreshLoop() {
        if (refreshLoopStarted) {
            return;
        }
        refreshLoopStarted = true;
        scheduleRefreshTick();
    }

    private void scheduleRefreshTick() {
        CompletableFuture.runAsync(
                this::dispatchRefreshTick,
                CompletableFuture.delayedExecutor(LINKED_PANEL_REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void dispatchRefreshTick() {
        if (dismissed || !isCurrentLinkedPanelOwner()) {
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
        world.execute(this::runRefreshTickOnWorldThread);
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
        world.execute(() -> runDebouncedFilterTextApplyOnWorldThread(version));
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
        applyLocalFilterToLinkedNpcEntries();
        sendCardRefreshUpdate();
    }

    private void cancelPendingFilterTextApply() {
        pendingFilterTextApplyVersion++;
        pendingFilterTextInput = null;
    }

    private void runRefreshTickOnWorldThread() {
        if (dismissed || !isCurrentLinkedPanelOwner()) {
            return;
        }
        if (isFilterEditPending()) {
            if (!dismissed) {
                scheduleRefreshTick();
            }
            return;
        }
        refreshLinkedNpcEntries();
        sendCardRefreshUpdate();
        if (!dismissed) {
            scheduleRefreshTick();
        }
    }

    private void sendCardRefreshUpdate() {
        if (dismissed || !isCurrentLinkedPanelOwner()) {
            return;
        }
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        commandBuilder.set("#TameworkLinkedPanelTitle.Text", resolvePanelTitleText());
        commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Entries", resolveGroupActivationDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelGroupSelectorDropdown.Value", resolveGroupActivationValue());
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", resolveModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", resolvePanelModeValue());
        commandBuilder.set("#TameworkLinkedPanelAutoLinkCheck.Value", resolvePanelAutoLinkEnabled());
        commandBuilder.set("#TameworkLinkedPanelSubtitleRadiusControls.Visible", shouldShowNearbyRadiusControls());
        commandBuilder.set("#TameworkLinkedPanelRadiusValue.Text", resolvePanelRadiusLabel());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Entries", resolveSortDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Value", resolvePanelSortValue());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Entries", resolveFilterModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Value", resolvePanelFilterModeValue());
        boolean showFilterInputControls = shouldShowFilterInputControls();
        commandBuilder.set("#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
        if (!isFilterEditPending()) {
            commandBuilder.set("#TameworkLinkedPanelFilterInput.Value", resolveAppliedPanelFilterInputValue());
        }
        applyGroupAssignOverlayState(commandBuilder);
        boolean hasEntries = linkedNpcEntries.length > 0;
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commandBuilder.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        boolean structureChanged = renderedLinkedNpcCardCount != linkedNpcEntries.length
                || renderedLinkedNpcEntries.length != linkedNpcEntries.length;
        boolean pendingUnlinkChanged = !java.util.Objects.equals(renderedPendingUnlinkNpcUuid, pendingUnlinkNpcUuid);
        if (structureChanged) {
            commandBuilder.clear("#TameworkLinkedPanelList");
            renderedLinkedNpcCardCount = linkedNpcEntries.length;
            if (hasEntries) {
                for (int i = 0; i < linkedNpcEntries.length; i++) {
                    bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], true);
                }
            }
        } else if (hasEntries) {
            for (int i = 0; i < linkedNpcEntries.length; i++) {
                boolean wasPendingUnlink = isPendingUnlink(renderedLinkedNpcEntries, renderedPendingUnlinkNpcUuid, i);
                boolean isPendingUnlink = isPendingUnlink(linkedNpcEntries, pendingUnlinkNpcUuid, i);
                if (!java.util.Objects.equals(linkedNpcEntries[i], renderedLinkedNpcEntries[i])
                        || wasPendingUnlink != isPendingUnlink
                        || pendingUnlinkChanged) {
                    bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], false);
                }
            }
        }
        renderedLinkedNpcEntries = linkedNpcEntries.clone();
        renderedPendingUnlinkNpcUuid = pendingUnlinkNpcUuid;
        bindCommandButtonEvents(eventBuilder);
        bindPanelControlEvents(eventBuilder);
        bindCloseButtonEvent(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private boolean isPendingUnlink(LinkedNpcEntry[] entries, UUID pendingUuid, int index) {
        if (entries == null || pendingUuid == null || index < 0 || index >= entries.length) {
            return false;
        }
        LinkedNpcEntry entry = entries[index];
        return entry != null && pendingUuid.equals(entry.npcUuid());
    }

    private void closePage() {
        dismissed = true;
        navigationPending = false;
        clearLinkedPanelOwner();
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
        CompletableFuture.runAsync(
                () -> dispatchNavigationAction(action),
                CompletableFuture.delayedExecutor(PAGE_NAVIGATION_DRAIN_DELAY_MS, TimeUnit.MILLISECONDS)
        );
    }

    private void dispatchNavigationAction(@Nonnull Runnable action) {
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
        world.execute(() -> {
            Ref<EntityStore> activeRef = playerRef.getReference();
            if (activeRef == null || !activeRef.isValid()) {
                return;
            }
            action.run();
        });
    }

    private void bindCommandButtonEvents(@Nonnull UIEventBuilder eventBuilder) {
        for (int i = 0; i < MAX_COMMAND_BUTTONS; i++) {
            if (i >= options.length) {
                continue;
            }
            CommandOption option = options[i];
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#CommandButton" + i,
                    EventData.of(EVENT_COMMAND_ID, option.id),
                    false
            );
        }
    }

    private void bindPanelControlEvents(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelAutoLinkCheck",
                EventData.of(KEY_PANEL_AUTO_LINK_ENABLED, "#TameworkLinkedPanelAutoLinkCheck.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelModeDropdown",
                EventData.of(KEY_PANEL_MODE_VALUE, "#TameworkLinkedPanelModeDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelRadiusDec",
                EventData.of(EVENT_COMMAND_ID, PANEL_RADIUS_DECREASE_COMMAND_ID),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelRadiusInc",
                EventData.of(EVENT_COMMAND_ID, PANEL_RADIUS_INCREASE_COMMAND_ID),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelManageGroupsButton",
                EventData.of(EVENT_COMMAND_ID, PANEL_MANAGE_GROUPS_COMMAND_ID),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelSortDropdown",
                EventData.of(KEY_PANEL_SORT_VALUE, "#TameworkLinkedPanelSortDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelFilterDropdown",
                EventData.of(KEY_PANEL_FILTER_MODE_VALUE, "#TameworkLinkedPanelFilterDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelFilterInput",
                EventData.of(KEY_PANEL_FILTER_TEXT_INPUT, "#TameworkLinkedPanelFilterInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelGroupSelectorDropdown",
                EventData.of(KEY_PANEL_GROUP_ACTIVE_VALUE, "#TameworkLinkedPanelGroupSelectorDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkLinkedPanelGroupAssignDropdown",
                EventData.of(KEY_PANEL_GROUP_ASSIGN_VALUE, "#TameworkLinkedPanelGroupAssignDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelGroupAssignCancelButton",
                EventData.of(EVENT_COMMAND_ID, PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkLinkedPanelGroupAssignApplyButton",
                EventData.of(EVENT_COMMAND_ID, PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID)
                        .append(KEY_PANEL_GROUP_ASSIGN_VALUE, "#TameworkLinkedPanelGroupAssignDropdown.Value"),
                false
        );
    }

    private void bindCloseButtonEvent(@Nonnull UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CommandMenuCloseButton",
                EventData.of(EVENT_COMMAND_ID, CLOSE_COMMAND_ID),
                false
        );
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
                resolveLanguage()
        );
    }

    private void applyGroupAssignOverlayState(@Nonnull UICommandBuilder commandBuilder) {
        groupAssignOverlay.applyTo(commandBuilder, resolveLanguage());
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
        List<LinkedNpcEntry> entries = linkedNpcBaseEntriesSupplier != null
                ? linkedNpcBaseEntriesSupplier.get()
                : linkedNpcEntriesSupplier != null
                ? linkedNpcEntriesSupplier.get()
                : List.of();
        baseLinkedNpcEntries = LinkedNpcEntrySnapshotMapper.build(
                entries,
                LocalizedText.resolve(resolveLanguage(), "tamework.ui.linkedPanel.subtitle.defaultNpcName")
        );
        applyLocalFilterToLinkedNpcEntries();
        if (pendingUnlinkNpcUuid != null && resolveLinkedNpcEntry(pendingUnlinkNpcUuid) == null) {
            pendingUnlinkNpcUuid = null;
        }
    }

    private void applyLocalFilterToLinkedNpcEntries() {
        LinkedNpcEntry[] source = baseLinkedNpcEntries != null ? baseLinkedNpcEntries : new LinkedNpcEntry[0];
        if (source.length == 0) {
            linkedNpcEntries = source;
            return;
        }
        String filterMode = resolvePanelFilterModeValue();
        String filterText = resolveAppliedPanelFilterInputValue();
        if (filterMode == null || filterMode.isBlank() || PANEL_FILTER_NONE.equalsIgnoreCase(filterMode)
                || filterText == null || filterText.isBlank()) {
            linkedNpcEntries = source;
            return;
        }
        String normalizedFilter = filterText.trim().toLowerCase(Locale.ROOT);
        ArrayList<LinkedNpcEntry> filtered = new ArrayList<>(source.length);
        for (LinkedNpcEntry entry : source) {
            if (entry == null) {
                continue;
            }
            if (matchesLocalFilter(entry, filterMode, normalizedFilter)) {
                filtered.add(entry);
            }
        }
        linkedNpcEntries = filtered.toArray(new LinkedNpcEntry[0]);
    }

    private boolean matchesLocalFilter(@Nonnull LinkedNpcEntry entry,
                                       @Nonnull String filterMode,
                                       @Nonnull String normalizedFilter) {
        String candidate = switch (filterMode.trim().toLowerCase(Locale.ROOT)) {
            case "name" -> entry.displayName();
            case "species" -> firstNonBlank(entry.speciesLabel(), entry.speciesId());
            case "group" -> firstNonBlank(entry.groupName(), entry.groupId());
            default -> null;
        };
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return candidate.toLowerCase(Locale.ROOT).contains(normalizedFilter);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private boolean containsOption(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return false;
        }
        for (CommandOption option : options) {
            if (option != null && CommandUiIdParser.commandIdEquals(option.id, commandId)) {
                return true;
            }
        }
        return false;
    }

    private String resolveCurrentLabel() {
        if (selectedCommandId == null || selectedCommandId.isBlank()) {
            return LocalizedText.resolve(resolveLanguage(), "tamework.ui.commandMenu.current.none");
        }
        for (CommandOption option : options) {
            if (option != null && CommandUiIdParser.commandIdEquals(option.id, selectedCommandId)) {
                return LocalizedText.format(resolveLanguage(), "tamework.ui.commandMenu.current.value", option.label);
            }
        }
        return LocalizedText.format(resolveLanguage(), "tamework.ui.commandMenu.current.value", selectedCommandId);
    }

    private String resolvePanelModeValue() {
        if (panelModeValueSupplier == null) {
            return PANEL_MODE_LINKED;
        }
        String value = panelModeValueSupplier.get();
        return value == null || value.isBlank() ? PANEL_MODE_LINKED : value;
    }

    private boolean resolvePanelAutoLinkEnabled() {
        if (panelAutoLinkEnabledSupplier == null) {
            return true;
        }
        Boolean value = panelAutoLinkEnabledSupplier.get();
        return value == null || value;
    }

    private String resolvePanelTitleText() {
        String title = shouldShowNearbyRadiusControls()
                ? LocalizedText.resolve(resolveLanguage(), "tamework.ui.linkedPanel.title.nearby")
                : LocalizedText.resolve(resolveLanguage(), "tamework.ui.linkedPanel.title.linked");
        int count = linkedNpcEntries != null ? linkedNpcEntries.length : 0;
        return title + " (" + count + ")";
    }

    private boolean shouldShowNearbyRadiusControls() {
        return PANEL_MODE_NEARBY.equalsIgnoreCase(resolvePanelModeValue());
    }

    private String resolvePanelRadiusLabel() {
        if (panelRadiusLabelSupplier == null) {
            return LocalizedText.format(resolveLanguage(), "tamework.ui.linkedPanel.radius.value", 24);
        }
        String value = panelRadiusLabelSupplier.get();
        return value == null || value.isBlank()
                ? LocalizedText.format(resolveLanguage(), "tamework.ui.linkedPanel.radius.value", 24)
                : value;
    }

    private String resolvePanelSortValue() {
        if (panelSortValueSupplier == null) {
            return PANEL_SORT_DEFAULT;
        }
        String value = panelSortValueSupplier.get();
        return value == null || value.isBlank() ? PANEL_SORT_DEFAULT : value;
    }

    private String resolvePanelFilterModeValue() {
        if (panelFilterModeValueSupplier == null) {
            return PANEL_FILTER_NONE;
        }
        String value = panelFilterModeValueSupplier.get();
        return value == null || value.isBlank() ? PANEL_FILTER_NONE : value;
    }

    private boolean shouldShowFilterInputControls() {
        return !PANEL_FILTER_NONE.equalsIgnoreCase(resolvePanelFilterModeValue());
    }

    private String resolvePanelFilterInputValue() {
        if (pendingFilterTextInput != null) {
            return pendingFilterTextInput;
        }
        return resolveAppliedPanelFilterInputValue();
    }

    private boolean isFilterEditPending() {
        return pendingFilterTextInput != null;
    }

    private String resolveAppliedPanelFilterInputValue() {
        if (panelFilterInputValueSupplier == null) {
            return "";
        }
        String value = panelFilterInputValueSupplier.get();
        return value == null ? "" : value;
    }

    private List<DropdownEntryInfo> resolveGroupActivationDropdownEntries() {
        List<DropdownEntryInfo> resolved = panelGroupActivationEntriesSupplier != null
                ? panelGroupActivationEntriesSupplier.get()
                : List.of();
        return resolved != null ? resolved : List.of();
    }

    private String resolveGroupActivationValue() {
        if (panelGroupActivationValueSupplier == null) {
            return "";
        }
        String value = panelGroupActivationValueSupplier.get();
        return value != null ? value : "";
    }

    private String resolveLanguage() {
        return playerRef != null ? playerRef.getLanguage() : null;
    }

    private List<DropdownEntryInfo> resolveModeDropdownEntries() {
        return CommandSelectionPanelOptions.resolveModeDropdownEntries(resolveLanguage());
    }

    private List<DropdownEntryInfo> resolveSortDropdownEntries() {
        return CommandSelectionPanelOptions.resolveSortDropdownEntries(resolveLanguage());
    }

    private List<DropdownEntryInfo> resolveFilterModeDropdownEntries() {
        return CommandSelectionPanelOptions.resolveFilterModeDropdownEntries(resolveLanguage());
    }

    private static LinkedNpcPanelCardBinder.CardBindingConfig buildCardBindingConfig(boolean recallActionEnabled) {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                LINKED_PANEL_CARD_UI_PATH,
                EVENT_COMMAND_ID,
                LINK_COMMAND_PREFIX,
                UNLINK_COMMAND_PREFIX,
                OPEN_GROUP_PICKER_COMMAND_PREFIX,
                TOGGLE_ACTIVE_COMMAND_PREFIX,
                TOGGLE_BREEDING_COMMAND_PREFIX,
                RELEASE_COMMAND_PREFIX,
                CULL_COMMAND_PREFIX,
                RESPAWN_COMMAND_PREFIX,
                LOCATE_COMMAND_PREFIX,
                RECALL_COMMAND_PREFIX,
                SET_HOME_COMMAND_PREFIX,
                RETURN_HOME_COMMAND_PREFIX,
                OPEN_TALENTS_COMMAND_PREFIX,
                recallActionEnabled
        );
    }

    private static CommandOption[] buildOptions(TwCommandItemConfig config,
                                                Predicate<CommandEntry> predicate,
                                                String language) {
        if (config == null || config.getCommandList() == null || config.getCommandList().length == 0) {
            return new CommandOption[0];
        }
        List<CommandOption> out = new ArrayList<>(MAX_COMMAND_BUTTONS);
        for (CommandEntry entry : config.getCommandList()) {
            if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            if (predicate != null && !predicate.test(entry)) {
                continue;
            }
            out.add(new CommandOption(entry.getId(), resolveLabel(entry, language)));
            if (out.size() >= MAX_COMMAND_BUTTONS) {
                break;
            }
        }
        return out.toArray(new CommandOption[0]);
    }

    private static String resolveLabel(CommandEntry entry, String language) {
        return LocalizedText.resolveConfigValue(language, entry.getDisplayName(), entry.getId());
    }

    private boolean isPendingUnlink(UUID npcUuid) {
        return npcUuid != null && pendingUnlinkNpcUuid != null && pendingUnlinkNpcUuid.equals(npcUuid);
    }

    private record CommandOption(String id, String label) { }

    /** Event payload emitted by command-button clicks in the command selection page. */
    public static final class CommandSelectionEventData {
        public static final BuilderCodec<CommandSelectionEventData> CODEC = BuilderCodec.builder(
                CommandSelectionEventData.class,
                CommandSelectionEventData::new
        )
            .<String>append(
                new KeyedCodec<>(EVENT_COMMAND_ID, Codec.STRING),
                (event, value) -> event.commandId = value,
                event -> event.commandId
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_MODE_VALUE, Codec.STRING),
                (event, value) -> event.panelModeValue = value,
                event -> event.panelModeValue
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_AUTO_LINK_ENABLED, Codec.BOOLEAN),
                (event, value) -> event.panelAutoLinkEnabled = value,
                event -> event.panelAutoLinkEnabled
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_SORT_VALUE, Codec.STRING),
                (event, value) -> event.panelSortValue = value,
                event -> event.panelSortValue
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_FILTER_MODE_VALUE, Codec.STRING),
                (event, value) -> event.panelFilterModeValue = value,
                event -> event.panelFilterModeValue
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_FILTER_TEXT_INPUT, Codec.STRING),
                (event, value) -> event.panelFilterTextInput = value,
                event -> event.panelFilterTextInput
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_GROUP_ACTIVE_VALUE, Codec.STRING),
                (event, value) -> event.panelGroupActiveValue = value,
                event -> event.panelGroupActiveValue
            )
            .add()
            .append(
                new KeyedCodec<>(KEY_PANEL_GROUP_ASSIGN_VALUE, Codec.STRING),
                (event, value) -> event.panelGroupAssignValue = value,
                event -> event.panelGroupAssignValue
            )
            .add()
            .build();

        private String commandId;
        private String panelModeValue;
        private Boolean panelAutoLinkEnabled;
        private String panelSortValue;
        private String panelFilterModeValue;
        private String panelFilterTextInput;
        private String panelGroupActiveValue;
        private String panelGroupAssignValue;
    }
}
