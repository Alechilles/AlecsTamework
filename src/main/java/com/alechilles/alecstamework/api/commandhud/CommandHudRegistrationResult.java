package com.alechilles.alecstamework.api.commandhud;

import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Stable result returned by command HUD renderer and contributor registration. */
public final class CommandHudRegistrationResult {
    /** Stable registration outcomes for integrations and diagnostics. */
    public enum Status {
        /** A new generation is live. */
        REGISTERED,
        /** Another live generation already owns the identifier on this surface. */
        CONFLICT,
        /** The identifier, descriptor, or provider input is invalid. */
        INVALID,
        /** The API implementation does not host this registration surface. */
        UNAVAILABLE
    }

    private final Status status;
    @Nullable
    private final CommandHudRegistration registration;
    @Nullable
    private final String id;
    private final String message;

    private CommandHudRegistrationResult(
            @Nonnull Status status,
            @Nullable CommandHudRegistration registration,
            @Nullable String id,
            @Nullable String message
    ) {
        this.status = Objects.requireNonNull(status, "status");
        if (status == Status.REGISTERED) {
            CommandHudRegistration liveRegistration = Objects.requireNonNull(
                    registration, "registered results require a registration");
            String registrationId = normalize(liveRegistration.id());
            if (registrationId == null) {
                throw new IllegalArgumentException(
                        "Registered command HUD handles require a nonblank ID.");
            }
            this.registration = liveRegistration;
            this.id = registrationId;
        } else {
            if (registration != null) {
                throw new IllegalArgumentException(
                        "Non-success command HUD results cannot carry a registration.");
            }
            this.registration = null;
            this.id = normalize(id);
        }
        this.message = message == null ? "" : message.trim();
    }

    /** Creates a successful result for an exact-generation handle. */
    @Nonnull
    public static CommandHudRegistrationResult registered(
            @Nonnull CommandHudRegistration registration
    ) {
        return new CommandHudRegistrationResult(
                Status.REGISTERED,
                Objects.requireNonNull(registration, "registration"),
                null,
                "registered");
    }

    /** Creates a conflict result for an already-owned identifier. */
    @Nonnull
    public static CommandHudRegistrationResult conflict(@Nonnull String id) {
        return new CommandHudRegistrationResult(
                Status.CONFLICT, null, Objects.requireNonNull(id, "id"),
                "ID is already registered");
    }

    /** Creates an invalid-input result without retaining a provider. */
    @Nonnull
    public static CommandHudRegistrationResult invalid(
            @Nullable String rawId,
            @Nonnull String message
    ) {
        return new CommandHudRegistrationResult(Status.INVALID, null, rawId, message);
    }

    /** Creates a fail-closed result without retaining a provider. */
    @Nonnull
    public static CommandHudRegistrationResult unavailable(@Nullable String rawId) {
        return new CommandHudRegistrationResult(
                Status.UNAVAILABLE, null, rawId,
                "command HUD registration is unavailable");
    }

    @Nonnull
    public Status status() {
        return status;
    }

    /** Returns the exact generation handle for a successful result, or null otherwise. */
    @Nullable
    public CommandHudRegistration registration() {
        return registration;
    }

    /** Returns the normalized requested or conflicting ID, or null when none was supplied. */
    @Nullable
    public String id() {
        return id;
    }

    /** Returns a stable human-readable status message. */
    @Nonnull
    public String message() {
        return message;
    }

    /** Returns whether this result created a live generation. */
    public boolean registered() {
        return status == Status.REGISTERED && registration != null;
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
