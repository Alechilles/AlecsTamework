package com.alechilles.alecstamework.persistence.sqlite;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Strict scalar validation shared by managed resident and lifecycle readers. */
final class ManagedCoopReadValidation {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private ManagedCoopReadValidation() {
    }

    @Nonnull
    static String requireText(@Nullable String value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        if (value == null || value.isBlank()) {
            throw new ManagedCoopIntegrityException("missing_managed_coop_field:" + field);
        }
        return value.trim();
    }

    @Nonnull
    static String normalizeCoopId(@Nullable String value) throws ManagedCoopIntegrityException {
        return requireText(value, "coop_id").toLowerCase(Locale.ROOT);
    }

    @Nonnull
    static UUID requireUuid(@Nullable String value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        UUID parsed = optionalUuid(value, field);
        if (parsed == null) {
            throw new ManagedCoopIntegrityException("missing_managed_coop_uuid:" + field);
        }
        return parsed;
    }

    @Nullable
    static UUID optionalUuid(@Nullable String value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ManagedCoopIntegrityException("blank_managed_coop_uuid:" + field);
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new ManagedCoopIntegrityException("invalid_managed_coop_uuid:" + field, exception);
        }
    }

    @Nullable
    static String optionalSha256(@Nullable String value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || !SHA_256.matcher(value).matches()) {
            throw new ManagedCoopIntegrityException("invalid_managed_coop_sha256:" + field);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    static boolean strictBoolean(int value, @Nonnull String field)
            throws ManagedCoopIntegrityException {
        if (value != 0 && value != 1) {
            throw new ManagedCoopIntegrityException("invalid_managed_coop_boolean:" + field + ":" + value);
        }
        return value == 1;
    }
}
