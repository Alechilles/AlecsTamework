package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable request to invoke one Tamework-issued command UI action. */
public final class CommandUiActionRequest {
    private final CommandUiActionHandle handle;
    @Nullable
    private final CommandUiValue input;

    /** Creates a request with an optional action-specific text value. */
    public CommandUiActionRequest(
            @Nonnull CommandUiActionHandle handle,
            @Nullable String textInput
    ) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.input = textInput == null ? null : CommandUiValue.string(textInput);
    }

    private CommandUiActionRequest(
            @Nonnull CommandUiActionHandle handle,
            @Nullable CommandUiValue input
    ) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.input = input;
    }

    /** Creates a handle-only request. */
    @Nonnull
    public static CommandUiActionRequest of(
            @Nonnull CommandUiActionHandle handle
    ) {
        return new CommandUiActionRequest(handle, (String) null);
    }

    /** Creates a request with a detached, typed action input value. */
    @Nonnull
    public static CommandUiActionRequest withInput(
            @Nonnull CommandUiActionHandle handle,
            @Nonnull CommandUiValue input
    ) {
        return new CommandUiActionRequest(handle,
                Objects.requireNonNull(input, "input"));
    }

    @Nonnull
    public CommandUiActionHandle handle() {
        return handle;
    }

    @Nullable
    public String textInput() {
        return input == null || input.type() != CommandUiValue.Type.STRING
                ? null : input.stringValue();
    }

    /** Returns the detached typed input, or null when no input was supplied. */
    @Nullable
    public CommandUiValue input() {
        return input;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CommandUiActionRequest that
                && handle.equals(that.handle)
                && Objects.equals(input, that.input);
    }

    @Override
    public int hashCode() {
        return Objects.hash(handle, input);
    }

    @Override
    public String toString() {
        return "CommandUiActionRequest[handle=opaque, hasInput="
                + (input != null) + "]";
    }
}
