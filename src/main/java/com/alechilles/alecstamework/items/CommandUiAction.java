package com.alechilles.alecstamework.items;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tamework-owned action binding kept behind the public opaque-handle API. */
final class CommandUiAction {
    enum Kind {
        SELECT_COMMAND,
        ASSIGN_HOTSWAP,
        ASSIGN_GROUP,
        MANAGE_GROUPS,
        SET_FILTER_TEXT,
        CREATE_GROUP,
        RENAME_GROUP,
        RECOLOR_GROUP,
        DELETE_GROUP,
        SELECT_ACTIVE_GROUP,
        LINK,
        UNLINK,
        TOGGLE_ACTIVE,
        TOGGLE_BREEDING,
        RELEASE,
        CULL,
        RESPAWN,
        LOCATE,
        RECALL,
        SET_HOME,
        RETURN_HOME,
        SUMMON,
        DISMISS,
        REVIVE,
        ABANDON,
        TOGGLE_FLIGHT,
        TOGGLE_SHOULDER_RIDE,
        PANEL_PREFERENCE,
        OTHER
    }

    private final String kind;
    @Nullable
    private final UUID targetId;
    @Nullable
    private final String value;
    private final boolean confirmationRequired;

    CommandUiAction(@Nonnull String kind) {
        this(kind, null, null, false);
    }

    CommandUiAction(@Nonnull String kind, @Nullable UUID targetId) {
        this(kind, targetId, null, false);
    }

    CommandUiAction(
            @Nonnull String kind,
            @Nullable UUID targetId,
            @Nullable String value,
            boolean confirmationRequired
    ) {
        this.kind = requireKind(kind);
        this.targetId = targetId;
        this.value = normalize(value);
        this.confirmationRequired = confirmationRequired;
    }

    @Nonnull
    static CommandUiAction of(@Nonnull String kind) {
        return new CommandUiAction(kind);
    }

    @Nonnull
    String kind() {
        return kind;
    }

    @Nullable
    UUID targetId() {
        return targetId;
    }

    @Nullable
    String value() {
        return value;
    }

    boolean confirmationRequired() {
        return confirmationRequired;
    }

    @Nonnull
    Kind builtInKind() {
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException ignored) {
            return Kind.OTHER;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiAction that)) return false;
        return confirmationRequired == that.confirmationRequired
                && kind.equals(that.kind)
                && Objects.equals(targetId, that.targetId)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, targetId, value, confirmationRequired);
    }

    @Nonnull
    private static String requireKind(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Command UI action kind is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
