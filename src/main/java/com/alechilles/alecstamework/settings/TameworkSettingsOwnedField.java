package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.config.overrides.TwConfigFamily;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical field-path matrix for config values controlled by `/tw settings`.
 */
public final class TameworkSettingsOwnedField {
    private static final Set<String> GLOBAL_EXACT_PATHS = Set.of(
            "simpleclaims.simpleclaimsenabled",
            "simpleclaims.damage.protecttamedfromnonmembers",
            "command.deadrespawnenabled"
    );
    private static final Set<String> GLOBAL_PREFIX_PATHS = Set.of(
            "ownershipprotection.",
            "ownershiprequirements.",
            "population.",
            "simpleclaims.breeding."
    );
    private static final Set<String> NEEDS_PREFIX_PATHS = Set.of(
            "tickpolicy.",
            "damage."
    );
    private static final Set<String> BREEDING_EXACT_PATHS = Set.of(
            "passivebreeding.enabled"
    );
    private static final Set<String> SPAWNER_EXACT_PATHS = Set.of(
            "capture.clearsowner",
            "spawn.assignsowner"
    );
    private static final Set<String> COMPANION_EXACT_PATHS = Set.of(
            "command.deadrespawnenabled"
    );
    private static final Set<String> COMPANION_PREFIX_PATHS = Set.of(
            "ownershipprotection."
    );

    private TameworkSettingsOwnedField() {
    }

    public static boolean isSettingsOwned(@Nullable TwConfigFamily family, @Nullable String path) {
        if (family == null) {
            return false;
        }
        String normalized = normalizePath(path);
        if (normalized.isBlank()) {
            return false;
        }
        return switch (family) {
            case GLOBAL -> matches(normalized, GLOBAL_EXACT_PATHS, GLOBAL_PREFIX_PATHS);
            case NEEDS -> matches(normalized, Set.of(), NEEDS_PREFIX_PATHS);
            case BREEDING -> matches(normalized, BREEDING_EXACT_PATHS, Set.of());
            case SPAWNER -> matches(normalized, SPAWNER_EXACT_PATHS, Set.of());
            case COMPANION -> matches(normalized, COMPANION_EXACT_PATHS, COMPANION_PREFIX_PATHS);
            default -> false;
        };
    }

    private static boolean matches(@Nonnull String normalized,
                                   @Nonnull Set<String> exactPaths,
                                   @Nonnull Set<String> prefixPaths) {
        if (exactPaths.contains(normalized)) {
            return true;
        }
        for (String prefix : prefixPaths) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String normalizePath(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
