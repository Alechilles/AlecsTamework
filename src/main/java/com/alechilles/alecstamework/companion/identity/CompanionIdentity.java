package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable stable profile row; lifecycle and runtime aliases intentionally live elsewhere.
 *
 * @param profileId stable companion identity
 * @param displayName optional display name
 * @param roleId optional role asset identifier
 * @param metadataJson optional canonical profile metadata
 * @param metadataHash SHA-256 of metadata JSON when present
 * @param lastKnownWorldKey non-authoritative reconciliation hint
 * @param createdAtMs signed persisted creation time
 * @param updatedAtMs signed persisted update time
 * @param lastActiveAtMs signed persisted last-active time
 * @param metadataRevision optimistic metadata revision
 */
public record CompanionIdentity(@Nonnull ProfileId profileId,
                                @Nullable String displayName,
                                @Nullable String roleId,
                                @Nullable String metadataJson,
                                @Nullable Sha256Hash metadataHash,
                                @Nullable String lastKnownWorldKey,
                                long createdAtMs,
                                long updatedAtMs,
                                long lastActiveAtMs,
                                long metadataRevision) {
    public CompanionIdentity {
        if (profileId == null) {
            throw new IllegalArgumentException("Profile ID is required");
        }
        displayName = normalize(displayName);
        roleId = normalize(roleId);
        metadataJson = normalize(metadataJson);
        lastKnownWorldKey = normalize(lastKnownWorldKey);
        if (metadataRevision < 0) {
            throw new IllegalArgumentException("Metadata revision cannot be negative");
        }
        requireMatchingMetadataHash(metadataJson, metadataHash);
    }

    private static void requireMatchingMetadataHash(String json, Sha256Hash hash) {
        if (json == null && hash == null) {
            return;
        }
        if (json == null || hash == null) {
            throw new IllegalArgumentException("Metadata JSON and lowercase SHA-256 must appear together");
        }
        if (!hash.matchesUtf8(json)) {
            throw new IllegalArgumentException("Metadata SHA-256 does not match its JSON");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
