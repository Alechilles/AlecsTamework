package com.alechilles.alecstamework.companion.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
                                @Nullable String metadataHash,
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
        metadataHash = normalize(metadataHash);
        lastKnownWorldKey = normalize(lastKnownWorldKey);
        if (metadataRevision < 0) {
            throw new IllegalArgumentException("Metadata revision cannot be negative");
        }
        requireMatchingMetadataHash(metadataJson, metadataHash);
    }

    private static void requireMatchingMetadataHash(String json, String hash) {
        if (json == null && hash == null) {
            return;
        }
        if (json == null || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Metadata JSON and lowercase SHA-256 must appear together");
        }
        if (!MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.US_ASCII),
                sha256(json).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Metadata SHA-256 does not match its JSON");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
