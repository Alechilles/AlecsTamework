package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Strict versioned JSON codec for one complete bonded profile snapshot. */
public final class BondedCompanionSnapshotCodec {
    public static final int CURRENT_VERSION = 1;

    private final CoopResidentStateSnapshotCodec fullStateCodec =
            new CoopResidentStateSnapshotCodec();

    /** Encodes the full state and exact namespaced extension payload strings. */
    @Nonnull
    public String encode(@Nonnull BondedCompanionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.add(
                "fullState",
                JsonParser.parseString(fullStateCodec.encode(
                        snapshot.fullStateInternal()
                ))
        );
        JsonObject extensions = new JsonObject();
        snapshot.extensionData().forEach(extensions::addProperty);
        root.add("extensions", extensions);
        return root.toString();
    }

    /** Decodes without collapsing absence and malformed state. */
    @Nonnull
    public DecodeResult decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return DecodeResult.notFound();
        }
        final JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return DecodeResult.failed(Failure.INVALID_ROOT, null);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException invalid) {
            return DecodeResult.failed(Failure.INVALID_JSON, null);
        }
        return decodeRoot(root);
    }

    private DecodeResult decodeRoot(JsonObject root) {
        JsonElement version = root.get("version");
        if (version == null || !version.isJsonPrimitive()
                || !version.getAsJsonPrimitive().isNumber()) {
            return DecodeResult.failed(Failure.INVALID_VERSION, "version");
        }
        try {
            if (version.getAsInt() != CURRENT_VERSION) {
                return DecodeResult.failed(
                        Failure.UNSUPPORTED_VERSION, "version"
                );
            }
        } catch (RuntimeException invalid) {
            return DecodeResult.failed(Failure.INVALID_VERSION, "version");
        }
        return decodePayload(root);
    }

    private DecodeResult decodePayload(JsonObject root) {
        JsonElement fullState = root.get("fullState");
        if (fullState == null || !fullState.isJsonObject()) {
            return DecodeResult.failed(Failure.INVALID_FULL_STATE, "fullState");
        }
        CoopResidentStateSnapshotCodec.DecodeResult decoded =
                fullStateCodec.decode(fullState.toString());
        if (decoded.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || decoded.snapshot() == null) {
            return DecodeResult.failed(Failure.INVALID_FULL_STATE, "fullState");
        }
        try {
            return DecodeResult.found(BondedCompanionSnapshot.of(
                    decoded.snapshot(), decodeExtensions(root.get("extensions"))
            ));
        } catch (RuntimeException invalid) {
            return DecodeResult.failed(Failure.INVALID_EXTENSIONS, "extensions");
        }
    }

    private Map<String, String> decodeExtensions(JsonElement encoded) {
        if (encoded == null || encoded.isJsonNull()) {
            return Map.of();
        }
        if (!encoded.isJsonObject()) {
            throw new IllegalArgumentException("extensions must be an object");
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        encoded.getAsJsonObject().entrySet().forEach(entry -> {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(
                        "extension payload must be a JSON string"
                );
            }
            result.put(entry.getKey(), value.getAsString());
        });
        return result;
    }

    /** Stable outer-envelope status. */
    public enum Status { FOUND, NOT_FOUND, FAILED }

    /** Stable outer-envelope failure. */
    public enum Failure {
        INVALID_JSON,
        INVALID_ROOT,
        INVALID_VERSION,
        UNSUPPORTED_VERSION,
        INVALID_FULL_STATE,
        INVALID_EXTENSIONS
    }

    /** Immutable strict decode result. */
    public record DecodeResult(
            @Nonnull Status status,
            @Nullable BondedCompanionSnapshot snapshot,
            @Nullable Failure failure,
            @Nullable String field
    ) {
        private static DecodeResult found(BondedCompanionSnapshot snapshot) {
            return new DecodeResult(Status.FOUND, snapshot, null, null);
        }

        private static DecodeResult notFound() {
            return new DecodeResult(Status.NOT_FOUND, null, null, null);
        }

        private static DecodeResult failed(Failure failure, String field) {
            return new DecodeResult(Status.FAILED, null, failure, field);
        }
    }
}
