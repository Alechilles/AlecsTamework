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
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Interactive command-selection page for command items.
 * Presents a radial-style set of clickable command buttons and returns the selected command id.
 */
public final class TameworkCommandSelectionPage
        extends InteractiveCustomUIPage<TameworkCommandSelectionPage.CommandSelectionEventData> {
    public static final String UI_PATH = "TameworkCommandRadialMenu.ui";
    private static final String EVENT_COMMAND_ID = "CommandId";
    private static final String CLOSE_COMMAND_ID = "__close__";
    private static final int MAX_COMMAND_BUTTONS = 8;

    private final CommandOption[] options;
    private final String selectedCommandId;
    private final Consumer<String> selectionCallback;

    public TameworkCommandSelectionPage(@Nonnull PlayerRef playerRef,
                                        @Nonnull TwCommandItemConfig config,
                                        String selectedCommandId,
                                        @Nonnull Consumer<String> selectionCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, CommandSelectionEventData.CODEC);
        this.options = buildOptions(config);
        this.selectedCommandId = selectedCommandId;
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

        for (int i = 0; i < MAX_COMMAND_BUTTONS; i++) {
            String selector = "#CommandButton" + i;
            if (i >= options.length) {
                commandBuilder.set(selector + ".Visible", false);
                continue;
            }
            CommandOption option = options[i];
            String label = option.label;
            if (commandIdEquals(option.id, selectedCommandId)) {
                label = "• " + label;
            }
            commandBuilder.set(selector + ".Visible", true);
            commandBuilder.set(selector + ".Text", label);
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
        if (!containsOption(data.commandId)) {
            close();
            return;
        }
        selectionCallback.accept(data.commandId);
        close();
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
            .build();

        private String commandId;
    }
}
