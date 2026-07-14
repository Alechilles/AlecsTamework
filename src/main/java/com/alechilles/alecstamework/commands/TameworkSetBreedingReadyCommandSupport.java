package com.alechilles.alecstamework.commands;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Parses the optional readiness mode and area-of-effect switch for the debug command. */
final class TameworkSetBreedingReadyCommandSupport {
    static final double DEFAULT_AOE_RADIUS = 10.0;

    private TameworkSetBreedingReadyCommandSupport() {
    }

    @Nonnull
    static Arguments parse(@Nullable String input) {
        String[] tokens = input == null || input.isBlank()
                ? new String[0]
                : input.trim().split("\\s+");
        if (tokens.length < 2 || tokens.length > 5) {
            return Arguments.invalid();
        }
        int index = 2;
        ReadyMode mode = ReadyMode.TRUE;
        if (index < tokens.length && !isAoe(tokens[index])) {
            mode = parseMode(tokens[index++]);
            if (mode == ReadyMode.INVALID) {
                return Arguments.invalid();
            }
        }
        if (index == tokens.length) {
            return new Arguments(mode, false, null, true);
        }
        if (!isAoe(tokens[index++])) {
            return Arguments.invalid();
        }
        if (index == tokens.length) {
            return new Arguments(mode, true, null, true);
        }
        if (index + 1 != tokens.length) {
            return Arguments.invalid();
        }
        try {
            double radius = Double.parseDouble(tokens[index]);
            return Double.isFinite(radius) && radius > 0.0
                    ? new Arguments(mode, true, radius, true)
                    : Arguments.invalid();
        } catch (NumberFormatException ignored) {
            return Arguments.invalid();
        }
    }

    private static boolean isAoe(@Nullable String raw) {
        return raw != null && ("aoe".equalsIgnoreCase(raw) || "--aoe".equalsIgnoreCase(raw));
    }

    @Nonnull
    private static ReadyMode parseMode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return ReadyMode.TRUE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "toggle" -> ReadyMode.TOGGLE;
            case "true", "1", "on", "yes" -> ReadyMode.TRUE;
            case "false", "0", "off", "no" -> ReadyMode.FALSE;
            default -> ReadyMode.INVALID;
        };
    }

    enum ReadyMode {
        TRUE,
        FALSE,
        TOGGLE,
        INVALID
    }

    record Arguments(ReadyMode mode, boolean aoe, @Nullable Double radius, boolean valid) {
        @Nonnull
        static Arguments invalid() {
            return new Arguments(ReadyMode.INVALID, false, null, false);
        }
    }
}
