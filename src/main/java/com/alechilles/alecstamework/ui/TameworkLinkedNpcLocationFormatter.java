package com.alechilles.alecstamework.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Formats linked-companion location values for player-facing UI.
 */
public final class TameworkLinkedNpcLocationFormatter {
    private static final Pattern TRAILING_UUID = Pattern.compile(
            "(?i)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );
    private static final Pattern TRAILING_UUID_WITH_SHORT_ID = Pattern.compile(
            "(?i)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9a-f]{8,}$"
    );

    private TameworkLinkedNpcLocationFormatter() {
    }

    @Nonnull
    public static String formatCoordinates(double x, double y, double z) {
        return formatCoordinate(x) + ", " + formatCoordinate(y) + ", " + formatCoordinate(z);
    }

    @Nonnull
    public static String formatDisplayWorldName(@Nullable String worldName, @Nonnull String unknownWorldLabel) {
        if (worldName == null || worldName.isBlank()) {
            return unknownWorldLabel;
        }
        String trimmed = worldName.trim();
        String base = trimmed;
        boolean instance = false;
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.startsWith("instance-")) {
            instance = true;
            base = base.substring("instance-".length());
        } else if (lower.startsWith("instance_")) {
            instance = true;
            base = base.substring("instance_".length());
        }

        Matcher uuidWithShortId = TRAILING_UUID_WITH_SHORT_ID.matcher(base);
        if (uuidWithShortId.find()) {
            instance = true;
            base = base.substring(0, uuidWithShortId.start());
        } else {
            Matcher uuid = TRAILING_UUID.matcher(base);
            if (uuid.find()) {
                instance = true;
                base = base.substring(0, uuid.start());
            }
        }

        base = trimInstanceSeparators(base);
        if (!instance) {
            return trimmed;
        }
        if (base.isBlank()) {
            base = "Instance";
        }
        return base + " (Instance)";
    }

    @Nonnull
    private static String formatCoordinate(double value) {
        if (!Double.isFinite(value)) {
            return "?";
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Nonnull
    private static String trimInstanceSeparators(@Nonnull String value) {
        String out = value.trim();
        while (out.endsWith("-") || out.endsWith("_") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out;
    }
}
