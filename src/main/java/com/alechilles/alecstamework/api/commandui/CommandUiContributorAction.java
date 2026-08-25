package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable definition of one contributor-owned command UI action.
 *
 * <p>The definition contains detached display state and a server-side handler.
 * Tamework keeps the handler in its action binding and exposes only the
 * detached view and an opaque handle to a renderer.</p>
 */
public final class CommandUiContributorAction {
    private static final Pattern LOCAL_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_./-]*");

    /** Scope in which the contributor action is presented. */
    public enum Scope {
        PAGE,
        COMMAND,
        ROW,
        FLOW
    }

    /** Input accepted by the action handler. */
    public enum InputPolicy {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    private final String localId;
    private final String kind;
    private final String label;
    @Nullable
    private final String iconAssetId;
    private final boolean visible;
    private final boolean enabled;
    @Nullable
    private final String disabledReason;
    private final InputPolicy inputPolicy;
    private final boolean confirmationRequired;
    private final Map<String, String> metadata;
    private final CommandUiContributorActionHandler handler;

    /**
     * Creates an immutable contributor action definition.
     *
     * @param localId contributor-local action path
     * @param kind renderer-defined action kind
     * @param label detached display label
     * @param iconAssetId optional detached icon asset ID
     * @param visible whether the action belongs in the detached action surface
     * @param enabled whether the visible action can be invoked
     * @param disabledReason optional reason shown when the action is disabled
     * @param inputPolicy input accepted by the server handler
     * @param confirmationRequired whether Tamework must request confirmation
     * @param metadata immutable detached display metadata
     * @param handler server-side action handler
     */
    public CommandUiContributorAction(
            @Nonnull String localId,
            @Nonnull String kind,
            @Nonnull String label,
            @Nullable String iconAssetId,
            boolean visible,
            boolean enabled,
            @Nullable String disabledReason,
            @Nonnull InputPolicy inputPolicy,
            boolean confirmationRequired,
            @Nullable Map<String, String> metadata,
            @Nonnull CommandUiContributorActionHandler handler
    ) {
        this.localId = normalizeLocalId(localId);
        this.kind = requireText(kind, "kind");
        this.label = requireText(label, "label");
        this.iconAssetId = normalize(iconAssetId);
        this.visible = visible;
        this.enabled = enabled;
        this.disabledReason = normalize(disabledReason);
        this.inputPolicy = Objects.requireNonNull(inputPolicy, "inputPolicy");
        this.confirmationRequired = confirmationRequired;
        this.metadata = copyMetadata(metadata);
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    /** Creates a visible, enabled action with no input or metadata. */
    public CommandUiContributorAction(
            @Nonnull String localId,
            @Nonnull String kind,
            @Nonnull String label,
            @Nonnull InputPolicy inputPolicy,
            boolean confirmationRequired,
            @Nonnull CommandUiContributorActionHandler handler
    ) {
        this(localId, kind, label, null, true, true, null, inputPolicy,
                confirmationRequired, Map.of(), handler);
    }

    @Nonnull
    public String localId() {
        return localId;
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

    public boolean visible() {
        return visible;
    }

    public boolean enabled() {
        return enabled;
    }

    @Nullable
    public String disabledReason() {
        return disabledReason;
    }

    @Nonnull
    public InputPolicy inputPolicy() {
        return inputPolicy;
    }

    public boolean confirmationRequired() {
        return confirmationRequired;
    }

    @Nonnull
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Returns the action handler retained by Tamework's server binding.
     * Renderers receive no action definition and cannot access this handler.
     */
    @Nonnull
    public CommandUiContributorActionHandler handler() {
        return handler;
    }

    /** Returns the effective public action ID for a contributor registration. */
    @Nonnull
    public String effectiveId(@Nonnull CommandUiContributorId contributorId) {
        return Objects.requireNonNull(contributorId, "contributorId").value()
                + "/" + localId;
    }

    /**
     * Creates the detached view that may be sent to a renderer.
     *
     * @param handle opaque Tamework handle, or null before a handle is issued
     * @return detached view, or null when this definition is not visible
     */
    @Nullable
    public CommandUiActionView view(@Nullable CommandUiActionHandle handle) {
        if (!visible) return null;
        return new CommandUiActionView(kind, label, iconAssetId, enabled,
                disabledReason, confirmationRequired, handle, metadata);
    }

    @Nonnull
    private static String normalizeLocalId(@Nullable String rawValue) {
        String normalized = rawValue == null
                ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !LOCAL_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Command UI contributor action ID must be a lowercase path: "
                            + rawValue);
        }
        return normalized;
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

    @Nonnull
    private static Map<String, String> copyMetadata(
            @Nullable Map<String, String> source
    ) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key");
            String value = Objects.requireNonNull(entry.getValue(),
                    "metadata value");
            if (key.isBlank()) {
                throw new IllegalArgumentException("metadata key is required.");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
