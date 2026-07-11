package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encodes nullable fields used by the legacy tab-delimited coop ledger. */
final class CoopLedgerTextCodec {
    private static final String ARRAY_SEPARATOR = ";";

    private CoopLedgerTextCodec() {
    }

    @Nonnull
    static String encodeNullableUuid(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    @Nullable
    static UUID decodeNullableUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    static String encodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    static String decodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    static String encodeStringArray(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(ARRAY_SEPARATOR);
            }
            builder.append(encodeNullableString(value));
        }
        return builder.toString();
    }

    @Nonnull
    static String[] decodeStringArray(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        ArrayList<String> decoded = new ArrayList<>();
        for (String value : raw.split(ARRAY_SEPARATOR)) {
            String item = decodeNullableString(value);
            if (item != null && !item.isBlank()) {
                decoded.add(item);
            }
        }
        return decoded.toArray(new String[0]);
    }
}
