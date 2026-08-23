package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compact fixed event payload accepted from a provider page.
 *
 * <p>Every field is untrusted input. Tamework treats the action token as a
 * lookup key and never accepts a target or route supplied by the event.</p>
 */
public final class CommandUiEvent {
    private final String eventId;
    @Nullable
    private final String actionToken;
    @Nullable
    private final String value;
    @Nullable
    private final Boolean booleanValue;

    public CommandUiEvent(
            @Nonnull String eventId,
            @Nullable String actionToken
    ) {
        this(eventId, actionToken, null, null);
    }

    public CommandUiEvent(
            @Nonnull String eventId,
            @Nullable String actionToken,
            @Nullable String value
    ) {
        this(eventId, actionToken, value, null);
    }

    public CommandUiEvent(
            @Nonnull String eventId,
            @Nullable String actionToken,
            @Nullable Boolean booleanValue
    ) {
        this(eventId, actionToken, null, booleanValue);
    }

    public CommandUiEvent(
            @Nonnull String eventId,
            @Nullable String actionToken,
            @Nullable String value,
            @Nullable Boolean booleanValue
    ) {
        this.eventId = requireText(eventId, "eventId");
        this.actionToken = normalize(actionToken);
        this.value = normalize(value);
        this.booleanValue = booleanValue;
    }

    @Nonnull
    public static CommandUiEvent action(
            @Nonnull String eventId,
            @Nonnull CommandUiActionHandle handle
    ) {
        return new CommandUiEvent(eventId,
                Objects.requireNonNull(handle, "handle").token());
    }

    @Nonnull
    public String eventId() {
        return eventId;
    }

    @Nullable
    public String actionToken() {
        return actionToken;
    }

    @Nullable
    public String value() {
        return value;
    }

    @Nullable
    public Boolean booleanValue() {
        return booleanValue;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CommandUiEvent that)) return false;
        return eventId.equals(that.eventId)
                && Objects.equals(actionToken, that.actionToken)
                && Objects.equals(value, that.value)
                && Objects.equals(booleanValue, that.booleanValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, actionToken, value, booleanValue);
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
