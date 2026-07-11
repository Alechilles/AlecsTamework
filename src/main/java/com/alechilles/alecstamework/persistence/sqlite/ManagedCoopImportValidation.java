package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopImportRepository.DispositionKind;

/** Strict scalar and evidence-shape validation shared by the import journal. */
final class ManagedCoopImportValidation {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ManagedCoopImportValidation() {
    }

    @Nonnull
    static String text(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    static String payload(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Nullable
    static String optionalText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    static String hash(@Nullable String value, String field) {
        String normalized = text(value, field);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a SHA-256 hash");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    @Nonnull
    static String contentHash(@Nullable String value, String payload, String field) {
        String normalized = hash(value, field);
        if (!normalized.equals(sha256(payload))) {
            throw new IllegalArgumentException(field + " does not match its payload");
        }
        return normalized;
    }

    static void eventTime(long value, String field) {
        if (value == 0L) {
            throw new IllegalArgumentException(field + " must use a non-zero signed timestamp");
        }
    }

    static void dispositionShape(DispositionKind kind,
                                 @Nullable String operationId,
                                 @Nullable String residentId,
                                 @Nullable String profileId,
                                 @Nullable String conflictId,
                                 @Nullable String conflictKind) {
        boolean quarantine = kind == DispositionKind.QUARANTINED;
        boolean managedBinding = operationId != null && residentId != null && profileId != null;
        boolean anyManagedReference = operationId != null || residentId != null || profileId != null;
        if ((quarantine && (conflictId == null || conflictKind == null || anyManagedReference))
                || (!quarantine && (!managedBinding || conflictId != null || conflictKind != null))) {
            throw new IllegalArgumentException("disposition durable binding shape is invalid");
        }
    }

    @Nonnull
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
