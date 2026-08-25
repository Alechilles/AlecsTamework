package com.alechilles.alecstamework.api.commandhud;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, normalized identifier for a command HUD renderer. */
public record CommandHudRendererId(@Nonnull String value) {
    private static final Pattern NAMESPACED_ID = Pattern.compile(
            "[a-z0-9][a-z0-9_.-]*:[a-z0-9][a-z0-9_./-]*");

    public CommandHudRendererId {
        value = normalizeAndValidate(value);
    }

    /** Parses and validates a renderer identifier. */
    @Nonnull
    public static CommandHudRendererId of(@Nullable String rawValue) {
        return new CommandHudRendererId(rawValue);
    }

    /** Returns an empty result instead of throwing for malformed input. */
    @Nonnull
    public static Optional<CommandHudRendererId> tryParse(@Nullable String rawValue) {
        try {
            return Optional.of(new CommandHudRendererId(rawValue));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Returns the namespace before the separator. */
    @Nonnull
    public String namespace() {
        return value.substring(0, value.indexOf(':'));
    }

    /** Returns the renderer name after the separator. */
    @Nonnull
    public String name() {
        return value.substring(value.indexOf(':') + 1);
    }

    @Nonnull
    private static String normalizeAndValidate(@Nullable String rawValue) {
        String normalized = rawValue == null
                ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !NAMESPACED_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Command HUD renderer ID must be a namespaced identifier: " + rawValue);
        }
        if (normalized.startsWith("tamework:")) {
            throw new IllegalArgumentException(
                    "The tamework namespace is reserved for command HUD renderers.");
        }
        return normalized;
    }
}
