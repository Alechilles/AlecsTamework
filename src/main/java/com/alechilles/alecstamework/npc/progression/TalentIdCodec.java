package com.alechilles.alecstamework.npc.progression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Encodes purchased talent IDs into a compact stable string for metadata and snapshot persistence.
 */
public final class TalentIdCodec {
    private static final char SEPARATOR = '|';
    private static final char ESCAPE = '\\';

    private TalentIdCodec() {
    }

    @Nullable
    public static String encode(@Nullable String[] talentIds) {
        if (talentIds == null || talentIds.length == 0) {
            return null;
        }
        ArrayList<String> sanitized = new ArrayList<>(talentIds.length);
        for (String talentId : talentIds) {
            if (talentId == null || talentId.isBlank()) {
                continue;
            }
            sanitized.add(escape(talentId.trim()));
        }
        if (sanitized.isEmpty()) {
            return null;
        }
        return String.join(String.valueOf(SEPARATOR), sanitized);
    }

    public static String[] decode(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new String[0];
        }
        ArrayList<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < encoded.length(); i++) {
            char ch = encoded.charAt(i);
            if (escaping) {
                current.append(ch);
                escaping = false;
                continue;
            }
            if (ch == ESCAPE) {
                escaping = true;
                continue;
            }
            if (ch == SEPARATOR) {
                addDecodedValue(values, current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addDecodedValue(values, current.toString());
        if (values.isEmpty()) {
            return new String[0];
        }
        Set<String> unique = new LinkedHashSet<>(values);
        return unique.toArray(new String[0]);
    }

    private static void addDecodedValue(ArrayList<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.add(value.trim());
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ESCAPE || ch == SEPARATOR) {
                out.append(ESCAPE);
            }
            out.append(ch);
        }
        return out.toString();
    }
}
