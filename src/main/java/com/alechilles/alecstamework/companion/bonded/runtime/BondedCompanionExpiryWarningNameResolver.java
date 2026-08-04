package com.alechilles.alecstamework.companion.bonded.runtime;

import javax.annotation.Nullable;

/** Chooses the player-facing name for a bonded companion expiry warning. */
final class BondedCompanionExpiryWarningNameResolver {
    private static final String EMPTY_ROLE = "Empty Role";

    private BondedCompanionExpiryWarningNameResolver() {
    }

    static String resolve(
            @Nullable String durableName,
            @Nullable String liveCustomName,
            @Nullable String roleName
    ) {
        String resolved = first(durableName, liveCustomName);
        if (resolved != null) return resolved;
        resolved = normalize(roleName);
        return EMPTY_ROLE.equalsIgnoreCase(resolved) ? "Companion"
                : resolved == null ? "Companion" : resolved;
    }

    @Nullable
    private static String first(String first, String second) {
        String normalized = normalize(first);
        return normalized == null ? normalize(second) : normalized;
    }

    @Nullable
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
