package com.alechilles.alecstamework.companion.restoration;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Validating JSON translation for one durable restoration projection. */
public final class RestorationProjectionJsonCodec {
    private RestorationProjectionJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull RestorationProjection projection) {
        if (projection == null) {
            throw new IllegalArgumentException("Restoration projection is required");
        }
        JsonObject fullState = new JsonObject();
        fullState.addProperty("kind", projection.fullState().kind().toString());
        fullState.addProperty(
                "payloadVersion",
                projection.fullState().payloadVersion()
        );
        fullState.addProperty("payloadJson", projection.fullState().payloadJson());
        fullState.addProperty(
                "payloadHash",
                projection.fullState().payloadHash().toString()
        );
        JsonObject json = new JsonObject();
        json.addProperty("sourceAlias", projection.sourceAlias().toString());
        json.add("fullState", fullState);
        return json;
    }

    @Nonnull
    public static RestorationProjection decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Restoration projection JSON is required"
            );
        }
        requireExactKeys(json, "sourceAlias", "fullState");
        JsonObject fullState = requiredObject(json, "fullState");
        requireExactKeys(
                fullState,
                "kind",
                "payloadVersion",
                "payloadJson",
                "payloadHash"
        );
        return new RestorationProjection(
                NpcAlias.parse(requiredString(json, "sourceAlias")),
                new SnapshotCodecRegistry.EncodedSnapshot(
                        new SnapshotKind(requiredString(fullState, "kind")),
                        requiredPositiveInt(fullState, "payloadVersion"),
                        requiredString(fullState, "payloadJson"),
                        Sha256Hash.parse(requiredString(fullState, "payloadHash"))
                )
        );
    }

    private static JsonObject requiredObject(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonObject()) {
            throw invalid(field, "object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject json, String field) {
        JsonElement value = required(json, field);
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw invalid(field, "nonblank string");
        }
        return value.getAsString();
    }

    private static int requiredPositiveInt(JsonObject json, String field) {
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

    private static JsonElement required(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw invalid(field, "required value");
        }
        return json.get(field);
    }

    private static void requireExactKeys(JsonObject json, String... expected) {
        java.util.Set<String> keys = java.util.Set.of(expected);
        if (!json.keySet().equals(keys)) {
            throw new IllegalArgumentException(
                    "Invalid restoration projection fields"
            );
        }
    }

    private static IllegalArgumentException invalid(
            String field,
            String expected
    ) {
        return new IllegalArgumentException(
                "Invalid restoration projection field "
                        + field + ": expected " + expected
        );
    }
}
