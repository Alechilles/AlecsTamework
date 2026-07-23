package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import javax.annotation.Nonnull;

/**
 * Stable compound key for one profile-scoped extension value.
 *
 * @param profileId stable companion profile
 * @param namespace integration namespace
 * @param dataKey integration-owned key
 */
public record ProfileExtensionKey(@Nonnull ProfileId profileId,
                                  @Nonnull String namespace,
                                  @Nonnull String dataKey) {
    public ProfileExtensionKey {
        if (profileId == null) {
            throw new IllegalArgumentException("Extension profile ID is required");
        }
        namespace = requireText(namespace, "Extension namespace", 128);
        dataKey = requireText(dataKey, "Extension data key", 256);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
