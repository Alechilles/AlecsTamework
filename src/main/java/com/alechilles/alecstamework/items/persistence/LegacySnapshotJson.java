package com.alechilles.alecstamework.items.persistence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Strict deterministic JSON primitives shared by the released snapshot codecs. */
final class LegacySnapshotJson {
    private LegacySnapshotJson() {
    }

    @Nonnull
    static JsonObject parseRoot(@Nonnull String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("Snapshot payload JSON is required");
        }
        JsonElement parsed = JsonParser.parseString(payloadJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Snapshot payload root must be an object");
        }
        return parsed.getAsJsonObject();
    }

    @Nullable
    static String optionalString(@Nonnull JsonObject root, @Nonnull String field) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid(field, "string");
        }
        String decoded = value.getAsString();
        return decoded == null || decoded.isBlank() ? null : decoded;
    }

    @Nullable
    static UUID optionalUuid(@Nonnull JsonObject root, @Nonnull String field) {
        String raw = optionalString(root, field);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException failure) {
            throw invalid(field, "UUID");
        }
    }

    static long optionalLong(@Nonnull JsonObject root,
                             @Nonnull String field,
                             long fallback) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return fallback;
        }
        requireNumber(value, field);
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(field, "integer");
        }
    }

    static int optionalInt(@Nonnull JsonObject root,
                           @Nonnull String field,
                           int fallback) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return fallback;
        }
        requireNumber(value, field);
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw invalid(field, "integer");
        }
    }

    static double optionalDouble(@Nonnull JsonObject root,
                                 @Nonnull String field,
                                 double fallback) {
        Double decoded = optionalNullableDouble(root, field);
        return decoded == null ? fallback : decoded;
    }

    @Nullable
    static Double optionalNullableDouble(@Nonnull JsonObject root, @Nonnull String field) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return null;
        }
        requireNumber(value, field);
        double decoded = value.getAsDouble();
        if (!Double.isFinite(decoded)) {
            throw invalid(field, "finite number");
        }
        return decoded;
    }

    static boolean optionalBoolean(@Nonnull JsonObject root,
                                   @Nonnull String field,
                                   boolean fallback) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalid(field, "boolean");
        }
        return value.getAsBoolean();
    }

    @Nullable
    static SnapshotVector3 optionalVector(@Nonnull JsonObject root, @Nonnull String field) {
        JsonElement value = optional(root, field);
        if (value == null) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw invalid(field, "vector object");
        }
        JsonObject vector = value.getAsJsonObject();
        return new SnapshotVector3(
                requiredFiniteDouble(vector, field + ".x", "x"),
                requiredFiniteDouble(vector, field + ".y", "y"),
                requiredFiniteDouble(vector, field + ".z", "z")
        );
    }

    static void putString(@Nonnull JsonObject root,
                          @Nonnull String field,
                          @Nullable String value) {
        if (value != null && !value.isBlank()) {
            root.addProperty(field, value);
        }
    }

    static void putUuid(@Nonnull JsonObject root,
                        @Nonnull String field,
                        @Nullable UUID value) {
        if (value != null) {
            root.addProperty(field, value.toString());
        }
    }

    static void putVector(@Nonnull JsonObject root,
                          @Nonnull String field,
                          @Nullable SnapshotVector3 value) {
        if (value == null) {
            return;
        }
        JsonObject vector = new JsonObject();
        vector.addProperty("x", value.x());
        vector.addProperty("y", value.y());
        vector.addProperty("z", value.z());
        root.add(field, vector);
    }

    @Nullable
    private static JsonElement optional(@Nonnull JsonObject root, @Nonnull String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            return null;
        }
        return root.get(field);
    }

    private static double requiredFiniteDouble(@Nonnull JsonObject root,
                                               @Nonnull String qualifiedField,
                                               @Nonnull String field) {
        if (!root.has(field) || root.get(field).isJsonNull()) {
            throw invalid(qualifiedField, "finite number");
        }
        JsonElement value = root.get(field);
        requireNumber(value, qualifiedField);
        double decoded = value.getAsDouble();
        if (!Double.isFinite(decoded)) {
            throw invalid(qualifiedField, "finite number");
        }
        return decoded;
    }

    private static void requireNumber(@Nonnull JsonElement value, @Nonnull String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field, "number");
        }
        try {
            new BigDecimal(value.getAsString());
        } catch (NumberFormatException failure) {
            throw invalid(field, "number");
        }
    }

    @Nonnull
    private static IllegalArgumentException invalid(@Nonnull String field,
                                                    @Nonnull String expected) {
        return new IllegalArgumentException(
                "Invalid snapshot field " + field + ": expected " + expected
        );
    }
}
