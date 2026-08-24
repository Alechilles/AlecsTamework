package com.alechilles.alecstamework.api.commandui;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, normalized identifier for a command UI renderer. */
public record CommandUiRendererId(@Nonnull String value) {
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*"
    );
    private static final String RESERVED_NAMESPACE = "tamework:";

    public CommandUiRendererId {
        value = normalizeAndValidate(value);
    }

    /** Parses and validates an identifier, throwing for malformed input. */
    @Nonnull
    public static CommandUiRendererId of(@Nonnull String rawValue) {
        return new CommandUiRendererId(rawValue);
    }

    /** Returns an empty result instead of throwing for malformed input. */
    @Nonnull
    public static Optional<CommandUiRendererId> tryParse(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CommandUiRendererId(rawValue));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Returns the namespace portion before the separator. */
    @Nonnull
    public String namespace() {
        return value.substring(0, value.indexOf(':'));
    }

    /** Returns the renderer name portion after the separator. */
    @Nonnull
    public String name() {
        return value.substring(value.indexOf(':') + 1);
    }

    /** Returns whether this identifier belongs to Tamework's reserved namespace. */
    public boolean reserved() {
        return value.startsWith(RESERVED_NAMESPACE);
    }

    @Nonnull
    private static String normalizeAndValidate(@Nullable String rawValue) {
        String normalized = rawValue == null
                ? ""
                : rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Command UI renderer ID must be nonblank.");
        }
        if (!NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Command UI renderer ID must be a namespaced identifier: " + rawValue
            );
        }
        return normalized;
    }
}
