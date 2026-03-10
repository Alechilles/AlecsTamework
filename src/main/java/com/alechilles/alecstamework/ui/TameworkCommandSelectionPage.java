package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    private static final String KEY_PANEL_SORT_VALUE = "@PanelSortValue";
    private static final String KEY_PANEL_FILTER_MODE_VALUE = "@PanelFilterModeValue";
    private static final String KEY_PANEL_FILTER_TEXT_INPUT = "@PanelFilterTextInput";
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final String LINK_COMMAND_PREFIX = "__link__:";
    private static final String UNLINK_COMMAND_PREFIX = "__unlink__:";
    private static final String TOGGLE_ACTIVE_COMMAND_PREFIX = "__active__:";
    private static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    private static final String RECALL_COMMAND_PREFIX = "__recall__:";
    private static final String SET_HOME_COMMAND_PREFIX = "__sethome__:";
    private static final String RETURN_HOME_COMMAND_PREFIX = "__returnhome__:";
    private static final String PANEL_RADIUS_DECREASE_COMMAND_ID = "__panel_radius_dec__";
    private static final String PANEL_RADIUS_INCREASE_COMMAND_ID = "__panel_radius_inc__";
    private static final String PANEL_MANAGE_GROUPS_COMMAND_ID = "__panel_manage_groups__";
    private static final String PANEL_FILTER_CLEAR_COMMAND_ID = "__panel_filter_clear__";
    private static final int MAX_COMMAND_BUTTONS = 8;
    private static final long LINKED_PANEL_REFRESH_INTERVAL_MS = 1000L;
    private static final List<DropdownEntryInfo> MODE_DROPDOWN_ENTRIES = List.of(
            new DropdownEntryInfo(LocalizableString.fromString("Linked"), "LinkedMode"),
            new DropdownEntryInfo(LocalizableString.fromString("Nearby"), "NearbyMode")
    );
    private static final List<DropdownEntryInfo> SORT_DROPDOWN_ENTRIES = List.of(
            new DropdownEntryInfo(LocalizableString.fromString("Default"), "Default"),
            new DropdownEntryInfo(LocalizableString.fromString("Name"), "Name"),
            new DropdownEntryInfo(LocalizableString.fromString("Species"), "Species"),
            new DropdownEntryInfo(LocalizableString.fromString("Group"), "Group")
    );
    private static final List<DropdownEntryInfo> FILTER_MODE_DROPDOWN_ENTRIES = List.of(
            new DropdownEntryInfo(LocalizableString.fromString("None"), "None"),
            new DropdownEntryInfo(LocalizableString.fromString("Name"), "Name"),
            new DropdownEntryInfo(LocalizableString.fromString("Species"), "Species"),
            new DropdownEntryInfo(LocalizableString.fromString("Group"), "Group")
    );

    private final CommandOption[] options;
    private final boolean requireUnlinkConfirm;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    private final Supplier<String> panelModeValueSupplier;
    private final Supplier<String> panelRadiusLabelSupplier;
    private final Supplier<String> panelSortValueSupplier;
    private final Supplier<String> panelFilterModeValueSupplier;
    private final Supplier<String> panelFilterInputValueSupplier;
    private final Supplier<String> panelFilterSummarySupplier;
    private LinkedNpcEntry[] linkedNpcEntries;
    private int renderedLinkedNpcCardCount;
    private UUID pendingUnlinkNpcUuid;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final Consumer<UUID> linkCallback;
    private final Consumer<UUID> unlinkCallback;
    private final Consumer<UUID> toggleActiveCallback;
    private final Consumer<UUID> respawnCallback;
    private final Consumer<UUID> recallCallback;
    private final Consumer<UUID> setHomeCallback;
    private final Consumer<UUID> returnHomeCallback;
    private final Consumer<String> panelSetModeCallback;
    private final Runnable panelRadiusDecreaseCallback;
    private final Runnable panelRadiusIncreaseCallback;
    private final Runnable panelManageGroupsCallback;
    private final Consumer<String> panelSetSortCallback;
    private final Consumer<String> panelSetFilterModeCallback;
    private final Consumer<String> panelSetFilterTextCallback;
    private final Runnable panelClearFiltersCallback;
    private volatile boolean refreshLoopStarted;
    private volatile boolean dismissed;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        boolean requireUnlinkConfirm,
                                        @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                        @Nonnull Supplier<String> panelModeValueSupplier,
                                        @Nonnull Supplier<String> panelRadiusLabelSupplier,
                                        @Nonnull Supplier<String> panelSortValueSupplier,
                                        @Nonnull Supplier<String> panelFilterModeValueSupplier,
                                        @Nonnull Supplier<String> panelFilterInputValueSupplier,
                                        @Nonnull Supplier<String> panelFilterSummarySupplier,
                                        @Nonnull Consumer<UUID> linkCallback,
                                        @Nonnull Consumer<UUID> unlinkCallback,
                                        @Nonnull Consumer<UUID> toggleActiveCallback,
                                        @Nonnull Consumer<UUID> respawnCallback,
                                        @Nonnull Consumer<UUID> recallCallback,
                                        @Nonnull Consumer<UUID> setHomeCallback,
                                        @Nonnull Consumer<UUID> returnHomeCallback,
                                        @Nonnull Consumer<String> panelSetModeCallback,
                                        @Nonnull Runnable panelRadiusDecreaseCallback,
                                        @Nonnull Runnable panelRadiusIncreaseCallback,
                                        @Nonnull Runnable panelManageGroupsCallback,
                                        @Nonnull Consumer<String> panelSetSortCallback,
                                        @Nonnull Consumer<String> panelSetFilterModeCallback,
                                        @Nonnull Consumer<String> panelSetFilterTextCallback,
                                        @Nonnull Runnable panelClearFiltersCallback,
                                        @Nonnull Consumer<String> selectionCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.options = buildOptions(config);
        this.requireUnlinkConfirm = requireUnlinkConfirm;
        this.linkedNpcEntriesSupplier = linkedNpcEntriesSupplier;
        this.panelModeValueSupplier = panelModeValueSupplier;
        this.panelRadiusLabelSupplier = panelRadiusLabelSupplier;
        this.panelSortValueSupplier = panelSortValueSupplier;
        this.panelFilterModeValueSupplier = panelFilterModeValueSupplier;
        this.panelFilterInputValueSupplier = panelFilterInputValueSupplier;
        this.panelFilterSummarySupplier = panelFilterSummarySupplier;
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.renderedLinkedNpcCardCount = 0;
        this.pendingUnlinkNpcUuid = null;
        this.selectedCommandId = selectedCommandId;
        this.linkCallback = linkCallback;
        this.unlinkCallback = unlinkCallback;
        this.toggleActiveCallback = toggleActiveCallback;
        this.respawnCallback = respawnCallback;
        this.recallCallback = recallCallback;
        this.setHomeCallback = setHomeCallback;
        this.returnHomeCallback = returnHomeCallback;
        this.panelSetModeCallback = panelSetModeCallback;
        this.panelRadiusDecreaseCallback = panelRadiusDecreaseCallback;
        this.panelRadiusIncreaseCallback = panelRadiusIncreaseCallback;
        this.panelManageGroupsCallback = panelManageGroupsCallback;
        this.panelSetSortCallback = panelSetSortCallback;
        this.panelSetFilterModeCallback = panelSetFilterModeCallback;
        this.panelSetFilterTextCallback = panelSetFilterTextCallback;
        this.panelClearFiltersCallback = panelClearFiltersCallback;
        this.selectionCallback = selectionCallback;
        this.refreshLoopStarted = false;
        this.dismissed = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        refreshLinkedNpcEntries();
        commandBuilder.append(UI_PATH);
        commandBuilder.append(LINKED_PANEL_UI_PATH);
        commandBuilder.set("#TameworkCommandMenuWheel.Visible", true);
        commandBuilder.set("#TameworkCommandMenuTitle.Text", "Select Command");
        commandBuilder.set("#TameworkCommandMenuSubtitle.Text", "Click a command to set it.");
        commandBuilder.set("#TameworkCommandMenuCurrent.Text", resolveCurrentLabel());
        commandBuilder.set("#TameworkLinkedPanelRoot.Visible", true);
        commandBuilder.set("#TameworkLinkedPanelTitle.Text", "Linked NPCs");
        commandBuilder.set(
                "#TameworkLinkedPanelSubtitle.Text",
                LinkedNpcPanelSubtitleService.resolveSubtitle(linkedNpcEntries, pendingUnlinkNpcUuid)
        );
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", buildModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", resolvePanelModeValue());
        commandBuilder.set("#TameworkLinkedPanelSubtitleRadiusControls.Visible", shouldShowNearbyRadiusControls());
        commandBuilder.set("#TameworkLinkedPanelRadiusValue.Text", resolvePanelRadiusLabel());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Entries", buildSortDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Value", resolvePanelSortValue());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Entries", buildFilterModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Value", resolvePanelFilterModeValue());
        boolean showFilterInputControls = shouldShowFilterInputControls();
        commandBuilder.set("#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
        commandBuilder.set("#TameworkLinkedPanelFilterInput.Value", resolvePanelFilterInputValue());

        buildCommandButtons(commandBuilder, eventBuilder);
        buildLinkedNpcPanel(commandBuilder, eventBuilder);
        bindPanelControlEvents(eventBuilder);
        bindCloseButtonEvent(eventBuilder);
        startRefreshLoop();
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull CommandSelectionEventData data) {
        if (data.panelModeValue != null) {
            if (panelSetModeCallback != null) {
                panelSetModeCallback.accept(data.panelModeValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelSortValue != null) {
            if (panelSetSortCallback != null) {
                panelSetSortCallback.accept(data.panelSortValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterModeValue != null) {
            if (panelSetFilterModeCallback != null) {
                panelSetFilterModeCallback.accept(data.panelFilterModeValue);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterTextInput != null) {
            if (panelSetFilterTextCallback != null) {
                panelSetFilterTextCallback.accept(data.panelFilterTextInput);
            }
            pendingUnlinkNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.commandId == null || data.commandId.isBlank() || CLOSE_COMMAND_ID.equals(data.commandId)) {
            pendingUnlinkNpcUuid = null;
            closePage();
            return;
        }
        if (PANEL_RADIUS_DECREASE_COMMAND_ID.equals(data.commandId)) {
            if (panelRadiusDecreaseCallback != null) {
                panelRadiusDecreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_RADIUS_INCREASE_COMMAND_ID.equals(data.commandId)) {
            if (panelRadiusIncreaseCallback != null) {
                panelRadiusIncreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_MANAGE_GROUPS_COMMAND_ID.equals(data.commandId)) {
            if (panelManageGroupsCallback != null) {
                pendingUnlinkNpcUuid = null;
                closePage();
                panelManageGroupsCallback.run();
            }
            return;
        }
        if (PANEL_FILTER_CLEAR_COMMAND_ID.equals(data.commandId)) {
            if (panelClearFiltersCallback != null) {
                panelClearFiltersCallback.run();
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(LINK_COMMAND_PREFIX)) {
            if (linkCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, LINK_COMMAND_PREFIX);
            if (npcUuid != null) {
                linkCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(UNLINK_COMMAND_PREFIX)) {
            if (unlinkCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, UNLINK_COMMAND_PREFIX);
            if (npcUuid != null) {
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
        if (data.commandId.startsWith(TOGGLE_ACTIVE_COMMAND_PREFIX)) {
            if (toggleActiveCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, TOGGLE_ACTIVE_COMMAND_PREFIX);
            if (npcUuid != null) {
                toggleActiveCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(RESPAWN_COMMAND_PREFIX)) {
            if (respawnCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, RESPAWN_COMMAND_PREFIX);
            if (npcUuid != null) {
                respawnCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(RECALL_COMMAND_PREFIX)) {
            if (recallCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, RECALL_COMMAND_PREFIX);
            if (npcUuid != null) {
                recallCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(SET_HOME_COMMAND_PREFIX)) {
            if (setHomeCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, SET_HOME_COMMAND_PREFIX);
            if (npcUuid != null) {
                setHomeCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(RETURN_HOME_COMMAND_PREFIX)) {
            if (returnHomeCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, RETURN_HOME_COMMAND_PREFIX);
            if (npcUuid != null) {
                returnHomeCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (!containsOption(data.commandId)) {
            pendingUnlinkNpcUuid = null;
            closePage();
            return;
        }
        pendingUnlinkNpcUuid = null;
        closePage();
        selectionCallback.accept(data.commandId);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true;
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
        if (dismissed) {
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

    private void runRefreshTickOnWorldThread() {
        if (dismissed) {
            return;
        }
        refreshLinkedNpcEntries();
        sendCardRefreshUpdate();
        if (!dismissed) {
            scheduleRefreshTick();
        }
    }

    private void sendCardRefreshUpdate() {
        if (dismissed) {
            return;
        }
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        commandBuilder.set(
                "#TameworkLinkedPanelSubtitle.Text",
                LinkedNpcPanelSubtitleService.resolveSubtitle(linkedNpcEntries, pendingUnlinkNpcUuid)
        );
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Entries", buildModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelModeDropdown.Value", resolvePanelModeValue());
        commandBuilder.set("#TameworkLinkedPanelSubtitleRadiusControls.Visible", shouldShowNearbyRadiusControls());
        commandBuilder.set("#TameworkLinkedPanelRadiusValue.Text", resolvePanelRadiusLabel());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Entries", buildSortDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelSortDropdown.Value", resolvePanelSortValue());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Entries", buildFilterModeDropdownEntries());
        commandBuilder.set("#TameworkLinkedPanelFilterDropdown.Value", resolvePanelFilterModeValue());
        boolean showFilterInputControls = shouldShowFilterInputControls();
        commandBuilder.set("#TameworkLinkedPanelInlineFilterTextControls.Visible", showFilterInputControls);
        commandBuilder.set("#TameworkLinkedPanelFilterInput.Value", resolvePanelFilterInputValue());
        boolean hasEntries = linkedNpcEntries.length > 0;
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commandBuilder.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        boolean structureChanged = renderedLinkedNpcCardCount != linkedNpcEntries.length;
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
                bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], false);
            }
        }
        bindCommandButtonEvents(eventBuilder);
        bindPanelControlEvents(eventBuilder);
        bindCloseButtonEvent(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void closePage() {
        dismissed = true;
        close();
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
        String entrySelector = "#TameworkLinkedPanelList[" + index + "]";
        String nameSelector = entrySelector + " #Name";
        String statusUnloadedSelector = entrySelector + " #StatusUnloaded";
        String statusConfirmSelector = entrySelector + " #StatusConfirm";
        String secondaryStatFrameSelector = entrySelector + " #FutureStatAFrame";
        String tertiaryStatFrameSelector = entrySelector + " #FutureStatBFrame";
        String futureActionBarSelector = entrySelector + " #FutureActionBar";
        String traitsButtonSelector = entrySelector + " #TraitsButton";
        String talentsButtonSelector = entrySelector + " #TalentsButton";
        String linkSelector = entrySelector + " #LinkButton";
        String removeSelector = entrySelector + " #RemoveButton";
        String activeToggleActiveSelector = entrySelector + " #ActiveToggleActiveButton";
        String activeToggleInactiveSelector = entrySelector + " #ActiveToggleInactiveButton";
        String inactiveBadgeSelector = entrySelector + " #StatusInactive";
        String groupTabSelector = entrySelector + " #GroupTab";
        String groupTabTextSelector = entrySelector + " #GroupTabText";
        String respawnSelector = entrySelector + " #RespawnButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", LINKED_PANEL_CARD_UI_PATH);
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        boolean isLinked = entry.linked();
        boolean pendingUnlink = isLinked && isPendingUnlink(entry.npcUuid());
        boolean showRespawn = isLinked && entry.dead() && entry.deadRespawnRemainingMs() == 0L && !pendingUnlink;
        boolean showRecall = isLinked && !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showSetHome = isLinked && entry.loaded() && !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showReturnHome = isLinked && !entry.dead() && !entry.captured() && entry.hasHome() && !pendingUnlink;
        boolean showLink = !isLinked;
        boolean showUnlink = isLinked;
        boolean showActiveToggleActive = isLinked && entry.active() && !pendingUnlink;
        boolean showActiveToggleInactive = isLinked && !entry.active() && !pendingUnlink;
        boolean showInactiveBadge = isLinked && !entry.active() && !pendingUnlink;
        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry));
        commandBuilder.set(statusConfirmSelector + ".Visible", pendingUnlink);
        commandBuilder.set(linkSelector + ".Visible", showLink);
        commandBuilder.set(removeSelector + ".Visible", showUnlink);
        commandBuilder.set(activeToggleActiveSelector + ".Visible", showActiveToggleActive);
        commandBuilder.set(activeToggleInactiveSelector + ".Visible", showActiveToggleInactive);
        commandBuilder.set(inactiveBadgeSelector + ".Visible", showInactiveBadge);
        LinkedNpcPanelGroupTabBinder.bind(commandBuilder, groupTabSelector, groupTabTextSelector, entry, pendingUnlink);
        LinkedNpcPanelVitalsBinder.bind(commandBuilder, entrySelector, entry);
        commandBuilder.set(secondaryStatFrameSelector + ".Visible", entry.hasFutureStatA());
        commandBuilder.set(tertiaryStatFrameSelector + ".Visible", entry.hasFutureStatB());
        commandBuilder.set(futureActionBarSelector + ".Visible", entry.hasAnyFutureAction());
        commandBuilder.set(traitsButtonSelector + ".Visible", entry.isTraitsActionVisible());
        commandBuilder.set(talentsButtonSelector + ".Visible", entry.isTalentsActionVisible());
        commandBuilder.set(respawnSelector + ".Visible", showRespawn);
        commandBuilder.set(recallSelector + ".Visible", showRecall);
        commandBuilder.set(setHomeSelector + ".Visible", showSetHome);
        commandBuilder.set(returnHomeSelector + ".Visible", showReturnHome);
        LinkedNpcTraitIndicatorBinder.bind(commandBuilder, entrySelector, entry.traitIndicators());
        if (showLink) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    linkSelector,
                    EventData.of(EVENT_COMMAND_ID, LINK_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showUnlink) {
            commandBuilder.set(removeSelector + ".Text", "");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    removeSelector,
                    EventData.of(EVENT_COMMAND_ID, UNLINK_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showActiveToggleActive) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    activeToggleActiveSelector,
                    EventData.of(EVENT_COMMAND_ID, TOGGLE_ACTIVE_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showActiveToggleInactive) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    activeToggleInactiveSelector,
                    EventData.of(EVENT_COMMAND_ID, TOGGLE_ACTIVE_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showRespawn) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    respawnSelector,
                    EventData.of(EVENT_COMMAND_ID, RESPAWN_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showRecall) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    recallSelector,
                    EventData.of(EVENT_COMMAND_ID, RECALL_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showSetHome) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    setHomeSelector,
                    EventData.of(EVENT_COMMAND_ID, SET_HOME_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
        if (showReturnHome) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    returnHomeSelector,
                    EventData.of(EVENT_COMMAND_ID, RETURN_HOME_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
    }

    private void refreshLinkedNpcEntries() {
        List<LinkedNpcEntry> entries = linkedNpcEntriesSupplier != null ? linkedNpcEntriesSupplier.get() : List.of();
        linkedNpcEntries = buildLinkedNpcEntries(entries);
        if (pendingUnlinkNpcUuid != null
                && !LinkedNpcPanelSubtitleService.containsEntry(linkedNpcEntries, pendingUnlinkNpcUuid)) {
            pendingUnlinkNpcUuid = null;
        }
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
            return "Current: none";
        }
        for (CommandOption option : options) {
            if (option != null && CommandUiIdParser.commandIdEquals(option.id, selectedCommandId)) {
                return "Current: " + option.label;
            }
        }
        return "Current: " + selectedCommandId;
    }

    private String resolvePanelModeValue() {
        if (panelModeValueSupplier == null) {
            return "LinkedMode";
        }
        String value = panelModeValueSupplier.get();
        return value == null || value.isBlank() ? "LinkedMode" : value;
    }

    private boolean shouldShowNearbyRadiusControls() {
        return "NearbyMode".equalsIgnoreCase(resolvePanelModeValue());
    }

    private String resolvePanelRadiusLabel() {
        if (panelRadiusLabelSupplier == null) {
            return "Radius: 24m";
        }
        String value = panelRadiusLabelSupplier.get();
        return value == null || value.isBlank() ? "Radius: 24m" : value;
    }

    private String resolvePanelSortValue() {
        if (panelSortValueSupplier == null) {
            return "Default";
        }
        String value = panelSortValueSupplier.get();
        return value == null || value.isBlank() ? "Default" : value;
    }

    private String resolvePanelFilterModeValue() {
        if (panelFilterModeValueSupplier == null) {
            return "None";
        }
        String value = panelFilterModeValueSupplier.get();
        return value == null || value.isBlank() ? "None" : value;
    }

    private boolean shouldShowFilterInputControls() {
        return !"None".equalsIgnoreCase(resolvePanelFilterModeValue());
    }

    private String resolvePanelFilterInputValue() {
        if (panelFilterInputValueSupplier == null) {
            return "";
        }
        String value = panelFilterInputValueSupplier.get();
        return value == null ? "" : value;
    }

    private String resolvePanelFilterSummary() {
        if (panelFilterSummarySupplier == null) {
            return "Filters: none";
        }
        String value = panelFilterSummarySupplier.get();
        return value == null || value.isBlank() ? "Filters: none" : value;
    }

    private List<DropdownEntryInfo> buildModeDropdownEntries() {
        return MODE_DROPDOWN_ENTRIES;
    }

    private List<DropdownEntryInfo> buildSortDropdownEntries() {
        return SORT_DROPDOWN_ENTRIES;
    }

    private List<DropdownEntryInfo> buildFilterModeDropdownEntries() {
        return FILTER_MODE_DROPDOWN_ENTRIES;
    }

    private static CommandOption[] buildOptions(TwCommandItemConfig config) {
        if (config == null || config.getCommandList() == null || config.getCommandList().length == 0) {
            return new CommandOption[0];
        }
        List<CommandOption> out = new ArrayList<>(MAX_COMMAND_BUTTONS);
        for (CommandEntry entry : config.getCommandList()) {
            if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            out.add(new CommandOption(entry.getId(), resolveLabel(entry)));
            if (out.size() >= MAX_COMMAND_BUTTONS) {
                break;
            }
        }
        return out.toArray(new CommandOption[0]);
    }

    private static LinkedNpcEntry[] buildLinkedNpcEntries(List<LinkedNpcEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new LinkedNpcEntry[0];
        }
        List<LinkedNpcEntry> out = new ArrayList<>(entries.size());
        for (LinkedNpcEntry entry : entries) {
            if (entry == null || entry.npcUuid() == null) {
                continue;
            }
            String name = entry.displayName();
            if (name == null || name.isBlank()) {
                name = "NPC";
            }
            int current = Math.max(0, entry.currentHealth());
            int max = Math.max(0, entry.maxHealth());
            int currentHappiness = Math.max(0, entry.currentHappiness());
            int maxHappiness = Math.max(0, entry.maxHappiness());
            int currentHunger = Math.max(0, entry.currentHunger());
            int maxHunger = Math.max(0, entry.maxHunger());
            int currentThirst = Math.max(0, entry.currentThirst());
            int maxThirst = Math.max(0, entry.maxThirst());
            out.add(new LinkedNpcEntry(
                    entry.npcUuid(),
                    name,
                    current,
                    max,
                    currentHappiness,
                    maxHappiness,
                    entry.happinessModifierBreakdown(),
                    currentHunger,
                    maxHunger,
                    currentThirst,
                    maxThirst,
                    entry.loaded(),
                    entry.hasHome(),
                    entry.dead(),
                    entry.captured(),
                    entry.deadRespawnRemainingMs(),
                    entry.futureStatA(),
                    entry.futureStatB(),
                    entry.traitIndicators(),
                    entry.isTraitsActionVisible(),
                    entry.isTraitsActionEnabled(),
                    entry.isTalentsActionVisible(),
                    entry.isTalentsActionEnabled(),
                    entry.linked(),
                    entry.active(),
                    entry.speciesId(),
                    entry.speciesLabel(),
                    entry.groupId(),
                    entry.groupName(),
                    entry.groupColorHex(),
                    entry.breedingCooldownActive(),
                    entry.breedingCooldownRemainingMs(),
                    entry.breedingCooldownRatio(),
                    entry.breedingCooldownKnown()
            ));
        }
        return out.toArray(new LinkedNpcEntry[0]);
    }

    private static String resolveLabel(CommandEntry entry) {
        if (entry.getDisplayName() != null && !entry.getDisplayName().isBlank()) {
            return entry.getDisplayName();
        }
        return entry.getId();
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
            .build();

        private String commandId;
        private String panelModeValue;
        private String panelSortValue;
        private String panelFilterModeValue;
        private String panelFilterTextInput;
    }
}
