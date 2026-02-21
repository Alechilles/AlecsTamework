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
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
    private static final int MAX_COMMAND_BUTTONS = 8;
    private static final int HEALTH_FILL_MAX_WIDTH = 358;

    private final CommandOption[] options;
    private final Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier;
    private LinkedNpcEntry[] linkedNpcEntries;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;
    private final Consumer<UUID> unlinkCallback;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        @Nonnull Supplier<List<LinkedNpcEntry>> linkedNpcEntriesSupplier,
                                        @Nonnull Consumer<UUID> unlinkCallback,
                                        @Nonnull Consumer<String> selectionCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.options = buildOptions(config);
        this.linkedNpcEntriesSupplier = linkedNpcEntriesSupplier;
        this.linkedNpcEntries = new LinkedNpcEntry[0];
        this.selectedCommandId = selectedCommandId;
        this.unlinkCallback = unlinkCallback;
        this.selectionCallback = selectionCallback;
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
        commandBuilder.set("#TameworkLinkedPanelSubtitle.Text", resolvePanelSubtitle());

        buildCommandButtons(commandBuilder, eventBuilder);
        buildLinkedNpcPanel(commandBuilder, eventBuilder);

        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CommandMenuCloseButton",
                EventData.of(EVENT_COMMAND_ID, CLOSE_COMMAND_ID),
                false
        );
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull CommandSelectionEventData data) {
        if (data.commandId == null || data.commandId.isBlank() || CLOSE_COMMAND_ID.equals(data.commandId)) {
            close();
            return;
        }
        if (data.commandId.startsWith(UNLINK_COMMAND_PREFIX)) {
            if (unlinkCallback == null) {
                return;
            }
            UUID npcUuid = parseUnlinkNpcUuid(data.commandId);
            if (npcUuid != null) {
                unlinkCallback.accept(npcUuid);
                refreshLinkedNpcEntries();
                rebuild();
            }
            return;
        }
        if (!containsOption(data.commandId)) {
            close();
            return;
        }
        close();
        selectionCallback.accept(data.commandId);
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
        commandBuilder.clear("#TameworkLinkedPanelList");
        for (int i = 0; i < linkedNpcEntries.length; i++) {
            LinkedNpcEntry entry = linkedNpcEntries[i];
            String entrySelector = "#TameworkLinkedPanelList[" + i + "]";
            String nameSelector = entrySelector + " #Name";
            String healthTextSelector = entrySelector + " #HealthText";
            String healthFillSelector = entrySelector + " #HealthFill";
            String removeSelector = entrySelector + " #RemoveButton";

            commandBuilder.append("#TameworkLinkedPanelList", LINKED_PANEL_CARD_UI_PATH);
            commandBuilder.set(nameSelector + ".Text", entry.displayName());
            if (entry.hasHealth()) {
                commandBuilder.set(
                        healthTextSelector + ".Text",
                        "Health: " + entry.currentHealth() + "/" + entry.maxHealth()
                );
                commandBuilder.set(healthFillSelector + ".Visible", true);
                commandBuilder.setObject(healthFillSelector + ".Anchor", buildHealthFillAnchor(entry.healthRatio()));
            } else {
                commandBuilder.set(healthTextSelector + ".Text", "Health: unavailable");
                commandBuilder.set(healthFillSelector + ".Visible", false);
            }
            commandBuilder.set(removeSelector + ".Text", "");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    removeSelector,
                    EventData.of(EVENT_COMMAND_ID, UNLINK_COMMAND_PREFIX + entry.npcUuid()),
                    false
            );
        }
    }

    private String resolvePanelSubtitle() {
        int total = linkedNpcEntries.length;
        if (total <= 0) {
            return "No linked companions";
        }
        return total + " linked companion" + (total == 1 ? "" : "s");
    }

    private void refreshLinkedNpcEntries() {
        List<LinkedNpcEntry> entries = linkedNpcEntriesSupplier != null ? linkedNpcEntriesSupplier.get() : List.of();
        linkedNpcEntries = buildLinkedNpcEntries(entries);
    }

    private boolean containsOption(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return false;
        }
        for (CommandOption option : options) {
            if (option != null && commandIdEquals(option.id, commandId)) {
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
            if (option != null && commandIdEquals(option.id, selectedCommandId)) {
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
            out.add(new LinkedNpcEntry(entry.npcUuid, name, current, max, entry.loaded));
        }
        out.sort(
                Comparator.comparing(LinkedNpcEntry::loaded).reversed()
                        .thenComparing(LinkedNpcEntry::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.npcUuid.toString())
        );
        return out.toArray(new LinkedNpcEntry[0]);
    }

    private static Anchor buildHealthFillAnchor(double ratio) {
        int width = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * HEALTH_FILL_MAX_WIDTH);
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(1));
        anchor.setTop(Value.of(1));
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(12));
        return anchor;
    }

    private static String resolveLabel(CommandEntry entry) {
        if (entry.getDisplayName() != null && !entry.getDisplayName().isBlank()) {
            return entry.getDisplayName();
        }
        return entry.getId();
    }

    private static boolean commandIdEquals(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private UUID parseUnlinkNpcUuid(String commandId) {
        if (commandId == null || !commandId.startsWith(UNLINK_COMMAND_PREFIX)) {
            return null;
        }
        String raw = commandId.substring(UNLINK_COMMAND_PREFIX.length());
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record CommandOption(String id, String label) { }

    /** View model for one linked NPC row in the command radial side panel. */
    public static final class LinkedNpcEntry {
        private final UUID npcUuid;
        private final String displayName;
        private final int currentHealth;
        private final int maxHealth;
        private final boolean loaded;

        public LinkedNpcEntry(UUID npcUuid, String displayName, int currentHealth, int maxHealth, boolean loaded) {
            this.npcUuid = npcUuid;
            this.displayName = displayName;
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
            this.loaded = loaded;
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

        public boolean loaded() {
            return loaded;
        }

        public double healthRatio() {
            if (!hasHealth()) {
                return 0.0;
            }
            return (double) currentHealth / (double) maxHealth;
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
