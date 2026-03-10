package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
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
    private static final String EVENT_ACTION = "Action";
    private static final String EVENT_COMMAND_ID = "CommandId";
    private static final String KEY_NAME_INPUT = "@GroupNameInput";
    private static final String KEY_COLOR_INPUT = "@GroupColorInput";
    private static final String KEY_ROW_NAME_INPUT = "@GroupRowNameInput";
    private static final String KEY_ROW_COLOR_INPUT = "@GroupRowColorInput";
    private static final String ACTION_CLOSE = "__close__";
    private static final String ACTION_BACK = "__back__";
    private static final String ACTION_CREATE = "__create__";
    private static final String ACTION_EDIT_PREFIX = "__edit__:";
    private static final String ACTION_COMPLETE_PREFIX = "__complete__:";
    private static final String ACTION_DELETE_PREFIX = "__delete__:";
    private static final String DEFAULT_GROUP_COLOR = "#4B657F";
    private static final String DEFAULT_SUBTITLE = "Create, edit, recolor, or delete groups.";
    private static final Logger LOGGER = Logger.getLogger(TameworkCommandGroupManagerPage.class.getName());

    private final Supplier<List<GroupEntry>> groupsSupplier;
    private final BiConsumer<String, String> createCallback;
    private final BiConsumer<String, String> renameCallback;
    private final BiConsumer<String, String> recolorCallback;
    private final Consumer<String> deleteCallback;
    private final Runnable backCallback;
    private final Runnable closeCallback;
    private GroupEntry[] entries;
    private String draftName;
    private String draftColor;
    private String editingGroupId;
    private boolean navigationPending;
    private boolean handled;

    public TameworkCommandGroupManagerPage(@Nonnull PlayerRef playerRef,
                                           @Nonnull Supplier<List<GroupEntry>> groupsSupplier,
                                           @Nonnull BiConsumer<String, String> createCallback,
                                           @Nonnull BiConsumer<String, String> renameCallback,
                                           @Nonnull BiConsumer<String, String> recolorCallback,
                                           @Nonnull Consumer<String> deleteCallback,
                                           @Nonnull Runnable backCallback,
                                           @Nonnull Runnable closeCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, GroupManagerEventData.CODEC);
        this.groupsSupplier = groupsSupplier;
        this.createCallback = createCallback;
        this.renameCallback = renameCallback;
        this.recolorCallback = recolorCallback;
        this.deleteCallback = deleteCallback;
        this.backCallback = backCallback;
        this.closeCallback = closeCallback;
        this.entries = new GroupEntry[0];
        this.draftName = "";
        this.draftColor = DEFAULT_GROUP_COLOR;
        this.editingGroupId = null;
        this.navigationPending = false;
        this.handled = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        refreshGroups();
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TameworkGroupManagerSubtitle.Text", resolveSubtitleText());
        commandBuilder.set("#TameworkGroupNameInput.Value", draftName);
        commandBuilder.set("#TameworkGroupColorInput.Color", draftColor);
        bindList(commandBuilder, eventBuilder);
        bindHeaderEvents(eventBuilder);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull String rawData) {
        Map<String, String> rawEventData = decodeRawEventData(rawData);
        if (rawEventData.isEmpty()) {
            super.handleDataEvent(ref, store, rawData);
            return;
        }
        String action = firstNonBlank(
                rawEventData.get(EVENT_ACTION),
                rawEventData.get("action"),
                rawEventData.get(EVENT_COMMAND_ID),
                rawEventData.get("commandId")
        );
        String nameInput = firstNonBlank(
                rawEventData.get(KEY_NAME_INPUT),
                rawEventData.get("GroupNameInput"),
                rawEventData.get("nameInput")
        );
        String colorInput = firstNonBlank(
                rawEventData.get(KEY_COLOR_INPUT),
                rawEventData.get("GroupColorInput"),
                rawEventData.get("colorInput")
        );
        String rowNameInput = firstNonBlank(
                rawEventData.get(KEY_ROW_NAME_INPUT),
                rawEventData.get("GroupRowNameInput"),
                rawEventData.get("rowNameInput")
        );
        String rowColorInput = firstNonBlank(
                rawEventData.get(KEY_ROW_COLOR_INPUT),
                rawEventData.get("GroupRowColorInput"),
                rawEventData.get("rowColorInput")
        );
        if (action == null && nameInput == null && colorInput == null && rowNameInput == null && rowColorInput == null) {
            super.handleDataEvent(ref, store, rawData);
            return;
        }
        handleResolvedEvent(action, nameInput, colorInput, rowNameInput, rowColorInput);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull GroupManagerEventData data) {
        String action = data != null ? resolveAction(data) : null;
        String nameInput = data != null ? data.nameInput : null;
        String colorInput = data != null ? data.colorInput : null;
        String rowNameInput = data != null ? data.rowNameInput : null;
        String rowColorInput = data != null ? data.rowColorInput : null;
        handleResolvedEvent(action, nameInput, colorInput, rowNameInput, rowColorInput);
    }

    private void handleResolvedEvent(String action,
                                     String nameInput,
                                     String colorInput,
                                     String rowNameInput,
                                     String rowColorInput) {
        if (navigationPending) {
            return;
        }
        if (nameInput != null) {
            draftName = nameInput;
        }
        if (colorInput != null) {
            draftColor = normalizeDraftColor(colorInput);
        }
        String normalizedAction = action != null ? action.trim() : "";
        LOGGER.log(
                Level.INFO,
                "Group manager event: commandId={0} name={1} color={2}",
                new Object[] {
                        safeForLog(normalizedAction),
                        safeForLog(draftName),
                        safeForLog(draftColor)
                }
        );
        if (ACTION_CLOSE.equals(normalizedAction)) {
            LOGGER.log(Level.INFO, "Group manager close requested.");
            handled = true;
            close();
            if (closeCallback != null) {
                closeCallback.run();
            }
            return;
        }
        if (ACTION_BACK.equals(normalizedAction)) {
            LOGGER.log(Level.INFO, "Group manager back requested.");
            if (navigationPending) {
                return;
            }
            handled = true;
            navigationPending = true;
            navigateAfterUiDrain(() -> {
                try {
                    if (backCallback != null) {
                        backCallback.run();
                    }
                } finally {
                    navigationPending = false;
                }
            });
            return;
        }
        if (ACTION_CREATE.equals(normalizedAction)) {
            LOGGER.log(Level.INFO, "Group manager create requested for name={0} color={1}",
                    new Object[] {
                            safeForLog(draftName),
                            safeForLog(draftColor)
                    });
            applyCreate();
            refreshAndSend();
            return;
        }
        if (normalizedAction.isBlank()) {
            LOGGER.log(Level.INFO, "Group manager event ignored because no action payload was provided.");
            return;
        }
        if (normalizedAction.startsWith(ACTION_EDIT_PREFIX)) {
            String groupId = normalizedAction.substring(ACTION_EDIT_PREFIX.length()).trim();
            beginRowEdit(groupId);
            refreshAndSend();
            return;
        }
        if (normalizedAction.startsWith(ACTION_COMPLETE_PREFIX)) {
            String groupId = normalizedAction.substring(ACTION_COMPLETE_PREFIX.length()).trim();
            completeRowEdit(groupId, rowNameInput, rowColorInput);
            refreshAndSend();
            return;
        }
        if (normalizedAction.startsWith(ACTION_DELETE_PREFIX)) {
            String groupId = normalizedAction.substring(ACTION_DELETE_PREFIX.length()).trim();
            if (!groupId.isBlank() && deleteCallback != null) {
                LOGGER.log(Level.INFO, "Group manager delete requested for groupId={0}",
                        new Object[] { safeForLog(groupId) });
                deleteCallback.accept(groupId);
            }
            if (!groupId.isBlank() && groupId.equalsIgnoreCase(editingGroupId)) {
                clearRowEdit();
            }
            refreshAndSend();
            return;
        }
        LOGGER.log(Level.INFO, "Group manager ignored unknown action payload: {0}", safeForLog(normalizedAction));
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        navigationPending = false;
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
        commandBuilder.set("#TameworkGroupManagerSubtitle.Text", resolveSubtitleText());
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
                CustomUIEventBindingType.ValueChanged,
                "#TameworkGroupNameInput",
                EventData.of(KEY_NAME_INPUT, "#TameworkGroupNameInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupCreateButton",
                EventData.of(EVENT_ACTION, ACTION_CREATE)
                        .append(KEY_NAME_INPUT, "#TameworkGroupNameInput.Value")
                        .append(KEY_COLOR_INPUT, "#TameworkGroupColorInput.Color"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupCloseButton",
                EventData.of(EVENT_ACTION, ACTION_CLOSE),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupBackButton",
                EventData.of(EVENT_ACTION, ACTION_BACK),
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
            commandBuilder.set(root + " #GroupNameInput.Value", entry.name);
            commandBuilder.set(root + " #GroupColorPicker.Color", entry.colorHex);

            boolean editing = isRowEditing(entry.groupId);
            commandBuilder.set(root + " #GroupName.Visible", !editing);
            commandBuilder.set(root + " #GroupNameInput.Visible", editing);
            commandBuilder.set(root + " #GroupColorSwatch.Visible", !editing);
            commandBuilder.set(root + " #GroupColorPicker.Visible", editing);
            commandBuilder.set(root + " #EditButton.Visible", !editing);
            commandBuilder.set(root + " #CompleteButton.Visible", editing);

            if (!editing) {
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #EditButton",
                        EventData.of(EVENT_ACTION, ACTION_EDIT_PREFIX + entry.groupId),
                        false
                );
            } else {
                eventBuilder.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        root + " #CompleteButton",
                        EventData.of(EVENT_ACTION, ACTION_COMPLETE_PREFIX + entry.groupId)
                                .append(KEY_ROW_NAME_INPUT, root + " #GroupNameInput.Value")
                                .append(KEY_ROW_COLOR_INPUT, root + " #GroupColorPicker.Color"),
                        false
                );
            }
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    root + " #DeleteButton",
                    EventData.of(EVENT_ACTION, ACTION_DELETE_PREFIX + entry.groupId),
                    false
            );
        }
    }

    private void applyCreate() {
        if (createCallback != null) {
            createCallback.accept(draftName, draftColor);
        }
        draftName = "";
    }

    private void beginRowEdit(String groupId) {
        GroupEntry target = findEntry(groupId);
        if (target == null) {
            clearRowEdit();
            return;
        }
        editingGroupId = target.groupId;
    }

    private void completeRowEdit(String groupId, String rowNameInput, String rowColorInput) {
        GroupEntry target = findEntry(groupId);
        if (target == null) {
            clearRowEdit();
            return;
        }
        String nextName = rowNameInput == null ? target.name : rowNameInput.trim();
        if (nextName.isBlank()) {
            nextName = target.name;
        }
        if (!target.name.equals(nextName) && renameCallback != null) {
            LOGGER.log(Level.INFO, "Group manager rename requested for groupId={0} newName={1}",
                    new Object[] { safeForLog(target.groupId), safeForLog(nextName) });
            renameCallback.accept(target.groupId, nextName);
        }
        String nextColor = resolveRowColorInput(rowColorInput, target.colorHex);
        if (!target.colorHex.equalsIgnoreCase(nextColor) && recolorCallback != null) {
            LOGGER.log(Level.INFO, "Group manager recolor requested for groupId={0} color={1}",
                    new Object[] { safeForLog(target.groupId), safeForLog(nextColor) });
            recolorCallback.accept(target.groupId, nextColor);
        }
        clearRowEdit();
    }

    private GroupEntry findEntry(String groupId) {
        if (groupId == null || groupId.isBlank() || entries.length == 0) {
            return null;
        }
        for (GroupEntry entry : entries) {
            if (entry == null || entry.groupId == null) {
                continue;
            }
            if (entry.groupId.equalsIgnoreCase(groupId.trim())) {
                return entry;
            }
        }
        return null;
    }

    private boolean isRowEditing(String groupId) {
        return editingGroupId != null
                && groupId != null
                && editingGroupId.equalsIgnoreCase(groupId.trim());
    }

    private void clearRowEdit() {
        editingGroupId = null;
    }

    private String resolveSubtitleText() {
        if (editingGroupId == null || editingGroupId.isBlank()) {
            return DEFAULT_SUBTITLE;
        }
        GroupEntry entry = findEntry(editingGroupId);
        if (entry == null) {
            return DEFAULT_SUBTITLE;
        }
        return "Editing \"" + entry.name + "\". Update fields, then click Done.";
    }

    private String resolveRowColorInput(String value, String fallbackColor) {
        if (value == null || value.isBlank()) {
            return normalizeDraftColor(fallbackColor);
        }
        String normalized = normalizeDraftColor(value);
        if (normalized.equalsIgnoreCase(DEFAULT_GROUP_COLOR)
                && !normalizeDraftColor(fallbackColor).equalsIgnoreCase(DEFAULT_GROUP_COLOR)
                && !looksLikeHexColor(value)) {
            return normalizeDraftColor(fallbackColor);
        }
        return normalized;
    }

    private boolean looksLikeHexColor(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.matches("^#[0-9A-Fa-f]{6}$") || trimmed.matches("^[0-9A-Fa-f]{6}$");
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

    private static String resolveAction(GroupManagerEventData data) {
        if (data == null) {
            return "";
        }
        if (data.action != null && !data.action.isBlank()) {
            return data.action.trim();
        }
        if (data.commandId != null && !data.commandId.isBlank()) {
            return data.commandId.trim();
        }
        return "";
    }

    @Nonnull
    private Map<String, String> decodeRawEventData(String rawData) {
        if (rawData == null || rawData.isBlank()) {
            return Map.of();
        }
        try {
            return MapCodec.STRING_HASH_MAP_CODEC.decodeJson(
                    new RawJsonReader(rawData.toCharArray()),
                    ExtraInfo.THREAD_LOCAL.get()
            );
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static final class GroupManagerEventData {
        public static final BuilderCodec<GroupManagerEventData> CODEC = BuilderCodec.builder(
                GroupManagerEventData.class,
                GroupManagerEventData::new
        )
                .<String>append(
                        new KeyedCodec<>(EVENT_ACTION, Codec.STRING),
                        (data, value) -> data.action = value,
                        data -> data.action
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(EVENT_COMMAND_ID, Codec.STRING),
                        (data, value) -> data.commandId = value,
                        data -> data.commandId
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
                .<String>append(
                        new KeyedCodec<>(KEY_ROW_NAME_INPUT, Codec.STRING),
                        (data, value) -> data.rowNameInput = value,
                        data -> data.rowNameInput
                )
                .add()
                .<String>append(
                        new KeyedCodec<>(KEY_ROW_COLOR_INPUT, Codec.STRING),
                        (data, value) -> data.rowColorInput = value,
                        data -> data.rowColorInput
                )
                .add()
                .build();

        private String action;
        private String commandId;
        private String nameInput;
        private String colorInput;
        private String rowNameInput;
        private String rowColorInput;
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

    private static String safeForLog(String value) {
        if (value == null) {
            return "<null>";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "<empty>";
        }
        if (trimmed.length() <= 48) {
            return trimmed;
        }
        return trimmed.substring(0, 45) + "...";
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
