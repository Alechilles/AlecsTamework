package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached presentation of one Tamework-defined action. */
public final class CommandUiActionView {
    @Nullable
    private final CommandUiActionHandle handle;
    private final String kind;
    private final String label;
    @Nullable
    private final String iconAssetId;
    private final boolean enabled;
    @Nullable
    private final String disabledReason;
    private final boolean confirmationRequired;
    private final Map<String, String> metadata;

    /** Creates one detached action presentation. */
    public CommandUiActionView(
            @Nonnull String kind,
            @Nonnull String label,
            @Nullable String iconAssetId,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired,
            @Nullable CommandUiActionHandle handle,
            @Nullable Map<String, String> metadata
    ) {
        this.kind = requireText(kind, "kind");
        this.label = requireText(label, "label");
        this.iconAssetId = normalize(iconAssetId);
        this.enabled = enabled;
        this.disabledReason = normalize(disabledReason);
        this.confirmationRequired = confirmationRequired;
        this.handle = enabled ? handle : null;
        this.metadata = copyMetadata(metadata);
    }

    /** Creates one detached action without optional icon or metadata. */
    public CommandUiActionView(
            @Nonnull String kind,
            @Nonnull String label,
            boolean enabled,
            @Nullable String disabledReason,
            boolean confirmationRequired,
            @Nullable CommandUiActionHandle handle
    ) {
        this(kind, label, null, enabled, disabledReason,
                confirmationRequired, handle, Map.of());
    }

    @Nullable
    public CommandUiActionHandle handle() {
        return handle;
    }

    @Nonnull
    public String kind() {
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

    public boolean enabled() {
        return enabled;
    }

    @Nullable
    public String disabledReason() {
        return disabledReason;
    }

    public boolean confirmationRequired() {
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
                && kind.equals(that.kind)
                && label.equals(that.label)
                && Objects.equals(iconAssetId, that.iconAssetId)
                && Objects.equals(disabledReason, that.disabledReason)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handle, kind, label, iconAssetId, enabled,
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
