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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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
    public static final String LINKED_PANEL_GROUP_PICKER_OPTION_ROW_UI_PATH = "TameworkLinkedNpcGroupPickerOptionRow.ui";
    private static final String EVENT_COMMAND_ID = "CommandId";
    private static final String KEY_PANEL_MODE_VALUE = "@PanelModeValue";
    private static final String KEY_PANEL_SORT_VALUE = "@PanelSortValue";
    private static final String KEY_PANEL_FILTER_MODE_VALUE = "@PanelFilterModeValue";
    private static final String KEY_PANEL_FILTER_TEXT_INPUT = "@PanelFilterTextInput";
    private static final String KEY_CARD_GROUP_VALUE = "@CardGroupValue";
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final String LINK_COMMAND_PREFIX = "__link__:";
    private static final String UNLINK_COMMAND_PREFIX = "__unlink__:";
    private static final String OPEN_GROUP_PICKER_COMMAND_PREFIX = "__opengroup__:";
    private static final String SET_GROUP_COMMAND_PREFIX = "__setgroup__:";
    private static final String TOGGLE_ACTIVE_COMMAND_PREFIX = "__active__:";
    private static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    private static final String RECALL_COMMAND_PREFIX = "__recall__:";
    private static final String SET_HOME_COMMAND_PREFIX = "__sethome__:";
    private static final String RETURN_HOME_COMMAND_PREFIX = "__returnhome__:";
    private static final String PANEL_RADIUS_DECREASE_COMMAND_ID = "__panel_radius_dec__";
    private static final String PANEL_RADIUS_INCREASE_COMMAND_ID = "__panel_radius_inc__";
    private static final String PANEL_MANAGE_GROUPS_COMMAND_ID = "__panel_manage_groups__";
    private static final String PANEL_FILTER_CLEAR_COMMAND_ID = "__panel_filter_clear__";
    private static final String GROUP_NONE_VALUE = "None";
    private static final String GROUP_NONE_COLOR = "#4B657F";
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
    private static final LinkedNpcPanelCardBinder.CardBindingConfig CARD_BINDING_CONFIG =
            new LinkedNpcPanelCardBinder.CardBindingConfig(
                    LINKED_PANEL_CARD_UI_PATH,
                    EVENT_COMMAND_ID,
                    KEY_CARD_GROUP_VALUE,
                    LINK_COMMAND_PREFIX,
                    UNLINK_COMMAND_PREFIX,
                    OPEN_GROUP_PICKER_COMMAND_PREFIX,
                    SET_GROUP_COMMAND_PREFIX,
                    TOGGLE_ACTIVE_COMMAND_PREFIX,
                    RESPAWN_COMMAND_PREFIX,
                    RECALL_COMMAND_PREFIX,
                    SET_HOME_COMMAND_PREFIX,
                    RETURN_HOME_COMMAND_PREFIX
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
    private final Supplier<List<LinkedNpcGroupPickerOption>> panelGroupPickerOptionsSupplier;
    private LinkedNpcEntry[] linkedNpcEntries;
    private int renderedLinkedNpcCardCount;
    private UUID pendingUnlinkNpcUuid;
    private UUID openGroupPickerNpcUuid;
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
    private final BiConsumer<UUID, String> panelSetGroupCallback;
    private volatile boolean refreshLoopStarted;
    private volatile boolean dismissed;
    private volatile boolean navigationPending;

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
                                        @Nonnull Supplier<List<LinkedNpcGroupPickerOption>> panelGroupPickerOptionsSupplier,
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
                                        @Nonnull BiConsumer<UUID, String> panelSetGroupCallback,
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
        this.panelGroupPickerOptionsSupplier = panelGroupPickerOptionsSupplier;
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.renderedLinkedNpcCardCount = 0;
        this.pendingUnlinkNpcUuid = null;
        this.openGroupPickerNpcUuid = null;
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
        this.panelSetGroupCallback = panelSetGroupCallback;
        this.selectionCallback = selectionCallback;
        this.refreshLoopStarted = false;
        this.dismissed = false;
        this.navigationPending = false;
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
        bindGlobalGroupPicker(commandBuilder, eventBuilder);

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
        if (navigationPending) {
            return;
        }
        if (data.panelModeValue != null) {
            if (panelSetModeCallback != null) {
                panelSetModeCallback.accept(data.panelModeValue);
            }
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelSortValue != null) {
            if (panelSetSortCallback != null) {
                panelSetSortCallback.accept(data.panelSortValue);
            }
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterModeValue != null) {
            if (panelSetFilterModeCallback != null) {
                panelSetFilterModeCallback.accept(data.panelFilterModeValue);
            }
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.panelFilterTextInput != null) {
            if (panelSetFilterTextCallback != null) {
                panelSetFilterTextCallback.accept(data.panelFilterTextInput);
            }
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
            return;
        }
        if (data.commandId == null || data.commandId.isBlank() || CLOSE_COMMAND_ID.equals(data.commandId)) {
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            closePage();
            return;
        }
        if (PANEL_RADIUS_DECREASE_COMMAND_ID.equals(data.commandId)) {
            if (panelRadiusDecreaseCallback != null) {
                panelRadiusDecreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                openGroupPickerNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_RADIUS_INCREASE_COMMAND_ID.equals(data.commandId)) {
            if (panelRadiusIncreaseCallback != null) {
                panelRadiusIncreaseCallback.run();
                pendingUnlinkNpcUuid = null;
                openGroupPickerNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (PANEL_MANAGE_GROUPS_COMMAND_ID.equals(data.commandId)) {
            if (panelManageGroupsCallback != null) {
                if (navigationPending) {
                    return;
                }
                pendingUnlinkNpcUuid = null;
                openGroupPickerNpcUuid = null;
                navigationPending = true;
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
        if (PANEL_FILTER_CLEAR_COMMAND_ID.equals(data.commandId)) {
            if (panelClearFiltersCallback != null) {
                panelClearFiltersCallback.run();
                pendingUnlinkNpcUuid = null;
                openGroupPickerNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(OPEN_GROUP_PICKER_COMMAND_PREFIX)) {
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, OPEN_GROUP_PICKER_COMMAND_PREFIX);
            if (npcUuid != null) {
                pendingUnlinkNpcUuid = null;
                if (isGroupPickerOpen(npcUuid)) {
                    openGroupPickerNpcUuid = null;
                } else {
                    openGroupPickerNpcUuid = npcUuid;
                }
                sendCardRefreshUpdate();
            }
            return;
        }
        if (data.commandId.startsWith(SET_GROUP_COMMAND_PREFIX)) {
            if (panelSetGroupCallback == null) {
                return;
            }
            UUID npcUuid = CommandUiIdParser.parseNpcUuid(data.commandId, SET_GROUP_COMMAND_PREFIX);
            if (npcUuid == null) {
                return;
            }
            String selectedGroupId = normalizeSelectedGroupId(data.cardGroupValue);
            LinkedNpcEntry entry = findLinkedNpcEntry(npcUuid);
            if (!isBlank(selectedGroupId) && entry != null && !entry.linked() && linkCallback != null) {
                linkCallback.accept(npcUuid);
                refreshLinkedNpcEntries();
                entry = findLinkedNpcEntry(npcUuid);
                if (entry == null || !entry.linked()) {
                    sendCardRefreshUpdate();
                    return;
                }
            }
            panelSetGroupCallback.accept(npcUuid, selectedGroupId);
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            refreshLinkedNpcEntries();
            sendCardRefreshUpdate();
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
                openGroupPickerNpcUuid = null;
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
                    openGroupPickerNpcUuid = null;
                    sendCardRefreshUpdate();
                    return;
                }
                unlinkCallback.accept(npcUuid);
                pendingUnlinkNpcUuid = null;
                openGroupPickerNpcUuid = null;
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
                openGroupPickerNpcUuid = null;
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
                openGroupPickerNpcUuid = null;
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
                openGroupPickerNpcUuid = null;
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
                openGroupPickerNpcUuid = null;
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
                openGroupPickerNpcUuid = null;
                refreshLinkedNpcEntries();
                sendCardRefreshUpdate();
            }
            return;
        }
        if (!containsOption(data.commandId)) {
            pendingUnlinkNpcUuid = null;
            openGroupPickerNpcUuid = null;
            closePage();
            return;
        }
        pendingUnlinkNpcUuid = null;
        openGroupPickerNpcUuid = null;
        closePage();
        selectionCallback.accept(data.commandId);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        dismissed = true;
        navigationPending = false;
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
        bindGlobalGroupPicker(commandBuilder, eventBuilder);
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
        navigationPending = false;
        close();
    }

    private void navigateAfterUiDrain(@Nonnull Runnable action) {
        dispatchNavigationAction(action);
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
        boolean pendingUnlink = entry.linked() && isPendingUnlink(entry.npcUuid());
        LinkedNpcPanelCardBinder.bind(
                commandBuilder,
                eventBuilder,
                index,
                entry,
                appendCard,
                pendingUnlink,
                CARD_BINDING_CONFIG
        );
    }

    private void bindGlobalGroupPicker(@Nonnull UICommandBuilder commandBuilder,
                                       @Nonnull UIEventBuilder eventBuilder) {
        LinkedNpcEntry targetEntry = findLinkedNpcEntry(openGroupPickerNpcUuid);
        boolean showOverlay = targetEntry != null;
        commandBuilder.set("#TameworkLinkedPanelGroupPickerOverlay.Visible", showOverlay);
        commandBuilder.clear("#TameworkLinkedPanelGroupPickerList");
        if (!showOverlay) {
            return;
        }
        commandBuilder.set("#TameworkLinkedPanelGroupPickerTitle.Text", "Set Group: " + targetEntry.displayName());
        List<LinkedNpcGroupPickerOption> options = resolveCardGroupPickerOptions(targetEntry);
        String selectedValue = resolveCardGroupSelectedValue(targetEntry);
        int rendered = 0;
        for (LinkedNpcGroupPickerOption option : options) {
            if (option == null) {
                continue;
            }
            String optionValue = normalizeGroupPickerOptionValue(option.value());
            if (optionValue == null) {
                continue;
            }
            commandBuilder.append(
                    "#TameworkLinkedPanelGroupPickerList",
                    LINKED_PANEL_GROUP_PICKER_OPTION_ROW_UI_PATH
            );
            String optionSelector = "#TameworkLinkedPanelGroupPickerList[" + rendered + "]";
            commandBuilder.set(
                    optionSelector + " #OptionColor.Background",
                    normalizeGroupPickerColor(option.colorHex())
            );
            String optionLabel = resolveGroupPickerOptionLabel(option, optionValue);
            boolean selected = selectedValue != null && selectedValue.equalsIgnoreCase(optionValue);
            commandBuilder.set(
                    optionSelector + " #OptionButton.Text",
                    selected ? "• " + optionLabel : optionLabel
            );
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    optionSelector + " #OptionButton",
                    EventData.of(EVENT_COMMAND_ID, SET_GROUP_COMMAND_PREFIX + targetEntry.npcUuid())
                            .append(KEY_CARD_GROUP_VALUE, optionValue),
                    false
            );
            rendered++;
        }
    }

    private void refreshLinkedNpcEntries() {
        List<LinkedNpcEntry> entries = linkedNpcEntriesSupplier != null ? linkedNpcEntriesSupplier.get() : List.of();
        linkedNpcEntries = LinkedNpcEntrySnapshotMapper.build(entries);
        if (pendingUnlinkNpcUuid != null
                && !LinkedNpcPanelSubtitleService.containsEntry(linkedNpcEntries, pendingUnlinkNpcUuid)) {
            pendingUnlinkNpcUuid = null;
        }
        if (openGroupPickerNpcUuid != null
                && !LinkedNpcPanelSubtitleService.containsEntry(linkedNpcEntries, openGroupPickerNpcUuid)) {
            openGroupPickerNpcUuid = null;
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

    private List<LinkedNpcGroupPickerOption> resolvePanelGroupPickerOptions() {
        List<LinkedNpcGroupPickerOption> provided = panelGroupPickerOptionsSupplier != null
                ? panelGroupPickerOptionsSupplier.get()
                : List.of();
        if (provided == null || provided.isEmpty()) {
            return List.of(new LinkedNpcGroupPickerOption(GROUP_NONE_VALUE, "None", GROUP_NONE_COLOR));
        }
        return provided;
    }

    private List<LinkedNpcGroupPickerOption> resolveCardGroupPickerOptions(LinkedNpcEntry entry) {
        return resolvePanelGroupPickerOptions();
    }

    private String resolveCardGroupSelectedValue(LinkedNpcEntry entry) {
        String selectedGroupId = entry != null ? normalizeSelectedGroupId(entry.groupId()) : null;
        if (isBlank(selectedGroupId)) {
            return GROUP_NONE_VALUE;
        }
        return selectedGroupId;
    }

    private String resolveGroupPickerOptionLabel(LinkedNpcGroupPickerOption option, String fallbackValue) {
        if (option == null) {
            return fallbackValue == null ? "Group" : fallbackValue;
        }
        String label = option.label();
        if (label == null || label.isBlank()) {
            return fallbackValue == null ? "Group" : fallbackValue;
        }
        return label.trim();
    }

    private String normalizeGroupPickerOptionValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private String normalizeGroupPickerColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return GROUP_NONE_COLOR;
        }
        String trimmed = raw.trim();
        if (!trimmed.matches("^#[0-9A-Fa-f]{6}$")) {
            return GROUP_NONE_COLOR;
        }
        return "#" + trimmed.substring(1).toUpperCase(Locale.ROOT);
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

    private static String resolveLabel(CommandEntry entry) {
        if (entry.getDisplayName() != null && !entry.getDisplayName().isBlank()) {
            return entry.getDisplayName();
        }
        return entry.getId();
    }

    private LinkedNpcEntry findLinkedNpcEntry(UUID npcUuid) {
        if (npcUuid == null || linkedNpcEntries == null || linkedNpcEntries.length == 0) {
            return null;
        }
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

    private String normalizeSelectedGroupId(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isBlank() || GROUP_NONE_VALUE.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isPendingUnlink(UUID npcUuid) {
        return npcUuid != null && pendingUnlinkNpcUuid != null && pendingUnlinkNpcUuid.equals(npcUuid);
    }

    private boolean isGroupPickerOpen(UUID npcUuid) {
        return npcUuid != null && openGroupPickerNpcUuid != null && openGroupPickerNpcUuid.equals(npcUuid);
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
            .append(
                new KeyedCodec<>(KEY_CARD_GROUP_VALUE, Codec.STRING),
                (event, value) -> event.cardGroupValue = value,
                event -> event.cardGroupValue
            )
            .add()
            .build();

        private String commandId;
        private String panelModeValue;
        private String panelSortValue;
        private String panelFilterModeValue;
        private String panelFilterTextInput;
        private String cardGroupValue;
    }
}
