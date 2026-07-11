package com.alechilles.alecstamework.ownership.reconciliation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Builds stable fingerprints for resumable source snapshots. */
final class ReconciliationGeneration {
    private ReconciliationGeneration() {
    }

    @Nonnull
    static String forLongs(@Nonnull String prefix, @Nonnull long[] values) {
        MessageDigest digest = digest();
        digest.update(prefix.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        for (long value : values) {
            buffer.clear();
            buffer.putLong(value);
            digest.update(buffer.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Nonnull
    static String forUuids(@Nonnull String prefix, @Nonnull Collection<UUID> values) {
        MessageDigest digest = digest();
        digest.update(prefix.getBytes(StandardCharsets.UTF_8));
        for (UUID value : values) {
            digest.update(Objects.requireNonNull(value, "value").toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Nonnull
    static String forStrings(@Nonnull String prefix, @Nonnull Collection<String> values) {
        MessageDigest digest = digest();
        digest.update(prefix.getBytes(StandardCharsets.UTF_8));
        for (String value : values) {
            digest.update(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Nonnull
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
