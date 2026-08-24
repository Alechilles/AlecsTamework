package com.alechilles.alecstamework.api.commandui;

import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Shared result returned by command UI renderer and contributor registration. */
public final class CommandUiRegistrationResult {
    /** Stable registration outcomes for integrations and diagnostics. */
    public enum Status {
        /** A new renderer or contributor generation is live. */
        REGISTERED,
        /** Another live generation already owns the identifier. */
        CONFLICT,
        /** The identifier or provider input is invalid. */
        INVALID,
        /** The API implementation does not host the registration surface. */
        UNAVAILABLE
    }

    private final Status status;
    @Nullable
    private final CommandUiRegistration registration;
    @Nullable
    private final String id;
    private final String message;

    public CommandUiRegistrationResult(
            @Nonnull Status status,
            @Nullable CommandUiRegistration registration,
            @Nullable String id,
            @Nullable String message
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.registration = registration;
        this.id = normalize(id);
        this.message = message == null ? "" : message.trim();
    }

    @Nonnull
    public static CommandUiRegistrationResult registered(
            @Nonnull String id,
            @Nonnull CommandUiRegistration registration
    ) {
        return new CommandUiRegistrationResult(
                Status.REGISTERED,
                Objects.requireNonNull(registration, "registration"),
                Objects.requireNonNull(id, "id"),
                "registered"
        );
    }

    @Nonnull
    public static CommandUiRegistrationResult conflict(@Nonnull String id) {
        return new CommandUiRegistrationResult(
                Status.CONFLICT,
                null,
                Objects.requireNonNull(id, "id"),
                "ID is already registered"
        );
    }

    @Nonnull
    public static CommandUiRegistrationResult invalid(
            @Nullable String rawId,
            @Nonnull String message
    ) {
        return new CommandUiRegistrationResult(Status.INVALID, null, rawId, message);
    }

    @Nonnull
    public static CommandUiRegistrationResult unavailable(@Nullable String rawId) {
        return new CommandUiRegistrationResult(
                Status.UNAVAILABLE,
                null,
                rawId,
                "command UI registration is unavailable"
        );
    }

    @Nonnull
    public Status status() {
        return status;
    }

    /** Returns the exact generation handle for a successful result, or null otherwise. */
    @Nullable
    public CommandUiRegistration registration() {
        return registration;
    }

    /** Alias for code that treats the handle as a close handle. */
    @Nullable
    public CommandUiRegistration handle() {
        return registration;
    }

    @Nullable
    public String id() {
        return id;
    }

    @Nonnull
    public String message() {
        return message;
    }

    public boolean registered() {
        return status == Status.REGISTERED && registration != null;
    }

    public boolean accepted() {
        return registered();
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
