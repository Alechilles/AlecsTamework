package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Encodes attachment selections stored inside immutable companion snapshots.
 */
final class CommandAttachmentSelectionCodec {
    private static final String ENTRY_SEPARATOR = ";";
    private static final String KEY_VALUE_SEPARATOR = ",";

    private CommandAttachmentSelectionCodec() {
    }

    @Nullable
    static String encode(@Nullable Map<String, String> selections) {
        if (selections == null || selections.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : selections.entrySet()) {
            if (entry == null || blank(entry.getKey())
                    || blank(entry.getValue())) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(ENTRY_SEPARATOR);
            }
            builder.append(token(entry.getKey()))
                    .append(KEY_VALUE_SEPARATOR)
                    .append(token(entry.getValue()));
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    @Nonnull
    static Map<String, String> decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        HashMap<String, String> decoded = new HashMap<>();
        for (String entry : raw.split(ENTRY_SEPARATOR)) {
            String[] pair = entry.split(KEY_VALUE_SEPARATOR, 2);
            if (pair.length != 2) {
                continue;
            }
            String key = decodeToken(pair[0]);
            String value = decodeToken(pair[1]);
            if (!blank(key) && !blank(value)) {
                decoded.put(key, value);
            }
        }
        return CompanionModelAttachmentService
                .sanitizeAttachmentSelections(decoded);
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String token(String value) {
        return Base64.getUrlEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Nullable
    private static String decodeToken(@Nullable String value) {
        if (blank(value)) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value),
                    StandardCharsets.UTF_8
            );
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
