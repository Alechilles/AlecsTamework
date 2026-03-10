package com.alechilles.alecstamework.ui;

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
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/**
 * Modal page for assigning one NPC to a command group.
 */
public final class TameworkCommandGroupAssignPage
        extends InteractiveCustomUIPage<TameworkCommandGroupAssignPage.GroupAssignEventData> {
    public static final String UI_PATH = "TameworkCommandGroupAssign.ui";
    private static final String KEY_ACTION = "Action";
    private static final String KEY_GROUP_VALUE = "@GroupValue";
    private static final String ACTION_APPLY = "__apply__";
    private static final String ACTION_CANCEL = "__cancel__";
    private static final String GROUP_NONE_VALUE = "None";

    private final UUID npcUuid;
    private final String npcName;
    private final List<DropdownEntryInfo> dropdownEntries;
    private final BiConsumer<UUID, String> applyCallback;
    private final Runnable cancelCallback;
    private String selectedGroupValue;
    private boolean handled;
    private boolean navigationPending;

    public TameworkCommandGroupAssignPage(@Nonnull PlayerRef playerRef,
                                          @Nonnull UUID npcUuid,
                                          String npcName,
                                          @Nonnull List<DropdownEntryInfo> dropdownEntries,
                                          String selectedGroupValue,
                                          @Nonnull BiConsumer<UUID, String> applyCallback,
                                          @Nonnull Runnable cancelCallback) {
        super(playerRef, CustomPageLifetime.CanDismiss, GroupAssignEventData.CODEC);
        this.npcUuid = npcUuid;
        this.npcName = (npcName == null || npcName.isBlank()) ? "NPC" : npcName;
        this.dropdownEntries = dropdownEntries != null ? dropdownEntries : List.of();
        this.applyCallback = applyCallback;
        this.cancelCallback = cancelCallback;
        this.selectedGroupValue = normalizeSelectedValue(selectedGroupValue);
        this.handled = false;
        this.navigationPending = false;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder,
                      @Nonnull Store<EntityStore> store) {
        commandBuilder.append(UI_PATH);
        commandBuilder.set("#TameworkGroupAssignTitle.Text", "Assign Group");
        commandBuilder.set("#TameworkGroupAssignSubtitle.Text", "Choose a group for " + npcName + ".");
        commandBuilder.set("#TameworkGroupAssignDropdown.Entries", dropdownEntries);
        commandBuilder.set("#TameworkGroupAssignDropdown.Value", selectedGroupValue);

        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#TameworkGroupAssignDropdown",
                EventData.of(KEY_GROUP_VALUE, "#TameworkGroupAssignDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupAssignApplyButton",
                EventData.of(KEY_ACTION, ACTION_APPLY)
                        .append(KEY_GROUP_VALUE, "#TameworkGroupAssignDropdown.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TameworkGroupAssignCancelButton",
                EventData.of(KEY_ACTION, ACTION_CANCEL),
                false
        );
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
                                @Nonnull Store<EntityStore> store,
                                @Nonnull GroupAssignEventData data) {
        if (navigationPending) {
            return;
        }
        if (data.groupValue != null) {
            selectedGroupValue = normalizeSelectedValue(data.groupValue);
        }
        String action = data.action == null ? "" : data.action.trim();
        if (action.isBlank()) {
            return;
        }
        if (ACTION_CANCEL.equalsIgnoreCase(action)) {
            handled = true;
            navigationPending = true;
            navigateAfterUiDrain(() -> {
                try {
                    if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                } finally {
                    navigationPending = false;
                }
            });
            return;
        }
        if (ACTION_APPLY.equalsIgnoreCase(action)) {
            String normalizedGroupId = normalizeGroupIdForAssignment(selectedGroupValue);
            handled = true;
            navigationPending = true;
            navigateAfterUiDrain(() -> {
                try {
                    if (applyCallback != null) {
                        applyCallback.accept(npcUuid, normalizedGroupId);
                    }
                } finally {
                    navigationPending = false;
                }
            });
            return;
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        navigationPending = false;
        if (handled) {
            return;
        }
        handled = true;
        if (cancelCallback != null) {
            cancelCallback.run();
        }
    }

    private String normalizeSelectedValue(String value) {
        if (value == null || value.isBlank()) {
            return GROUP_NONE_VALUE;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? GROUP_NONE_VALUE : trimmed;
    }

    private String normalizeGroupIdForAssignment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank() || GROUP_NONE_VALUE.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private void navigateAfterUiDrain(@Nonnull Runnable action) {
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

    /** Event payload emitted by the group assign modal. */
    public static final class GroupAssignEventData {
        public static final BuilderCodec<GroupAssignEventData> CODEC = BuilderCodec.builder(
                        GroupAssignEventData.class,
                        GroupAssignEventData::new
                )
                .append(
                        new KeyedCodec<>(KEY_ACTION, Codec.STRING),
                        (data, value) -> data.action = value,
                        data -> data.action
                )
                .add()
                .append(
                        new KeyedCodec<>(KEY_GROUP_VALUE, Codec.STRING),
                        (data, value) -> data.groupValue = value,
                        data -> data.groupValue
                )
                .add()
                .build();

        private String action;
        private String groupValue;
    }
}
