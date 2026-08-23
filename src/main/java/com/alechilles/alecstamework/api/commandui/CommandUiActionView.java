package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Detached presentation of one Tamework-defined action.
 *
 * <p>An enabled view can carry an opaque handle. Disabled views carry an
 * explicit reason and no usable handle. The metadata is string-only so this
 * record cannot retain a live component, callback, or mutable runtime object.</p>
 */
public final class CommandUiActionView {
    @Nullable
    private final CommandUiActionHandle handle;
    @Nullable
    private final CommandUiAction action;
    private final String kind;
    private final String label;
    @Nullable
    private final String iconAssetId;
    private final boolean enabled;
    @Nullable
    private final String disabledReason;
    private final boolean confirmationRequired;
    private final Map<String, String> metadata;

    /** Creates a minimal enabled or disabled action view. */
    public CommandUiActionView(
            @Nonnull String kind,
            @Nonnull String label,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired,
            @Nullable CommandUiActionHandle handle
    ) {
        this(null, kind, label, null, enabled, disabledReason,
                confirmationRequired, handle, Map.of());
    }

    /** Convenient kind/label/handle order for simple snapshots. */
    public CommandUiActionView(
            @Nonnull String kind,
            @Nonnull String label,
            @Nullable CommandUiActionHandle handle,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired
    ) {
        this(null, kind, label, null, enabled, disabledReason,
                confirmationRequired, handle, Map.of());
    }

    /** Creates a view for a semantic action descriptor. */
    public CommandUiActionView(
            @Nonnull CommandUiAction action,
            @Nonnull String label,
            @Nullable String iconAssetId,
            boolean enabled,
            @Nullable String disabledReason,
            @Nullable CommandUiActionHandle handle,
            @Nullable Map<String, String> metadata
    ) {
        this(action, action.kind(), label, iconAssetId, enabled, disabledReason,
                action.confirmationRequired(), handle, metadata);
    }

    /** Full constructor used by snapshot assemblers. */
    public CommandUiActionView(
            @Nullable CommandUiAction action,
            @Nonnull String kind,
            @Nonnull String label,
            @Nullable String iconAssetId,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired,
            @Nullable CommandUiActionHandle handle,
            @Nullable Map<String, String> metadata
    ) {
        this.action = action;
        this.kind = requireText(kind, "kind");
        this.label = requireText(label, "label");
        this.iconAssetId = normalize(iconAssetId);
        this.enabled = enabled;
        this.disabledReason = normalize(disabledReason);
        this.confirmationRequired = confirmationRequired;
        // A disabled view must not accidentally retain an executable handle.
        this.handle = enabled ? handle : null;
        this.metadata = copyMetadata(metadata);
    }

    /** Constructor in handle-first order for concise action assembly. */
    public CommandUiActionView(
            @Nullable CommandUiActionHandle handle,
            @Nonnull String kind,
            @Nonnull String label,
            @Nullable String iconAssetId,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired,
            @Nullable Map<String, String> metadata
    ) {
        this(null, kind, label, iconAssetId, enabled, disabledReason,
                confirmationRequired, handle, metadata);
    }

    /** Creates a standard enabled action view. */
    @Nonnull
    public static CommandUiActionView enabled(
            @Nonnull CommandUiActionHandle handle,
            @Nonnull String kind,
            @Nonnull String label
    ) {
        return new CommandUiActionView(
                null, kind, label, null, true, null, false, handle, Map.of());
    }

    /** Creates a standard disabled action view. */
    @Nonnull
    public static CommandUiActionView disabled(
            @Nonnull String kind,
            @Nonnull String label,
            @Nonnull String reason
    ) {
        return new CommandUiActionView(
                null, kind, label, null, false, reason, false, null, Map.of());
    }

    @Nullable
    public CommandUiActionHandle handle() {
        return handle;
    }

    /** Alias used by renderers that call this field an action handle. */
    @Nullable
    public CommandUiActionHandle actionHandle() {
        return handle;
    }

    @Nullable
    public CommandUiAction action() {
        return action;
    }

    @Nonnull
    public String kind() {
        return kind;
    }

    @Nonnull
    public String semanticKind() {
        return kind;
    }

    @Nonnull
    public String label() {
        return label;
    }

    @Nullable
    public String iconAssetId() {
        return iconAssetId;
    }

    @Nullable
    public String icon() {
        return iconAssetId;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean available() {
        return enabled && handle != null;
    }

    @Nullable
    public String disabledReason() {
        return disabledReason;
    }

    @Nullable
    public String reason() {
        return disabledReason;
    }

    public boolean confirmationRequired() {
        return confirmationRequired;
    }

    public boolean requiresConfirmation() {
        return confirmationRequired;
    }

    @Nonnull
    public Map<String, String> metadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiActionView that)) return false;
        return enabled == that.enabled
                && confirmationRequired == that.confirmationRequired
                && Objects.equals(handle, that.handle)
                && Objects.equals(action, that.action)
                && kind.equals(that.kind)
                && label.equals(that.label)
                && Objects.equals(iconAssetId, that.iconAssetId)
                && Objects.equals(disabledReason, that.disabledReason)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handle, action, kind, label, iconAssetId, enabled,
                disabledReason, confirmationRequired, metadata);
    }

    @Override
    public String toString() {
        return "CommandUiActionView[kind=" + kind
                + ", label=" + label
                + ", enabled=" + enabled
                + ", confirmationRequired=" + confirmationRequired + "]";
    }

    @Nonnull
    private static Map<String, String> copyMetadata(
            @Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
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
