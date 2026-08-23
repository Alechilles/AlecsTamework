package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached presentation of one command choice in a command UI snapshot. */
public final class CommandUiCommandOption {
    private final String commandId;
    private final String label;
    @Nullable
    private final String localizationSource;
    @Nullable
    private final String iconAssetId;
    private final boolean radialVisible;
    private final boolean selected;
    @Nullable
    private final CommandUiActionView action;

    /** Creates a visible, unselected command option without an action handle. */
    public CommandUiCommandOption(
            @Nonnull String commandId,
            @Nonnull String label
    ) {
        this(commandId, label, null, null, true, false,
                (CommandUiActionView) null);
    }

    /** Creates a command option with its selected state. */
    public CommandUiCommandOption(
            @Nonnull String commandId,
            @Nonnull String label,
            boolean selected
    ) {
        this(commandId, label, null, null, true, selected,
                (CommandUiActionView) null);
    }

    /** Full detached command option constructor. */
    public CommandUiCommandOption(
            @Nonnull String commandId,
            @Nonnull String label,
            @Nullable String localizationSource,
            @Nullable String iconAssetId,
            boolean radialVisible,
            boolean selected,
            @Nullable CommandUiActionView action
    ) {
        this.commandId = requireText(commandId, "commandId");
        this.label = requireText(label, "label");
        this.localizationSource = normalize(localizationSource);
        this.iconAssetId = normalize(iconAssetId);
        this.radialVisible = radialVisible;
        this.selected = selected;
        this.action = action;
    }

    @Nonnull
    public String commandId() {
        return commandId;
    }

    @Nonnull
    public String label() {
        return label;
    }

    @Nullable
    public String localizationSource() {
        return localizationSource;
    }

    @Nullable
    public String iconAssetId() {
        return iconAssetId;
    }

    @Nullable
    public String icon() {
        return iconAssetId;
    }

    public boolean radialVisible() {
        return radialVisible;
    }

    public boolean selected() {
        return selected;
    }

    @Nullable
    public CommandUiActionView action() {
        return action;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiCommandOption that)) return false;
        return radialVisible == that.radialVisible
                && selected == that.selected
                && commandId.equals(that.commandId)
                && label.equals(that.label)
                && Objects.equals(localizationSource, that.localizationSource)
                && Objects.equals(iconAssetId, that.iconAssetId)
                && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commandId, label, localizationSource, iconAssetId,
                radialVisible, selected, action);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
