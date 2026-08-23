package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached command-group manager state for a custom provider. */
public final class CommandUiGroupFlowView implements CommandUiFlowView {
    public static final String KIND = "groups";

    @Nullable private final String activeGroupId;
    private final List<CommandUiGroupView> groups;
    @Nullable private final CommandUiActionView createAction;
    @Nullable private final CommandUiActionView selectAllAction;
    @Nullable private final CommandUiActionView selectNoneAction;
    private final Map<String, String> metadata;

    public CommandUiGroupFlowView(
            @Nullable String activeGroupId,
            @Nullable List<CommandUiGroupView> groups,
            @Nullable CommandUiActionView createAction,
            @Nullable Map<String, String> metadata
    ) {
        this(activeGroupId, groups, createAction, null, null, metadata);
    }

    /** Creates a group flow with explicit all-groups and no-groups controls. */
    public CommandUiGroupFlowView(
            @Nullable String activeGroupId,
            @Nullable List<CommandUiGroupView> groups,
            @Nullable CommandUiActionView createAction,
            @Nullable CommandUiActionView selectAllAction,
            @Nullable CommandUiActionView selectNoneAction,
            @Nullable Map<String, String> metadata
    ) {
        this.activeGroupId = normalize(activeGroupId);
        this.groups = List.copyOf(groups == null ? List.of() : groups);
        this.createAction = createAction;
        this.selectAllAction = selectAllAction;
        this.selectNoneAction = selectNoneAction;
        this.metadata = copyMetadata(metadata);
    }

    @Nonnull @Override public String kind() { return KIND; }
    @Nullable public String activeGroupId() { return activeGroupId; }
    @Nonnull public List<CommandUiGroupView> groups() { return groups; }
    @Nullable public CommandUiActionView createAction() { return createAction; }
    @Nullable public CommandUiActionView selectAllAction() { return selectAllAction; }
    @Nullable public CommandUiActionView selectNoneAction() { return selectNoneAction; }
    @Nonnull public Map<String, String> metadata() { return metadata; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiGroupFlowView that)) return false;
        return Objects.equals(activeGroupId, that.activeGroupId)
                && groups.equals(that.groups)
                && Objects.equals(createAction, that.createAction)
                && Objects.equals(selectAllAction, that.selectAllAction)
                && Objects.equals(selectNoneAction, that.selectNoneAction)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activeGroupId, groups, createAction,
                selectAllAction, selectNoneAction, metadata);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static Map<String, String> copyMetadata(
            @Nullable Map<String, String> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
