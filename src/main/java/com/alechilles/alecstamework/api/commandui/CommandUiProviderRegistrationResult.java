package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Explicit outcome returned by command UI provider registration. */
public final class CommandUiProviderRegistrationResult {
    /** Stable registration outcomes for integrations and diagnostics. */
    public enum Status {
        /** A new provider generation is live. */
        REGISTERED,
        /** Another live generation already owns the identifier. */
        CONFLICT,
        /** The identifier or provider input is invalid. */
        INVALID,
        /** The API implementation does not host provider registration. */
        UNAVAILABLE
    }

    private final Status status;
    @Nullable
    private final CommandUiProviderRegistration registration;
    @Nullable
    private final CommandUiProviderId providerId;
    private final String message;

    public CommandUiProviderRegistrationResult(
            @Nonnull Status status,
            @Nullable CommandUiProviderRegistration registration,
            @Nullable CommandUiProviderId providerId,
            @Nullable String message
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.registration = registration;
        this.providerId = providerId;
        this.message = message == null ? "" : message.trim();
    }

    @Nonnull
    public static CommandUiProviderRegistrationResult registered(
            @Nonnull CommandUiProviderId providerId,
            @Nonnull CommandUiProviderRegistration registration
    ) {
        return new CommandUiProviderRegistrationResult(
                Status.REGISTERED,
                Objects.requireNonNull(registration, "registration"),
                Objects.requireNonNull(providerId, "providerId"),
                "registered"
        );
    }

    @Nonnull
    public static CommandUiProviderRegistrationResult conflict(
            @Nonnull CommandUiProviderId providerId
    ) {
        return new CommandUiProviderRegistrationResult(
                Status.CONFLICT,
                null,
                Objects.requireNonNull(providerId, "providerId"),
                "provider ID is already registered"
        );
    }

    @Nonnull
    public static CommandUiProviderRegistrationResult invalid(
            @Nullable String rawProviderId,
            @Nonnull String message
    ) {
        return new CommandUiProviderRegistrationResult(
                Status.INVALID,
                null,
                CommandUiProviderId.tryParse(rawProviderId).orElse(null),
                message
        );
    }

    @Nonnull
    public static CommandUiProviderRegistrationResult unavailable(
            @Nullable String rawProviderId
    ) {
        return new CommandUiProviderRegistrationResult(
                Status.UNAVAILABLE,
                null,
                CommandUiProviderId.tryParse(rawProviderId).orElse(null),
                "command UI provider registration is unavailable"
        );
    }

    @Nonnull
    public Status status() {
        return status;
    }

    /** Returns the exact generation handle for a successful result, or null otherwise. */
    @Nullable
    public CommandUiProviderRegistration registration() {
        return registration;
    }

    /** Alias for code that treats the handle as a close handle. */
    @Nullable
    public CommandUiProviderRegistration handle() {
        return registration;
    }

    @Nullable
    public CommandUiProviderId providerId() {
        return providerId;
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
}
