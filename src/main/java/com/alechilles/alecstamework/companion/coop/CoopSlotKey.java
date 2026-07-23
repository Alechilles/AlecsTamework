package com.alechilles.alecstamework.companion.coop;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.annotation.Nonnull;

/**
 * Versioned, collision-free identity for one physical coop resident slot.
 *
 * <p>World and coop identifiers are URL-safe Base64 components so delimiter characters in asset
 * or world names cannot alias a different slot.</p>
 */
public record CoopSlotKey(
        @Nonnull String worldKey,
        @Nonnull String coopId,
        int x,
        int y,
        int z,
        int residentSlot
) implements Comparable<CoopSlotKey> {
    private static final String VERSION = "v1";
    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public CoopSlotKey {
        worldKey = requireText(worldKey, "Coop world key");
        coopId = requireText(coopId, "Coop ID");
        if (residentSlot < 0) {
            throw new IllegalArgumentException("Coop resident slot cannot be negative");
        }
    }

    /** Parses only the explicit replacement-lineage key format. */
    @Nonnull
    public static CoopSlotKey parse(@Nonnull String value) {
        if (value == null) {
            throw new IllegalArgumentException("Coop slot key is required");
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 7 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported coop slot key");
        }
        try {
            return new CoopSlotKey(
                    decode(parts[1]),
                    decode(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]),
                    Integer.parseInt(parts[6])
            );
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid coop slot key", failure);
        }
    }

    /**
     * Returns the public-v2-v4 composite used only to correlate immutable import evidence.
     */
    @Nonnull
    public String legacySourceKey() {
        return worldKey + "|" + coopId + "|" + x + "|" + y + "|" + z
                + "|" + residentSlot;
    }

    @Override
    @Nonnull
    public String toString() {
        return VERSION + ":" + encode(worldKey) + ":" + encode(coopId)
                + ":" + x + ":" + y + ":" + z + ":" + residentSlot;
    }

    @Override
    public int compareTo(CoopSlotKey other) {
        if (other == null) {
            throw new NullPointerException("Other coop slot key is required");
        }
        return toString().compareTo(other.toString());
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
