package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Set;
import javax.annotation.Nonnull;

/** Strict JSON translation for one immutable encoded snapshot artifact. */
public final class EncodedSnapshotJsonCodec {
    private static final Set<String> FIELDS = Set.of(
            "kind",
            "payloadVersion",
            "payloadJson",
            "payloadHash"
    );

    private EncodedSnapshotJsonCodec() {
    }

    /** Encodes the complete payload and its already-verified integrity hash. */
    @Nonnull
    public static JsonObject encode(
            @Nonnull SnapshotCodecRegistry.EncodedSnapshot snapshot
    ) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "Encoded snapshot is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("kind", snapshot.kind().toString());
        json.addProperty(
                "payloadVersion", snapshot.payloadVersion()
        );
        json.addProperty("payloadJson", snapshot.payloadJson());
        json.addProperty(
                "payloadHash", snapshot.payloadHash().toString()
        );
        return json;
    }

    /** Decodes only the exact canonical encoded-snapshot JSON shape. */
    @Nonnull
    public static SnapshotCodecRegistry.EncodedSnapshot decode(
            @Nonnull JsonObject json
    ) {
        if (json == null || !json.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException(
                    "Invalid encoded snapshot fields"
            );
        }
        return new SnapshotCodecRegistry.EncodedSnapshot(
                new SnapshotKind(requiredString(json, "kind")),
                requiredPositiveInt(json, "payloadVersion"),
                requiredString(json, "payloadJson"),
                Sha256Hash.parse(requiredString(json, "payloadHash"))
        );
    }

    private static String requiredString(
            JsonObject json,
            String field
    ) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw invalid(field, "nonblank string");
        }
        return value.getAsString();
    }

    private static int requiredPositiveInt(
            JsonObject json,
            String field
    ) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(field, "positive integer");
        }
        try {
            int decoded = value.getAsBigDecimal().intValueExact();
            if (decoded <= 0) {
                throw invalid(field, "positive integer");
            }
            return decoded;
        } catch (ArithmeticException failure) {
            throw invalid(field, "positive integer");
        }
    }

    private static JsonElement required(
            JsonObject json,
            String field
    ) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw invalid(field, "required value");
        }
        return json.get(field);
    }

    private static IllegalArgumentException invalid(
            String field,
            String expected
    ) {
        return new IllegalArgumentException(
                "Invalid encoded snapshot field "
                        + field + ": expected " + expected
        );
    }
}
