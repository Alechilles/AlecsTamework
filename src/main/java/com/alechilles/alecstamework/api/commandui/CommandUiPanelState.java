package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Detached presentation state for the command panel controls. */
public final class CommandUiPanelState {
    @Nullable
    private final String mode;
    private final boolean autoLinkEnabled;
    private final boolean activeHighlightEnabled;
    private final double radius;
    @Nullable
    private final String radiusLabel;
    @Nullable
    private final String sort;
    @Nullable
    private final String filterMode;
    @Nullable
    private final String filterInput;
    @Nullable
    private final String emptyStateKey;
    private final Map<String, CommandUiActionView> actions;
    private final Map<String, String> values;

    /** Creates an empty panel state with a mode label. */
    public CommandUiPanelState(@Nullable String mode) {
        this(mode, false, false, 0.0, null, null, null, null, null,
                Map.of(), Map.of());
    }

    /** Creates the common preference state without actions. */
    public CommandUiPanelState(
            @Nullable String mode,
            boolean autoLinkEnabled,
            @Nullable String radiusLabel,
            @Nullable String sort,
            @Nullable String filterMode,
            @Nullable String filterInput,
            @Nullable String emptyStateKey
    ) {
        this(mode, autoLinkEnabled, false, 0.0, radiusLabel, sort, filterMode,
                filterInput, emptyStateKey, Map.of(), Map.of());
    }

    /** Full detached panel-state constructor. */
    public CommandUiPanelState(
            @Nullable String mode,
            boolean autoLinkEnabled,
            boolean activeHighlightEnabled,
            double radius,
            @Nullable String radiusLabel,
            @Nullable String sort,
            @Nullable String filterMode,
            @Nullable String filterInput,
            @Nullable String emptyStateKey,
            @Nullable Map<String, CommandUiActionView> actions,
            @Nullable Map<String, String> values
    ) {
        if (!Double.isFinite(radius) || radius < 0.0) {
            throw new IllegalArgumentException("Panel radius must be finite and non-negative.");
        }
        this.mode = normalize(mode);
        this.autoLinkEnabled = autoLinkEnabled;
        this.activeHighlightEnabled = activeHighlightEnabled;
        this.radius = radius;
        this.radiusLabel = normalize(radiusLabel);
        this.sort = normalize(sort);
        this.filterMode = normalize(filterMode);
        this.filterInput = normalize(filterInput);
        this.emptyStateKey = normalize(emptyStateKey);
        this.actions = copyActions(actions);
        this.values = copyValues(values);
    }

    @Nullable
    public String mode() {
        return mode;
    }

    @Nullable
    public String panelMode() {
        return mode;
    }

    public boolean autoLinkEnabled() {
        return autoLinkEnabled;
    }

    public boolean autoLink() {
        return autoLinkEnabled;
    }

    public boolean activeHighlightEnabled() {
        return activeHighlightEnabled;
    }

    public double radius() {
        return radius;
    }

    @Nullable
    public String radiusLabel() {
        return radiusLabel;
    }

    @Nullable
    public String sort() {
        return sort;
    }

    @Nullable
    public String sortValue() {
        return sort;
    }

    @Nullable
    public String filterMode() {
        return filterMode;
    }

    @Nullable
    public String filterModeValue() {
        return filterMode;
    }

    @Nullable
    public String filterInput() {
        return filterInput;
    }

    @Nullable
    public String filterText() {
        return filterInput;
    }

    @Nullable
    public String emptyStateKey() {
        return emptyStateKey;
    }

    @Nonnull
    public Map<String, CommandUiActionView> actions() {
        return actions;
    }

    @Nonnull
    public Map<String, String> values() {
        return values;
    }

    @Nullable
    public CommandUiActionView action(@Nullable String kind) {
        return kind == null ? null : actions.get(kind);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiPanelState that)) return false;
        return autoLinkEnabled == that.autoLinkEnabled
                && activeHighlightEnabled == that.activeHighlightEnabled
                && Double.compare(radius, that.radius) == 0
                && Objects.equals(mode, that.mode)
                && Objects.equals(radiusLabel, that.radiusLabel)
                && Objects.equals(sort, that.sort)
                && Objects.equals(filterMode, that.filterMode)
                && Objects.equals(filterInput, that.filterInput)
                && Objects.equals(emptyStateKey, that.emptyStateKey)
                && actions.equals(that.actions)
                && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, autoLinkEnabled, activeHighlightEnabled,
                radius, radiusLabel, sort, filterMode, filterInput,
                emptyStateKey, actions, values);
    }

    @Nonnull
    private static Map<String, CommandUiActionView> copyActions(
            @Nullable Map<String, CommandUiActionView> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, CommandUiActionView> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nonnull
    private static Map<String, String> copyValues(@Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
