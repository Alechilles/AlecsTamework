package com.alechilles.alecstamework.ui;

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
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Group manager page for command tools.
 *
 * <p>Allows create/rename/recolor/delete operations for group metadata persisted on a command tool.
 */
public final class TameworkCommandGroupManagerPage
        extends InteractiveCustomUIPage<TameworkCommandGroupManagerPage.GroupManagerEventData> {
    public static final String UI_PATH = "TameworkCommandGroupManager.ui";
    public static final String ROW_UI_PATH = "TameworkCommandGroupManagerRow.ui";
    private static final String KEY_ACTION = "Action";
    private static final String KEY_CREATE_EVENT = "GroupCreateEvent";
    private static final String KEY_CLOSE_EVENT = "GroupCloseEvent";
    private static final String KEY_NAME_INPUT = "@GroupNameInput";
    private static final String KEY_COLOR_INPUT = "@GroupColorInput";
    private static final String ACTION_CLOSE = "__close__";
    private static final String ACTION_CREATE = "__create__";
    private static final String ACTION_RENAME_PREFIX = "__rename__:";
    private static final String ACTION_RECOLOR_PREFIX = "__recolor__:";
    private static final String ACTION_DELETE_PREFIX = "__delete__:";
    private static final String DEFAULT_GROUP_COLOR = "#4B657F";
    private static final String EVENT_TRUE = "1";

    private final Supplier<List<GroupEntry>> groupsSupplier;
    private final BiConsumer<String, String> createCallback;
    private final BiConsumer<String, String> renameCallback;
    private final BiConsumer<String, String> recolorCallback;
    private final Consumer<String> deleteCallback;
    private final Runnable closeCallback;
    private GroupEntry[] entries;
    private String draftName;
    private String draftColor;
    private boolean handled;

    public TameworkCommandGroupManagerPage(@Nonnull PlayerRef playerRef,
                                           @Nonnull Supplier<List<GroupEntry>> groupsSupplier,
                                           @Nonnull BiConsumer<String, String> createCallback,
                                           @Nonnull BiConsumer<String, String> renameCallback,
                                           @Nonnull BiConsumer<String, String> recolorCallback,
                                           @Nonnull Consumer<String> deleteCallback,
                                           @Nonnull Runnable closeCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, GroupManagerEventData.CODEC);
        this.groupsSupplier = groupsSupplier;
        this.createCallback = createCallback;
        this.renameCallback = renameCallback;
        this.recolorCallback = recolorCallback;
        this.deleteCallback = deleteCallback;
        this.closeCallback = closeCallback;
        this.entries = new GroupEntry[0];
        this.draftName = "";
        this.draftColor = DEFAULT_GROUP_COLOR;
        this.handled = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        refreshGroups();
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TameworkGroupNameInput.Value", draftName);
        commandBuilder.set("#TameworkGroupColorInput.Color", draftColor);
        bindList(commandBuilder, eventBuilder);
        bindHeaderEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull GroupManagerEventData data) {
        if (data.nameInput != null) {
            draftName = data.nameInput;
        }
        if (data.colorInput != null) {
            draftColor = normalizeDraftColor(data.colorInput);
        }
        boolean closePressed = EVENT_TRUE.equals(data.closeEvent);
        boolean createPressed = EVENT_TRUE.equals(data.createEvent);
        String action = data.action != null ? data.action.trim() : "";
        if (closePressed || ACTION_CLOSE.equals(action)) {
            handled = true;
            close();
            if (closeCallback != null) {
                closeCallback.run();
            }
            return;
        }
        if (createPressed || ACTION_CREATE.equals(action)) {
            if (createCallback != null) {
                createCallback.accept(draftName, draftColor);
            }
            draftName = "";
            refreshAndSend();
            return;
        }
        if (action.isBlank()) {
            return;
        }
        if (action.startsWith(ACTION_RENAME_PREFIX)) {
            String groupId = action.substring(ACTION_RENAME_PREFIX.length()).trim();
            if (!groupId.isBlank() && renameCallback != null) {
                renameCallback.accept(groupId, draftName);
            }
            refreshAndSend();
            return;
        }
        if (action.startsWith(ACTION_RECOLOR_PREFIX)) {
            String groupId = action.substring(ACTION_RECOLOR_PREFIX.length()).trim();
            if (!groupId.isBlank() && recolorCallback != null) {
                recolorCallback.accept(groupId, draftColor);
            }
            refreshAndSend();
            return;
        }
        if (action.startsWith(ACTION_DELETE_PREFIX)) {
            String groupId = action.substring(ACTION_DELETE_PREFIX.length()).trim();
            if (!groupId.isBlank() && deleteCallback != null) {
                deleteCallback.accept(groupId);
            }
            refreshAndSend();
            return;
        }
        handled = true;
        close();
        if (closeCallback != null) {
            closeCallback.run();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (handled) {
            return;
        }
        handled = true;
        if (closeCallback != null) {
            closeCallback.run();
        }
    }

    private void refreshAndSend() {
        refreshGroups();
        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        commandBuilder.set("#TameworkGroupNameInput.Value", draftName);
        commandBuilder.set("#TameworkGroupColorInput.Color", draftColor);
        bindList(commandBuilder, eventBuilder);
        bindHeaderEvents(eventBuilder);
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    private void refreshGroups() {
        List<GroupEntry> values = groupsSupplier != null ? groupsSupplier.get() : List.of();
        if (values == null || values.isEmpty()) {
            entries = new GroupEntry[0];
            return;
        }
        ArrayList<GroupEntry> out = new ArrayList<>(values.size());
        for (GroupEntry value : values) {
            if (value == null || value.groupId == null || value.groupId.isBlank()) {
                continue;
            }
            String name = value.name == null || value.name.isBlank() ? value.groupId : value.name;
            String color = value.colorHex == null || value.colorHex.isBlank() ? DEFAULT_GROUP_COLOR : value.colorHex;
            out.add(new GroupEntry(value.groupId, name, color));
        }
        entries = out.toArray(new GroupEntry[0]);
    }

    private void bindHeaderEvents(UIEventBuilder eventBuilder) {
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupCreateButton",
                new EventData()
                        .append(KEY_CREATE_EVENT, EVENT_TRUE)
                        .append(KEY_ACTION, ACTION_CREATE)
                        .append(KEY_NAME_INPUT, "#TameworkGroupNameInput.Value")
                        .append(KEY_COLOR_INPUT, "#TameworkGroupColorInput.Color"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupCloseButton",
                new EventData()
                        .append(KEY_CLOSE_EVENT, EVENT_TRUE)
                        .append(KEY_ACTION, ACTION_CLOSE),
                false
        );
    }

    private void bindList(UICommandBuilder commandBuilder, UIEventBuilder eventBuilder) {
        commandBuilder.clear("#TameworkGroupManagerList");
        boolean hasEntries = entries.length > 0;
        commandBuilder.set("#TameworkGroupManagerListViewport.Visible", hasEntries);
        commandBuilder.set("#TameworkGroupManagerEmptyState.Visible", !hasEntries);
        if (!hasEntries) {
            return;
        }
        for (int i = 0; i < entries.length; i++) {
            GroupEntry entry = entries[i];
            String root = "#TameworkGroupManagerList[" + i + "]";
            commandBuilder.append("#TameworkGroupManagerList", ROW_UI_PATH);
            commandBuilder.set(root + " #GroupName.Text", entry.name);
            commandBuilder.set(root + " #GroupColorSwatch.Background", entry.colorHex);
            commandBuilder.set(root + " #GroupMeta.Text", entry.groupId);

            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    root + " #RenameButton",
                    EventData.of(KEY_ACTION, ACTION_RENAME_PREFIX + entry.groupId)
                            .append(KEY_NAME_INPUT, "#TameworkGroupNameInput.Value"),
                    false
            );
                eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    root + " #ColorButton",
                    EventData.of(KEY_ACTION, ACTION_RECOLOR_PREFIX + entry.groupId)
                            .append(KEY_COLOR_INPUT, "#TameworkGroupColorInput.Color"),
                    false
            );
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    root + " #DeleteButton",
                    EventData.of(KEY_ACTION, ACTION_DELETE_PREFIX + entry.groupId),
                    false
            );
        }
    }

    public static final class GroupManagerEventData {
        public static final BuilderCodec<GroupManagerEventData> CODEC = BuilderCodec.builder(
                GroupManagerEventData.class,
                GroupManagerEventData::new
        )
                .<String>append(
                        new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                        (data, value) -> data.action = value,
                        data -> data.action
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(KEY_CREATE_EVENT, Codec.STRING),
                        (data, value) -> data.createEvent = value,
                        data -> data.createEvent
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(KEY_CLOSE_EVENT, Codec.STRING),
                        (data, value) -> data.closeEvent = value,
                        data -> data.closeEvent
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(KEY_NAME_INPUT, Codec.STRING),
                        (data, value) -> data.nameInput = value,
                        data -> data.nameInput
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(KEY_COLOR_INPUT, Codec.STRING),
                        (data, value) -> data.colorInput = value,
                        data -> data.colorInput
                )
                .add()
                .build();

        private String action;
        private String createEvent;
        private String closeEvent;
        private String nameInput;
        private String colorInput;
    }

    private String normalizeDraftColor(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_GROUP_COLOR;
        }
        String trimmed = value.trim();
        String normalized = trimmed;
        if (trimmed.length() > 7 && trimmed.startsWith("#")) {
            normalized = trimmed.substring(0, 7);
        }
        if (normalized.matches("^[0-9A-Fa-f]{6}$")) {
            normalized = "#" + normalized;
        }
        if (!normalized.matches("^#[0-9A-Fa-f]{6}$")) {
            return DEFAULT_GROUP_COLOR;
        }
        return "#" + normalized.substring(1).toUpperCase(Locale.ROOT);
    }

    /**
     * Lightweight UI row model for a command group.
     */
    public static final class GroupEntry {
        public final String groupId;
        public final String name;
        public final String colorHex;

        public GroupEntry(String groupId, String name, String colorHex) {
            this.groupId = groupId;
            this.name = name;
            this.colorHex = colorHex;
        }
    }
}
