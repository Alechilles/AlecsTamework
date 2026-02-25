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
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final String UNLINK_COMMAND_PREFIX = "__unlink__:";
    private static final String RESPAWN_COMMAND_PREFIX = "__respawn__:";
    private static final String RECALL_COMMAND_PREFIX = "__recall__:";
    private static final String SET_HOME_COMMAND_PREFIX = "__sethome__:";
    private static final String RETURN_HOME_COMMAND_PREFIX = "__returnhome__:";
    private static final int MAX_COMMAND_BUTTONS = 8;
    private static final long LINKED_PANEL_REFRESH_INTERVAL_MS = 1000L;

    private final CommandOption[] options;
    private final boolean requireUnlinkConfirm;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    private LinkedNpcEntry[] linkedNpcEntries;
    private int renderedLinkedNpcCardCount;
    private UUID pendingUnlinkNpcUuid;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final Consumer<UUID> unlinkCallback;
    private final Consumer<UUID> respawnCallback;
    private final Consumer<UUID> recallCallback;
    private final Consumer<UUID> setHomeCallback;
    private final Consumer<UUID> returnHomeCallback;
    private volatile boolean refreshLoopStarted;
    private volatile boolean dismissed;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        boolean requireUnlinkConfirm,
                                        @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                        @Nonnull Consumer<UUID> unlinkCallback,
                                        @Nonnull Consumer<UUID> respawnCallback,
                                        @Nonnull Consumer<UUID> recallCallback,
                                        @Nonnull Consumer<UUID> setHomeCallback,
                                        @Nonnull Consumer<UUID> returnHomeCallback,
                                        @Nonnull Consumer<String> selectionCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.options = buildOptions(config);
        this.requireUnlinkConfirm = requireUnlinkConfirm;
        this.linkedNpcEntriesSupplier = linkedNpcEntriesSupplier;
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.renderedLinkedNpcCardCount = 0;
        this.pendingUnlinkNpcUuid = null;
        this.selectedCommandId = selectedCommandId;
        this.unlinkCallback = unlinkCallback;
        this.respawnCallback = respawnCallback;
        this.recallCallback = recallCallback;
        this.setHomeCallback = setHomeCallback;
        this.returnHomeCallback = returnHomeCallback;
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
        commandBuilder.set("#TameworkLinkedPanelTitle.Text", "Linked Companions");
        commandBuilder.set(
                "#TameworkLinkedPanelSubtitle.Text",
                LinkedNpcPanelSubtitleService.resolveSubtitle(linkedNpcEntries, pendingUnlinkNpcUuid)
        );

        buildCommandButtons(commandBuilder, eventBuilder);
        buildLinkedNpcPanel(commandBuilder, eventBuilder);
        bindCloseButtonEvent(eventBuilder);
        startRefreshLoop();
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull CommandSelectionEventData data) {
        if (data.commandId == null || data.commandId.isBlank() || CLOSE_COMMAND_ID.equals(data.commandId)) {
            pendingUnlinkNpcUuid = null;
            close();
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
            close();
            return;
        }
        pendingUnlinkNpcUuid = null;
        close();
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
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        commandBuilder.set(
                "#TameworkLinkedPanelSubtitle.Text",
                LinkedNpcPanelSubtitleService.resolveSubtitle(linkedNpcEntries, pendingUnlinkNpcUuid)
        );
        renderedLinkedNpcCardCount = linkedNpcEntries.length;
        commandBuilder.clear("#TameworkLinkedPanelList");
        boolean hasEntries = linkedNpcEntries.length > 0;
        commandBuilder.set("#TameworkLinkedPanelEmptyState.Visible", !hasEntries);
        commandBuilder.set("#TameworkLinkedPanelListViewport.Visible", hasEntries);
        if (hasEntries) {
            for (int i = 0; i < linkedNpcEntries.length; i++) {
                bindLinkedNpcCard(commandBuilder, eventBuilder, i, linkedNpcEntries[i], true);
            }
        }
        bindCommandButtonEvents(eventBuilder);
        bindCloseButtonEvent(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
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
        String removeSelector = entrySelector + " #RemoveButton";
        String respawnSelector = entrySelector + " #RespawnButton";
        String recallSelector = entrySelector + " #RecallButton";
        String setHomeSelector = entrySelector + " #SetHomeButton";
        String returnHomeSelector = entrySelector + " #ReturnHomeButton";

        if (appendCard) {
            commandBuilder.append("#TameworkLinkedPanelList", LINKED_PANEL_CARD_UI_PATH);
        }
        commandBuilder.set(nameSelector + ".Text", entry.displayName());
        boolean pendingUnlink = isPendingUnlink(entry.npcUuid());
        boolean showRespawn = entry.dead() && entry.deadRespawnRemainingMs() == 0L && !pendingUnlink;
        boolean showRecall = !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showSetHome = entry.loaded() && !entry.dead() && !entry.captured() && !pendingUnlink;
        boolean showReturnHome = !entry.dead() && !entry.captured() && entry.hasHome() && !pendingUnlink;
        commandBuilder.set(statusUnloadedSelector + ".Visible", !entry.loaded() && !pendingUnlink && !showRespawn);
        commandBuilder.set(statusUnloadedSelector + ".Text", LinkedNpcPanelStatusTextService.resolveAvailabilityStatusText(entry));
        commandBuilder.set(statusConfirmSelector + ".Visible", pendingUnlink);
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
        commandBuilder.set(removeSelector + ".Text", "");
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                removeSelector,
                EventData.of(EVENT_COMMAND_ID, UNLINK_COMMAND_PREFIX + entry.npcUuid()),
                false
        );
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
            if (entry == null || entry.npcUuid == null) {
                continue;
            }
            String name = entry.displayName;
            if (name == null || name.isBlank()) {
                name = "NPC";
            }
            int current = Math.max(0, entry.currentHealth);
            int max = Math.max(0, entry.maxHealth);
            int currentHappiness = Math.max(0, entry.currentHappiness);
            int maxHappiness = Math.max(0, entry.maxHappiness);
            out.add(new LinkedNpcEntry(
                    entry.npcUuid,
                    name,
                    current,
                    max,
                    currentHappiness,
                    maxHappiness,
                    entry.loaded,
                    entry.hasHome,
                    entry.dead,
                    entry.captured,
                    entry.deadRespawnRemainingMs,
                    entry.futureStatA,
                    entry.futureStatB,
                    entry.traitIndicators,
                    entry.traitsActionVisible,
                    entry.traitsActionEnabled,
                    entry.talentsActionVisible,
                    entry.talentsActionEnabled
            ));
        }
        out.sort(
                Comparator.comparing(LinkedNpcEntry::loaded).reversed()
                        .thenComparing(LinkedNpcEntry::dead).reversed()
                        .thenComparing(LinkedNpcEntry::captured).reversed()
                        .thenComparing(LinkedNpcEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.npcUuid.toString())
        );
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

    /** View model for one linked NPC row in the command radial side panel. */
    public static final class LinkedNpcEntry {
        private final UUID npcUuid;
        private final String displayName;
        private final int currentHealth;
        private final int maxHealth;
        private final int currentHappiness;
        private final int maxHappiness;
        private final boolean loaded;
        private final boolean dead;
        private final boolean captured;
        private final boolean hasHome;
        private final long deadRespawnRemainingMs;
        private final FutureStat futureStatA;
        private final FutureStat futureStatB;
        private final LinkedNpcTraitIndicator[] traitIndicators;
        private final boolean traitsActionVisible;
        private final boolean traitsActionEnabled;
        private final boolean talentsActionVisible;
        private final boolean talentsActionEnabled;

        public LinkedNpcEntry(UUID npcUuid,
                              String displayName,
                              int currentHealth,
                              int maxHealth,
                              int currentHappiness,
                              int maxHappiness,
                              boolean loaded,
                              boolean hasHome,
                              boolean dead,
                              boolean captured,
                              long deadRespawnRemainingMs,
                              LinkedNpcTraitIndicator[] traitIndicators) {
            this(
                    npcUuid,
                    displayName,
                    currentHealth,
                    maxHealth,
                    currentHappiness,
                    maxHappiness,
                    loaded,
                    hasHome,
                    dead,
                    captured,
                    deadRespawnRemainingMs,
                    null,
                    null,
                    traitIndicators,
                    false,
                    false,
                    false,
                    false
            );
        }

        public LinkedNpcEntry(UUID npcUuid,
                              String displayName,
                              int currentHealth,
                              int maxHealth,
                              int currentHappiness,
                              int maxHappiness,
                              boolean loaded,
                              boolean hasHome,
                              boolean dead,
                              boolean captured,
                              long deadRespawnRemainingMs,
                              FutureStat futureStatA,
                              FutureStat futureStatB,
                              LinkedNpcTraitIndicator[] traitIndicators,
                              boolean traitsActionVisible,
                              boolean traitsActionEnabled,
                              boolean talentsActionVisible,
                              boolean talentsActionEnabled) {
            this.npcUuid = npcUuid;
            this.displayName = displayName;
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
            this.currentHappiness = currentHappiness;
            this.maxHappiness = maxHappiness;
            this.loaded = loaded;
            this.hasHome = hasHome;
            this.dead = dead;
            this.captured = captured;
            this.deadRespawnRemainingMs = Math.max(0L, deadRespawnRemainingMs);
            this.futureStatA = futureStatA;
            this.futureStatB = futureStatB;
            this.traitIndicators = sanitizeTraitIndicators(traitIndicators);
            this.traitsActionVisible = traitsActionVisible;
            this.traitsActionEnabled = traitsActionEnabled;
            this.talentsActionVisible = talentsActionVisible;
            this.talentsActionEnabled = talentsActionEnabled;
        }

        public boolean hasHealth() {
            return loaded && maxHealth > 0;
        }

        public UUID npcUuid() {
            return npcUuid;
        }

        public String displayName() {
            return displayName;
        }

        public int currentHealth() {
            return currentHealth;
        }

        public int maxHealth() {
            return maxHealth;
        }

        public int currentHappiness() {
            return currentHappiness;
        }

        public int maxHappiness() {
            return maxHappiness;
        }

        public boolean loaded() {
            return loaded;
        }

        public boolean dead() {
            return dead;
        }

        public boolean captured() {
            return captured;
        }

        public boolean hasHome() {
            return hasHome;
        }

        public long deadRespawnRemainingMs() {
            return deadRespawnRemainingMs;
        }

        public double healthRatio() {
            if (!hasHealth()) {
                return 0.0;
            }
            return (double) currentHealth / (double) maxHealth;
        }

        public boolean hasHappiness() {
            return loaded && maxHappiness > 0;
        }

        public double happinessRatio() {
            if (!hasHappiness()) {
                return 0.0;
            }
            return (double) currentHappiness / (double) maxHappiness;
        }

        public boolean hasFutureStatA() {
            return futureStatA != null;
        }

        public boolean hasFutureStatB() {
            return futureStatB != null;
        }

        public FutureStat futureStatA() {
            return futureStatA;
        }

        public FutureStat futureStatB() {
            return futureStatB;
        }

        public LinkedNpcTraitIndicator[] traitIndicators() {
            return traitIndicators;
        }

        public boolean hasAnyFutureAction() {
            return traitsActionVisible || talentsActionVisible;
        }

        public boolean isTraitsActionVisible() {
            return traitsActionVisible;
        }

        public boolean isTraitsActionEnabled() {
            return traitsActionEnabled;
        }

        public boolean isTalentsActionVisible() {
            return talentsActionVisible;
        }

        public boolean isTalentsActionEnabled() {
            return talentsActionEnabled;
        }

        private static LinkedNpcTraitIndicator[] sanitizeTraitIndicators(LinkedNpcTraitIndicator[] input) {
            if (input == null || input.length == 0) {
                return LinkedNpcTraitIndicator.EMPTY;
            }
            List<LinkedNpcTraitIndicator> out = new ArrayList<>(input.length);
            for (LinkedNpcTraitIndicator indicator : input) {
                if (indicator == null) {
                    continue;
                }
                out.add(indicator);
                if (out.size() >= LinkedNpcTraitIndicatorBinder.MAX_VISIBLE_TRAIT_INDICATORS) {
                    break;
                }
            }
            return out.isEmpty() ? LinkedNpcTraitIndicator.EMPTY : out.toArray(new LinkedNpcTraitIndicator[0]);
        }
    }

    /** Placeholder stat entry used for future linked-panel bars (hunger/thirst/happiness/etc.). */
    public static final class FutureStat {
        private final String label;
        private final int current;
        private final int max;

        public FutureStat(String label, int current, int max) {
            this.label = label;
            this.current = current;
            this.max = max;
        }

        public String label() {
            return label;
        }

        public int current() {
            return current;
        }

        public int max() {
            return max;
        }
    }

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
            .build();

        private String commandId;
    }
}
