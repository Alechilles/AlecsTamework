package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import javax.annotation.Nonnull;

/**
 * Exact engine-neutral item value written by one successful companion capture.
 *
 * <p>The metadata is canonical BSON Extended JSON. Quantity and both durability values are
 * retained because they participate in exact live inventory comparison and cannot safely be
 * re-derived from an asset after restart.</p>
 */
public record CapturedArtifact(
        @Nonnull String itemId,
        int quantity,
        double durability,
        double maxDurability,
        @Nonnull String metadataExtendedJson,
        @Nonnull Sha256Hash artifactHash
) {
    public CapturedArtifact {
        itemId = requireItemId(itemId);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Captured artifact quantity must be positive");
        }
        requireDurability(durability, "durability");
        requireDurability(maxDurability, "maximum durability");
        if (metadataExtendedJson == null || metadataExtendedJson.isBlank()) {
            throw new IllegalArgumentException(
                    "Captured artifact metadata Extended JSON is required"
            );
        }
        String canonical = CapturedArtifactJsonCodec.canonicalizeMetadata(
                metadataExtendedJson
        );
        if (!canonical.equals(metadataExtendedJson)) {
            throw new IllegalArgumentException(
                    "Captured artifact metadata must be canonical Extended JSON"
            );
        }
        if (artifactHash == null || !artifactHash.equals(
                CapturedArtifactJsonCodec.hash(
                        itemId,
                        quantity,
                        durability,
                        maxDurability,
                        metadataExtendedJson
                )
        )) {
            throw new IllegalArgumentException(
                    "Captured artifact SHA-256 does not match its canonical value"
            );
        }
    }

    /** Canonicalizes metadata and computes the exact artifact hash. */
    @Nonnull
    public static CapturedArtifact create(
            @Nonnull String itemId,
            int quantity,
            double durability,
            double maxDurability,
            @Nonnull String metadataExtendedJson
    ) {
        String normalizedItemId = requireItemId(itemId);
        String canonicalMetadata =
                CapturedArtifactJsonCodec.canonicalizeMetadata(
                        metadataExtendedJson
                );
        return new CapturedArtifact(
                normalizedItemId,
                quantity,
                durability,
                maxDurability,
                canonicalMetadata,
                CapturedArtifactJsonCodec.hash(
                        normalizedItemId,
                        quantity,
                        durability,
                        maxDurability,
                        canonicalMetadata
                )
        );
    }

    private static String requireItemId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Captured artifact item ID is required"
            );
        }
        String normalized = value.trim();
        if ("Empty".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Captured artifact item ID cannot be Empty"
            );
        }
        return normalized;
    }

    private static void requireDurability(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0D) {
            throw new IllegalArgumentException(
                    "Captured artifact " + label
                            + " must be finite and non-negative"
            );
        }
    }
}
