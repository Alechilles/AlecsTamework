package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached presentation and issued actions for one command group. */
public final class CommandUiGroupView {
    private final String groupId;
    private final String name;
    private final String colorHex;
    private final boolean active;
    @Nullable private final CommandUiActionView renameAction;
    @Nullable private final CommandUiActionView recolorAction;
    @Nullable private final CommandUiActionView deleteAction;
    @Nullable private final CommandUiActionView selectAction;

    public CommandUiGroupView(
            @Nonnull String groupId,
            @Nonnull String name,
            @Nonnull String colorHex,
            boolean active,
            @Nullable CommandUiActionView renameAction,
            @Nullable CommandUiActionView recolorAction,
            @Nullable CommandUiActionView deleteAction,
            @Nullable CommandUiActionView selectAction
    ) {
        this.groupId = requireText(groupId, "groupId");
        this.name = requireText(name, "name");
        this.colorHex = requireText(colorHex, "colorHex");
        this.active = active;
        this.renameAction = renameAction;
        this.recolorAction = recolorAction;
        this.deleteAction = deleteAction;
        this.selectAction = selectAction;
    }

    @Nonnull public String groupId() { return groupId; }
    @Nonnull public String name() { return name; }
    @Nonnull public String colorHex() { return colorHex; }
    public boolean active() { return active; }
    @Nullable public CommandUiActionView renameAction() { return renameAction; }
    @Nullable public CommandUiActionView recolorAction() { return recolorAction; }
    @Nullable public CommandUiActionView deleteAction() { return deleteAction; }
    @Nullable public CommandUiActionView selectAction() { return selectAction; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiGroupView that)) return false;
        return active == that.active
                && groupId.equals(that.groupId)
                && name.equals(that.name)
                && colorHex.equals(that.colorHex)
                && Objects.equals(renameAction, that.renameAction)
                && Objects.equals(recolorAction, that.recolorAction)
                && Objects.equals(deleteAction, that.deleteAction)
                && Objects.equals(selectAction, that.selectAction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, name, colorHex, active, renameAction,
                recolorAction, deleteAction, selectAction);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }
}
