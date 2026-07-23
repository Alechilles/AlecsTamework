package com.alechilles.alecstamework.persistence.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.annotation.Nonnull;

/**
 * Canonical lowercase SHA-256 value shared by replacement persistence boundaries.
 *
 * @param value 64-character lowercase hexadecimal digest
 */
public record Sha256Hash(@Nonnull String value) {
    public Sha256Hash {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Canonical lowercase SHA-256 is required");
        }
    }

    /** Computes a digest over the exact UTF-8 representation. */
    @Nonnull
    public static Sha256Hash ofUtf8(@Nonnull String value) {
        if (value == null) {
            throw new IllegalArgumentException("SHA-256 input is required");
        }
        try {
            return new Sha256Hash(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Parses the durable lowercase representation. */
    @Nonnull
    public static Sha256Hash parse(@Nonnull String value) {
        return new Sha256Hash(value);
    }

    /** Returns whether this digest matches the exact UTF-8 representation. */
    public boolean matchesUtf8(@Nonnull String value) {
        if (value == null) {
            return false;
        }
        return MessageDigest.isEqual(
                this.value.getBytes(StandardCharsets.US_ASCII),
                ofUtf8(value).value.getBytes(StandardCharsets.US_ASCII)
        );
    }

    @Override
    @Nonnull
    public String toString() {
        return value;
    }
}
