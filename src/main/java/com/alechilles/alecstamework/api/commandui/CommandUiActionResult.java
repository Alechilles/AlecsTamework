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

    public CommandUiActionResult(
            @Nonnull CommandUiActionStatus status,
            @Nullable String message,
            @Nullable CommandUiActionHandle confirmationHandle,
            @Nullable CommandUiActionView confirmationView,
            @Nullable Map<String, String> metadata
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.message = message == null ? "" : message.trim();
        this.confirmationHandle = confirmationHandle;
        this.confirmationView = confirmationView;
        this.metadata = copyMetadata(metadata);
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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiActionResult that)) return false;
        return status == that.status
                && message.equals(that.message)
                && Objects.equals(confirmationHandle, that.confirmationHandle)
                && Objects.equals(confirmationView, that.confirmationView)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, message, confirmationHandle,
                confirmationView, metadata);
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
