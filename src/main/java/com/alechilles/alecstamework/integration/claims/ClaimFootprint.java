package com.alechilles.alecstamework.integration.claims;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Immutable, canonical set of chunks occupied by one claim.
 */
public record ClaimFootprint(@Nonnull List<ClaimChunkCoordinate> chunks) {
    private static final Comparator<ClaimChunkCoordinate> COORDINATE_ORDER = Comparator
            .comparing(ClaimChunkCoordinate::worldName)
            .thenComparingInt(ClaimChunkCoordinate::chunkX)
            .thenComparingInt(ClaimChunkCoordinate::chunkZ);

    public ClaimFootprint {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks cannot be null");
        }
        LinkedHashSet<ClaimChunkCoordinate> unique = new LinkedHashSet<>();
        for (ClaimChunkCoordinate chunk : chunks) {
            if (chunk == null) {
                throw new IllegalArgumentException("chunks cannot contain null elements");
            }
            unique.add(chunk);
        }
        ArrayList<ClaimChunkCoordinate> canonical = new ArrayList<>(unique);
        canonical.sort(COORDINATE_ORDER);
        chunks = List.copyOf(canonical);
    }

    public int chunkCount() {
        return chunks.size();
    }

    /**
     * Returns an order-independent digest suitable for fallback claim identity and cache validation.
     */
    @Nonnull
    public String digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("tamework-claim-footprint-v1\n".getBytes(StandardCharsets.UTF_8));
            for (ClaimChunkCoordinate chunk : chunks) {
                digest.update(chunk.worldName().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                updateInt(digest, chunk.chunkX());
                updateInt(digest, chunk.chunkZ());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void updateInt(@Nonnull MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
