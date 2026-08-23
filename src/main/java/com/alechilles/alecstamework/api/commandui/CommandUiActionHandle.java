package com.alechilles.alecstamework.api.commandui;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Opaque, session-bound reference to one Tamework-defined action.
 *
 * <p>The token is only a lookup key. Route, target, provider generation, and
 * authority generation are held by Tamework and are never exposed by this
 * type. A token copied from another session therefore has no authority in the
 * current session.</p>
 */
public final class CommandUiActionHandle {
    private final String token;

    /**
     * Creates an opaque token wrapper. Providers may hold or forward a handle,
     * but a token is useful only when Tamework issued it for that session.
     */
    public CommandUiActionHandle(@Nonnull String token) {
        this.token = requireToken(token);
    }

    /** Returns the opaque event token. It contains no route or authority data. */
    @Nonnull
    public String token() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CommandUiActionHandle that
                && token.equals(that.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }

    /** Does not print the token so logs cannot accidentally disclose handles. */
    @Override
    public String toString() {
        return "CommandUiActionHandle[opaque]";
    }

    @Nonnull
    private static String requireToken(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Command UI action token is required.");
        }
        return normalized;
    }
}
