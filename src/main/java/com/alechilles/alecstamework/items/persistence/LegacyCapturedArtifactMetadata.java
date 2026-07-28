package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonNumber;
import org.bson.BsonValue;

/** Strict typed access to one immutable released-public captured artifact document. */
record LegacyCapturedArtifactMetadata(BsonDocument values) {
    static LegacyCapturedArtifactMetadata parse(CapturedArtifact artifact) {
        try {
            return new LegacyCapturedArtifactMetadata(BsonDocument.parse(
                    artifact.metadataExtendedJson()
            ));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Legacy captured artifact metadata is invalid",
                    failure
            );
        }
    }

    boolean has(String key) {
        BsonValue value = values.get(key);
        return value != null && !value.isNull();
    }

    void requireCompleteGroup(
            String label,
            String[] required,
            String... members
    ) {
        boolean present = false;
        for (String member : members) {
            present |= has(member);
        }
        if (!present) {
            return;
        }
        for (String key : required) {
            if (!has(key)) {
                throw new IllegalArgumentException(
                        "Legacy capture " + label
                                + " metadata is incomplete: " + key
                );
            }
        }
    }

    @Nullable
    String text(String key) {
        BsonValue value = optional(key);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw invalid(key, "text");
        }
        String result = value.asString().getValue();
        return result == null || result.isBlank()
                ? null
                : result.trim();
    }

    @Nullable
    UUID uuid(String key) {
        String value = text(key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw invalid(key, "UUID");
        }
    }

    @Nullable
    Boolean bool(String key) {
        BsonValue value = optional(key);
        if (value == null) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalid(key, "boolean");
        }
        return value.asBoolean().getValue();
    }

    @Nullable
    Long integer(String key) {
        BsonNumber number = number(key);
        if (number == null) {
            return null;
        }
        return switch (number.getBsonType()) {
            case INT32 -> (long) number.asInt32().getValue();
            case INT64 -> number.asInt64().getValue();
            default -> throw invalid(key, "integer");
        };
    }

    @Nullable
    Integer intValue(String key) {
        Long value = integer(key);
        if (value == null) {
            return null;
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException failure) {
            throw invalid(key, "32-bit integer");
        }
    }

    @Nullable
    Double finiteDouble(String key) {
        BsonNumber number = number(key);
        if (number == null) {
            return null;
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw invalid(key, "finite number");
        }
        return result;
    }

    @Nullable
    private BsonNumber number(String key) {
        BsonValue value = optional(key);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid(key, "number");
        }
        return value.asNumber();
    }

    @Nullable
    private BsonValue optional(String key) {
        BsonValue value = values.get(key);
        return value == null || value.isNull() ? null : value;
    }

    private IllegalArgumentException invalid(
            String key,
            String expected
    ) {
        return new IllegalArgumentException(
                "Legacy capture " + key + " must be " + expected
        );
    }
}
