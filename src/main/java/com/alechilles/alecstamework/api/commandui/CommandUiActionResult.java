package com.alechilles.alecstamework.api.commandui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable result returned by a command UI action invocation. */
public final class CommandUiActionResult {
    private final CommandUiActionStatus status;
    private final String message;
    @Nullable
    private final CommandUiActionHandle confirmationHandle;
    @Nullable
    private final CommandUiActionView confirmationView;
    private final Map<String, String> metadata;
    @Nullable
    private final CommandUiFlowView flowView;
    @Nullable
    private final CommandUiFlowOperation flowOperation;
    private final boolean refreshSnapshot;

    public CommandUiActionResult(
            @Nonnull CommandUiActionStatus status,
            @Nullable String message,
            @Nullable CommandUiActionHandle confirmationHandle,
            @Nullable CommandUiActionView confirmationView,
            @Nullable Map<String, String> metadata
    ) {
        this(status, message, confirmationHandle, confirmationView, metadata,
                null, null, defaultRefresh(status));
    }

    /** Full result constructor for managed command UI flows. */
    public CommandUiActionResult(
            @Nonnull CommandUiActionStatus status,
            @Nullable String message,
            @Nullable CommandUiActionHandle confirmationHandle,
            @Nullable CommandUiActionView confirmationView,
            @Nullable Map<String, String> metadata,
            @Nullable CommandUiFlowView flowView,
            boolean refreshSnapshot
    ) {
        this(status, message, confirmationHandle, confirmationView, metadata,
                flowView, defaultFlowOperation(status, flowView), refreshSnapshot);
    }

    /** Full result constructor with an explicit managed-flow operation. */
    public CommandUiActionResult(
            @Nonnull CommandUiActionStatus status,
            @Nullable String message,
            @Nullable CommandUiActionHandle confirmationHandle,
            @Nullable CommandUiActionView confirmationView,
            @Nullable Map<String, String> metadata,
            @Nullable CommandUiFlowView flowView,
            @Nullable CommandUiFlowOperation flowOperation,
            boolean refreshSnapshot
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.message = message == null ? "" : message.trim();
        this.confirmationHandle = confirmationHandle;
        this.confirmationView = confirmationView;
        this.metadata = copyMetadata(metadata);
        this.flowView = flowView;
        if (flowOperation != null && !successfulFlowStatus(status)) {
            throw new IllegalArgumentException(
                    "A flow operation requires an applied or accepted result.");
        }
        if (flowOperation == null && flowView != null) {
            throw new IllegalArgumentException(
                    "A flow view requires a flow operation.");
        }
        if (flowOperation == CommandUiFlowOperation.CLOSE && flowView != null) {
            throw new IllegalArgumentException("A closed flow cannot carry a flow view.");
        }
        if (flowOperation != null && flowOperation != CommandUiFlowOperation.CLOSE
                && flowView == null) {
            throw new IllegalArgumentException(
                    "A non-close flow operation requires a flow view.");
        }
        this.flowOperation = flowOperation;
        this.refreshSnapshot = refreshSnapshot;
    }

    public CommandUiActionResult(@Nonnull CommandUiActionStatus status) {
        this(status, null, null, null, Map.of());
    }

    @Nonnull
    public static CommandUiActionResult of(@Nonnull CommandUiActionStatus status) {
        return new CommandUiActionResult(status);
    }

    @Nonnull
    public static CommandUiActionResult applied() {
        return new CommandUiActionResult(CommandUiActionStatus.APPLIED);
    }

    @Nonnull
    public static CommandUiActionResult applied(@Nullable String message) {
        return new CommandUiActionResult(CommandUiActionStatus.APPLIED,
                message, null, null, Map.of());
    }

    /** Returns a truthful outcome for a dispatched legacy void callback. */
    @Nonnull
    public static CommandUiActionResult accepted() {
        return new CommandUiActionResult(CommandUiActionStatus.ACCEPTED);
    }

    /** Returns a detached managed flow without requesting a main snapshot refresh. */
    @Nonnull
    public static CommandUiActionResult presented(
            @Nonnull CommandUiFlowView flowView
    ) {
        return flowResult(CommandUiActionStatus.ACCEPTED, null,
                Objects.requireNonNull(flowView, "flowView"),
                CommandUiFlowOperation.OPEN, false);
    }

    /** Returns an updated managed flow and requests a main snapshot refresh. */
    @Nonnull
    public static CommandUiActionResult updated(
            @Nullable String message,
            @Nonnull CommandUiFlowView flowView
    ) {
        return flowResult(CommandUiActionStatus.APPLIED, message,
                Objects.requireNonNull(flowView, "flowView"),
                CommandUiFlowOperation.UPDATE, true);
    }

    /** Opens a custom flow from the retained main snapshot. */
    @Nonnull
    public static CommandUiActionResult openFlow(
            @Nonnull CommandUiCustomFlowView flowView
    ) {
        return flowResult(CommandUiActionStatus.ACCEPTED, null,
                Objects.requireNonNull(flowView, "flowView"),
                CommandUiFlowOperation.OPEN, false);
    }

    /** Replaces the current custom flow with a new revision. */
    @Nonnull
    public static CommandUiActionResult replaceFlow(
            @Nonnull CommandUiCustomFlowView flowView
    ) {
        return flowResult(CommandUiActionStatus.APPLIED, null,
                Objects.requireNonNull(flowView, "flowView"),
                CommandUiFlowOperation.REPLACE, false);
    }

    /** Updates the current custom flow without replacing its instance. */
    @Nonnull
    public static CommandUiActionResult updateFlow(
            @Nonnull CommandUiCustomFlowView flowView
    ) {
        return flowResult(CommandUiActionStatus.APPLIED, null,
                Objects.requireNonNull(flowView, "flowView"),
                CommandUiFlowOperation.UPDATE, false);
    }

    /** Closes the active custom flow and returns to the retained main page. */
    @Nonnull
    public static CommandUiActionResult closeFlow() {
        return new CommandUiActionResult(CommandUiActionStatus.APPLIED,
                null, null, null, Map.of(), null,
                CommandUiFlowOperation.CLOSE, false);
    }

    @Nonnull
    public static CommandUiActionResult confirmationRequired(
            @Nonnull CommandUiActionHandle handle
    ) {
        return confirmationRequired(handle, null, null);
    }

    @Nonnull
    public static CommandUiActionResult confirmationRequired(
            @Nonnull CommandUiActionHandle handle,
            @Nullable String message,
            @Nullable CommandUiActionView view
    ) {
        return new CommandUiActionResult(
                CommandUiActionStatus.CONFIRMATION_REQUIRED, message, handle,
                view, Map.of());
    }

    @Nonnull
    public static CommandUiActionResult denied(@Nullable String message) {
        return simple(CommandUiActionStatus.DENIED, message);
    }

    @Nonnull
    public static CommandUiActionResult stale(@Nullable String message) {
        return simple(CommandUiActionStatus.STALE, message);
    }

    @Nonnull
    public static CommandUiActionResult notFound(@Nullable String message) {
        return simple(CommandUiActionStatus.NOT_FOUND, message);
    }

    @Nonnull
    public static CommandUiActionResult unavailable(@Nullable String message) {
        return simple(CommandUiActionStatus.UNAVAILABLE, message);
    }

    @Nonnull
    public static CommandUiActionResult conflict(@Nullable String message) {
        return simple(CommandUiActionStatus.CONFLICT, message);
    }

    @Nonnull
    public static CommandUiActionResult failed(@Nullable String message) {
        return simple(CommandUiActionStatus.FAILED, message);
    }

    @Nonnull
    private static CommandUiActionResult simple(
            CommandUiActionStatus status,
            @Nullable String message
    ) {
        return new CommandUiActionResult(status, message, null, null, Map.of());
    }

    @Nonnull
    public CommandUiActionStatus status() {
        return status;
    }

    @Nonnull
    public String message() {
        return message;
    }

    @Nullable
    public CommandUiActionHandle confirmationHandle() {
        return confirmationHandle;
    }

    @Nullable
    public CommandUiActionView confirmationView() {
        return confirmationView;
    }

    @Nonnull
    public Map<String, String> metadata() {
        return metadata;
    }

    /** Returns a detached managed-flow replacement, when this action opened one. */
    @Nullable
    public CommandUiFlowView flowView() {
        return flowView;
    }

    /** Returns the requested managed-flow operation, if one is present. */
    @Nullable
    public CommandUiFlowOperation flowOperation() {
        return flowOperation;
    }

    /** Returns whether the session should request a fresh main snapshot. */
    public boolean refreshSnapshot() {
        return refreshSnapshot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiActionResult that)) return false;
        return status == that.status
                && message.equals(that.message)
                && Objects.equals(confirmationHandle, that.confirmationHandle)
                && Objects.equals(confirmationView, that.confirmationView)
                && metadata.equals(that.metadata)
                && Objects.equals(flowView, that.flowView)
                && flowOperation == that.flowOperation
                && refreshSnapshot == that.refreshSnapshot;
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, message, confirmationHandle,
                confirmationView, metadata, flowView, flowOperation,
                refreshSnapshot);
    }

    private static boolean defaultRefresh(
            @Nonnull CommandUiActionStatus status
    ) {
        return status == CommandUiActionStatus.APPLIED
                || status == CommandUiActionStatus.ACCEPTED;
    }

    @Nullable
    private static CommandUiFlowOperation defaultFlowOperation(
            @Nonnull CommandUiActionStatus status,
            @Nullable CommandUiFlowView flowView
    ) {
        if (flowView == null) return null;
        return switch (status) {
            case ACCEPTED -> CommandUiFlowOperation.OPEN;
            case APPLIED -> CommandUiFlowOperation.UPDATE;
            default -> null;
        };
    }

    private static boolean successfulFlowStatus(
            @Nonnull CommandUiActionStatus status
    ) {
        return status == CommandUiActionStatus.APPLIED
                || status == CommandUiActionStatus.ACCEPTED;
    }

    @Nonnull
    private static CommandUiActionResult flowResult(
            @Nonnull CommandUiActionStatus status,
            @Nullable String message,
            @Nonnull CommandUiFlowView flowView,
            @Nonnull CommandUiFlowOperation operation,
            boolean refreshSnapshot
    ) {
        return new CommandUiActionResult(status, message, null, null, Map.of(),
                flowView, operation, refreshSnapshot);
    }

    private static Map<String, String> copyMetadata(
            @Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
