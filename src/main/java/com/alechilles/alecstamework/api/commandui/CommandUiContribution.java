package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable page and companion-row data contributed by one command UI plugin. */
public final class CommandUiContribution {
    /** Initial and refresh status for one contributor namespace. */
    public enum Status {
        READY,
        OPTIONAL_UNAVAILABLE,
        OPTIONAL_FAILED,
        REQUIRED_UNAVAILABLE,
        REQUIRED_FAILED,
        UNSUPPORTED_BY_RENDERER
    }

    private final CommandUiContributorId contributorId;
    private final Map<String, CommandUiValue> pageData;
    private final Map<UUID, Map<String, CommandUiValue>> rowData;
    private final Map<String, CommandUiActionView> pageActions;
    private final Map<String, CommandUiActionView> commandActions;
    private final Map<UUID, Map<String, CommandUiActionView>> rowActions;
    private final Map<String, CommandUiActionView> flowActions;
    private final Status status;
    private final String diagnosticReason;

    /** Creates an empty ready contribution. */
    public CommandUiContribution(@Nonnull CommandUiContributorId contributorId) {
        this(contributorId, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Status.READY, null);
    }

    /** Creates a ready contribution with immutable page and row data. */
    public CommandUiContribution(
            @Nonnull CommandUiContributorId contributorId,
            @Nullable Map<String, CommandUiValue> pageData,
            @Nullable Map<UUID, Map<String, CommandUiValue>> rowData
    ) {
        this(contributorId, pageData, rowData, Map.of(), Map.of(), Map.of(), Map.of(),
                Status.READY, null);
    }

    /** Creates a contribution with an explicit lifecycle status. */
    public CommandUiContribution(
            @Nonnull CommandUiContributorId contributorId,
            @Nullable Map<String, CommandUiValue> pageData,
            @Nullable Map<UUID, Map<String, CommandUiValue>> rowData,
            @Nullable Map<String, CommandUiActionView> pageActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            @Nullable Map<UUID, Map<String, CommandUiActionView>> rowActions,
            @Nullable Map<String, CommandUiActionView> flowActions,
            @Nonnull Status status,
            @Nullable String diagnosticReason
    ) {
        this.contributorId = Objects.requireNonNull(contributorId, "contributorId");
        this.pageData = copyPageData(pageData);
        this.rowData = copyRowData(rowData);
        this.pageActions = copyActions(pageActions);
        this.commandActions = copyActions(commandActions);
        this.rowActions = copyRowActions(rowActions);
        this.flowActions = copyActions(flowActions);
        this.status = Objects.requireNonNull(status, "status");
        this.diagnosticReason = diagnosticReason == null ? "" : diagnosticReason.trim();
    }

    /** Creates a contribution with detached actions and ready status. */
    public CommandUiContribution(
            @Nonnull CommandUiContributorId contributorId,
            @Nullable Map<String, CommandUiValue> pageData,
            @Nullable Map<UUID, Map<String, CommandUiValue>> rowData,
            @Nullable Map<String, CommandUiActionView> pageActions,
            @Nullable Map<String, CommandUiActionView> commandActions,
            @Nullable Map<UUID, Map<String, CommandUiActionView>> rowActions,
            @Nullable Map<String, CommandUiActionView> flowActions
    ) {
        this(contributorId, pageData, rowData, pageActions, commandActions,
                rowActions, flowActions, Status.READY, null);
    }

    @Nonnull
    public static CommandUiContribution ready(
            @Nonnull CommandUiContributorId contributorId,
            @Nullable Map<String, CommandUiValue> pageData,
            @Nullable Map<UUID, Map<String, CommandUiValue>> rowData
    ) {
        return new CommandUiContribution(contributorId, pageData, rowData);
    }

    @Nonnull
    public CommandUiContributorId contributorId() {
        return contributorId;
    }

    @Nonnull
    public CommandUiContributorId id() {
        return contributorId;
    }

    @Nonnull
    public Map<String, CommandUiValue> pageData() {
        return pageData;
    }

    @Nullable
    public CommandUiValue pageValue(@Nullable String key) {
        return key == null ? null : pageData.get(key);
    }

    @Nonnull
    public Map<UUID, Map<String, CommandUiValue>> rowData() {
        return rowData;
    }

    @Nonnull
    public Map<String, CommandUiValue> rowData(@Nonnull UUID rowId) {
        return rowData.getOrDefault(Objects.requireNonNull(rowId, "rowId"), Map.of());
    }

    @Nullable
    public CommandUiValue rowValue(@Nonnull UUID rowId, @Nullable String key) {
        return key == null ? null : rowData(rowId).get(key);
    }

    /** Returns detached page-level actions keyed by contributor-local ID. */
    @Nonnull
    public Map<String, CommandUiActionView> pageActions() {
        return pageActions;
    }

    /** Returns detached command-level actions keyed by contributor-local ID. */
    @Nonnull
    public Map<String, CommandUiActionView> commandActions() {
        return commandActions;
    }

    /** Returns detached row-level actions keyed by row and contributor-local ID. */
    @Nonnull
    public Map<UUID, Map<String, CommandUiActionView>> rowActions() {
        return rowActions;
    }

    /** Returns detached actions for one companion row. */
    @Nonnull
    public Map<String, CommandUiActionView> rowActions(@Nonnull UUID rowId) {
        return rowActions.getOrDefault(Objects.requireNonNull(rowId, "rowId"), Map.of());
    }

    /** Returns detached flow-level actions keyed by contributor-local ID. */
    @Nonnull
    public Map<String, CommandUiActionView> flowActions() {
        return flowActions;
    }

    @Nonnull
    public Status status() {
        return status;
    }

    @Nonnull
    public String diagnosticReason() {
        return diagnosticReason;
    }

    /** Alias for callers that use the shorter reason wording. */
    @Nonnull
    public String reason() {
        return diagnosticReason;
    }

    private static Map<String, CommandUiValue> copyPageData(
            @Nullable Map<String, CommandUiValue> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, CommandUiValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CommandUiValue> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "page data key"),
                    Objects.requireNonNull(entry.getValue(), "page data value")
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<UUID, Map<String, CommandUiValue>> copyRowData(
            @Nullable Map<UUID, Map<String, CommandUiValue>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, Map<String, CommandUiValue>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, CommandUiValue>> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "row ID"),
                    copyPageData(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, CommandUiActionView> copyActions(
            @Nullable Map<String, CommandUiActionView> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, CommandUiActionView> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CommandUiActionView> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "action ID"),
                    Objects.requireNonNull(entry.getValue(), "action view")
            );
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<UUID, Map<String, CommandUiActionView>> copyRowActions(
            @Nullable Map<UUID, Map<String, CommandUiActionView>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<UUID, Map<String, CommandUiActionView>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, CommandUiActionView>> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "row ID"),
                    copyActions(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(copy);
    }
}
