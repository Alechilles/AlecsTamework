package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Strict parsers for released nested values that previously decoded by silently dropping data. */
final class LegacyRestorationEvidence {
    private LegacyRestorationEvidence() {
    }

    static Metadata metadata(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new Metadata(null, null, null);
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw invalid("metadataJson", "object");
            }
            JsonObject root = parsed.getAsJsonObject();
            return new Metadata(
                    optionalString(root, "owner_name"),
                    optionalString(root, "custom_name"),
                    optionalBoolean(root, "tamed")
            );
        } catch (EvidenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("metadataJson", "valid object", failure);
        }
    }

    static TameworkTraitsComponent.TraitValue[] traits(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new TameworkTraitsComponent.TraitValue[0];
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonArray()) {
                throw invalid("traitsValues", "array");
            }
            ArrayList<TameworkTraitsComponent.TraitValue> result = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw invalid("traitsValues", "trait objects");
                }
                JsonObject trait = element.getAsJsonObject();
                if (!trait.keySet().equals(Set.of("id", "value"))) {
                    throw invalid("traitsValues", "exact id/value objects");
                }
                String id = requiredString(trait, "id", "traitsValues");
                double value = requiredFiniteDouble(
                        trait,
                        "value",
                        "traitsValues"
                );
                if (!ids.add(id)) {
                    throw invalid("traitsValues", "unique trait IDs");
                }
                result.add(new TameworkTraitsComponent.TraitValue(id, value));
            }
            return result.toArray(new TameworkTraitsComponent.TraitValue[0]);
        } catch (EvidenceException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("traitsValues", "valid trait array", failure);
        }
    }

    static String[] talents(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int index = 0; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (escaping) {
                if (value != '\\' && value != '|') {
                    throw invalid("purchasedTalentIds", "valid escape");
                }
                current.append(value);
                escaping = false;
            } else if (value == '\\') {
                escaping = true;
            } else if (value == '|') {
                addTalent(values, unique, current);
            } else {
                current.append(value);
            }
        }
        if (escaping) {
            throw invalid("purchasedTalentIds", "complete escape");
        }
        addTalent(values, unique, current);
        return values.toArray(new String[0]);
    }

    static Map<String, String> attachments(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split(";", -1)) {
            if (part.isBlank()) {
                throw invalid("attachmentsValues", "nonempty selections");
            }
            int separator = part.indexOf(',');
            if (separator <= 0 || separator != part.lastIndexOf(',')
                    || separator == part.length() - 1) {
                throw invalid("attachmentsValues", "one encoded key/value pair");
            }
            String key = decodeAttachmentToken(
                    part.substring(0, separator)
            );
            String value = decodeAttachmentToken(
                    part.substring(separator + 1)
            );
            if (result.putIfAbsent(key, value) != null) {
                throw invalid("attachmentsValues", "unique attachment keys");
            }
        }
        return Map.copyOf(result);
    }

    private static void addTalent(
            ArrayList<String> values,
            LinkedHashSet<String> unique,
            StringBuilder current
    ) {
        String value = current.toString().trim();
        current.setLength(0);
        if (value.isEmpty()) {
            throw invalid("purchasedTalentIds", "nonblank IDs");
        }
        if (!unique.add(value)) {
            throw invalid("purchasedTalentIds", "unique IDs");
        }
        values.add(value);
    }

    private static String decodeAttachmentToken(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (decoded.isBlank()) {
                throw invalid("attachmentsValues", "nonblank decoded IDs");
            }
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException failure) {
            throw invalid("attachmentsValues", "base64url UTF-8 IDs", failure);
        }
    }

    @Nullable
    private static String optionalString(JsonObject root, String field) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("metadataJson." + field, "string");
        }
        String decoded = value.getAsString().trim();
        return decoded.isEmpty() ? null : decoded;
    }

    @Nullable
    private static Boolean optionalBoolean(JsonObject root, String field) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid("metadataJson." + field, "boolean");
        }
        return value.getAsBoolean();
    }

    private static String requiredString(
            JsonObject root,
            String field,
            String qualified
    ) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw invalid(qualified, "nonblank string IDs");
        }
        return value.getAsString().trim();
    }

    private static double requiredFiniteDouble(
            JsonObject root,
            String field,
            String qualified
    ) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(qualified, "finite numeric values");
        }
        double decoded = value.getAsDouble();
        if (!Double.isFinite(decoded)) {
            throw invalid(qualified, "finite numeric values");
        }
        return decoded;
    }

    @Nullable
    private static JsonElement optional(JsonObject root, String field) {
        return !root.has(field) || root.get(field).isJsonNull()
                ? null
                : root.get(field);
    }

    private static EvidenceException invalid(String field, String expected) {
        return new EvidenceException(
                field,
                "Expected " + expected
        );
    }

    private static EvidenceException invalid(
            String field,
            String expected,
            Throwable cause
    ) {
        return new EvidenceException(
                field,
                "Expected " + expected,
                cause
        );
    }

    record Metadata(
            @Nullable String ownerName,
            @Nullable String customName,
            @Nullable Boolean tamed
    ) {
    }

    static final class EvidenceException extends IllegalArgumentException {
        private final String field;

        EvidenceException(String field, String message) {
            super(message);
            this.field = field;
        }

        EvidenceException(String field, String message, Throwable cause) {
            super(message, cause);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
