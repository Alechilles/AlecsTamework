package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;

/**
 * Immutable profile extension row with version, integrity, and optimistic revision evidence.
 *
 * <p>Hash and JSON validation are intentionally performed by
 * {@link ProfileExtensionDataDecoder}; constructing a row read from storage must not turn
 * corruption into authoritative absence.</p>
 */
public record ProfileExtensionData(@Nonnull ProfileExtensionKey key,
                                   int payloadVersion,
                                   @Nonnull String jsonPayload,
                                   @Nonnull Sha256Hash payloadHash,
                                   long revision,
                                   long createdAtMs,
                                   long updatedAtMs) {
    public ProfileExtensionData {
        if (key == null || jsonPayload == null || payloadHash == null) {
            throw new IllegalArgumentException("Complete profile extension data is required");
        }
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("Extension payload version must be positive");
        }
        if (revision <= 0) {
            throw new IllegalArgumentException("Extension revision must be positive");
        }
    }

    /** Creates a validated first-revision JSON value. */
    @Nonnull
    public static ProfileExtensionData initial(@Nonnull ProfileExtensionKey key,
                                               @Nonnull String jsonPayload,
                                               long createdAtMs) {
        return new ProfileExtensionData(
                key,
                ProfileExtensionDataDecoder.JSON_VERSION,
                jsonPayload,
                Sha256Hash.ofUtf8(jsonPayload),
                1,
                createdAtMs,
                createdAtMs
        );
    }
}
