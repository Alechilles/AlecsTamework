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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Interactive command-selection page for command items.
 * Presents a radial-style set of clickable command buttons and returns the selected command id.
 */
public final class TameworkCommandSelectionPage
        extends InteractiveCustomUIPage<TameworkCommandSelectionPage.CommandSelectionEventData> {
    public static final String UI_PATH = "TameworkCommandRadialMenu.ui";
    public static final String LINKED_NPC_ROW_UI_PATH = "TameworkLinkedNpcRow.ui";
    private static final String EVENT_COMMAND_ID = "CommandId";
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final String REMOVE_LINK_PREFIX = "__remove_link__:";
    private static final int MAX_COMMAND_BUTTONS = 8;

    private final CommandOption[] options;
    private final String selectedCommandId;
    private final Supplier<List<LinkedNpcMenuEntry>> linkedNpcSupplier;
    private final Consumer<String> selectionCallback;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        @Nonnull Supplier<List<LinkedNpcMenuEntry>> linkedNpcSupplier,
                                        @Nonnull Consumer<String> selectionCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.options = buildOptions(config);
        this.selectedCommandId = selectedCommandId;
        this.linkedNpcSupplier = linkedNpcSupplier;
        this.selectionCallback = selectionCallback;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TameworkCommandMenuWheel.Visible", true);
        commandBuilder.set("#TameworkCommandMenuTitle.Text", "Select Command");
        commandBuilder.set("#TameworkCommandMenuSubtitle.Text", "Click a command to set it.");
        commandBuilder.set("#TameworkCommandMenuCurrent.Text", resolveCurrentLabel());
        buildLinkedNpcList(commandBuilder, eventBuilder);

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
        if (isRemoveLinkCommand(data.commandId)) {
            selectionCallback.accept(data.commandId);
            rebuild();
            return;
        }
        if (!containsOption(data.commandId)) {
            close();
            return;
        }
        close();
        selectionCallback.accept(data.commandId);
    }

    private void buildLinkedNpcList(@Nonnull UICommandBuilder commandBuilder,
                                    @Nonnull UIEventBuilder eventBuilder) {
        List<LinkedNpcMenuEntry> entries = resolveLinkedNpcEntries();
        commandBuilder.set("#LinkedNpcPanel.Visible", true);
        commandBuilder.set("#LinkedNpcPanelCount.Text", "Linked NPCs: " + entries.size());
        commandBuilder.clear("#LinkedNpcList");

        if (entries.isEmpty()) {
            commandBuilder.appendInline(
                    "#LinkedNpcList",
                    "Label { Text: \"No linked NPCs\"; Style: (FontSize: 14, TextColor: #d6e0ec, HorizontalAlignment: Center, VerticalAlignment: Top); }"
            );
            return;
        }

        for (int i = 0; i < entries.size(); i++) {
            LinkedNpcMenuEntry entry = entries.get(i);
            String selector = "#LinkedNpcList[" + i + "]";
            commandBuilder.append("#LinkedNpcList", LINKED_NPC_ROW_UI_PATH);
            commandBuilder.set(selector + " #LinkedNpcName.Text", entry.displayName());
            commandBuilder.set(selector + " #LinkedNpcStatus.Text", entry.statusText());
            commandBuilder.set(selector + " #LinkedNpcHealthBar.Value", entry.healthRatio());
            commandBuilder.set(selector + " #LinkedNpcHealthText.Text", entry.healthText());
            commandBuilder.set(selector + " #LinkedNpcRemove.Text", "Remove");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector + " #LinkedNpcRemove",
                    EventData.of(EVENT_COMMAND_ID, toRemoveLinkCommandId(entry.npcUuid())),
                    false
            );
        }
    }

    private List<LinkedNpcMenuEntry> resolveLinkedNpcEntries() {
        if (linkedNpcSupplier == null) {
            return List.of();
        }
        List<LinkedNpcMenuEntry> values = linkedNpcSupplier.get();
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcMenuEntry> filtered = new ArrayList<>(values.size());
        for (LinkedNpcMenuEntry entry : values) {
            if (entry == null || entry.npcUuid() == null || entry.npcUuid().isBlank()) {
                continue;
            }
            filtered.add(entry);
        }
        if (filtered.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(filtered);
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

    private static boolean isRemoveLinkCommand(String commandId) {
        return commandId != null && commandId.startsWith(REMOVE_LINK_PREFIX);
    }

    public static String toRemoveLinkCommandId(String npcUuid) {
        if (npcUuid == null || npcUuid.isBlank()) {
            return REMOVE_LINK_PREFIX;
        }
        return REMOVE_LINK_PREFIX + npcUuid.trim();
    }

    public static String extractRemoveLinkNpcUuid(String commandId) {
        if (!isRemoveLinkCommand(commandId)) {
            return null;
        }
        String raw = commandId.substring(REMOVE_LINK_PREFIX.length());
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private record CommandOption(String id, String label) { }

    /** UI model for a linked-NPC entry shown beside the command radial menu. */
    public record LinkedNpcMenuEntry(
            String npcUuid,
            String displayName,
            float healthRatio,
            String healthText,
            String statusText
    ) {
        public LinkedNpcMenuEntry {
            Objects.requireNonNull(npcUuid, "npcUuid");
            if (displayName == null || displayName.isBlank()) {
                displayName = npcUuid;
            }
            if (healthText == null || healthText.isBlank()) {
                healthText = "Health: unknown";
            }
            if (statusText == null || statusText.isBlank()) {
                statusText = "Unknown";
            }
            healthRatio = Math.max(0.0f, Math.min(1.0f, healthRatio));
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
