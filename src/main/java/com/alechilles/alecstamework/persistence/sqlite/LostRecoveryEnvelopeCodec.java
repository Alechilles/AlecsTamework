package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import org.joml.Vector3d;

/** Strict versioned JSON and SHA-256 boundary for durable lost-recovery envelopes. */
final class LostRecoveryEnvelopeCodec {
    static final int CURRENT_FORMAT_VERSION = 1;
    private static final String FORMAT_VERSION = "recoveryEnvelopeVersion";
    private static final String SOURCE_UUID = "sourceNpcUuid";
    private static final String FULL_SNAPSHOT = "fullStateSnapshot";
    private static final String FULL_SNAPSHOT_HASH = "fullStateSnapshotSha256";

    private final CoopResidentStateSnapshotCodec snapshotCodec = new CoopResidentStateSnapshotCodec();

    EncodedPayload encode(CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost,
                          CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot) {
        if (lost == null || lost.npcUuid() == null) {
            throw new IllegalArgumentException("Lost source NPC UUID is required");
        }
        if (fullSnapshot != null && !lost.npcUuid().equals(fullSnapshot.npcUuid())) {
            throw new IllegalArgumentException("Full snapshot source UUID does not match lost source UUID");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty(FORMAT_VERSION, CURRENT_FORMAT_VERSION);
        payload.addProperty(SOURCE_UUID, lost.npcUuid().toString());
        putVector(payload, "lastKnownPosition", lost.lastKnownPosition());
        putVector(payload, "homePosition", lost.homePosition());
        payload.addProperty("lastRelocationQueuedAtMs", lost.lastRelocationQueuedAtMs());
        payload.addProperty("lostAtMs", lost.lostAtMs());
        payload.addProperty("relocationRetryAttempts", lost.relocationRetryAttempts());
        if (lost.replacementNpcUuid() != null) {
            payload.addProperty("replacementNpcUuid", lost.replacementNpcUuid().toString());
        }
        payload.addProperty("recoveredAtMs", lost.recoveredAtMs());

        String hash = null;
        if (fullSnapshot != null) {
            JsonObject encodedSnapshot = parseEncodedSnapshot(snapshotCodec.encode(fullSnapshot));
            String canonicalSnapshot = encodedSnapshot.toString();
            hash = sha256(canonicalSnapshot);
            payload.add(FULL_SNAPSHOT, encodedSnapshot);
            payload.addProperty(FULL_SNAPSHOT_HASH, hash);
        }
        return new EncodedPayload(payload.toString(), CURRENT_FORMAT_VERSION, fullSnapshot != null, hash);
    }

    DecodeResult decode(String raw) {
        final JsonObject payload;
        try {
            JsonElement parsed = JsonParser.parseString(raw == null ? "" : raw);
            if (!parsed.isJsonObject()) {
                return DecodeResult.failed(LostRecoveryLoadResult.Failure.INVALID_JSON, "root_not_object");
            }
            payload = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            return DecodeResult.failed(
                    LostRecoveryLoadResult.Failure.INVALID_JSON, exception.getClass().getSimpleName());
        }

        try {
            int formatVersion = optionalInt(payload, FORMAT_VERSION, 0);
            boolean hasEnvelopeField = payload.has(SOURCE_UUID)
                    || payload.has(FULL_SNAPSHOT)
                    || payload.has(FULL_SNAPSHOT_HASH);
            if (formatVersion == 0 && hasEnvelopeField) {
                return DecodeResult.failed(
                        LostRecoveryLoadResult.Failure.UNSUPPORTED_FORMAT_VERSION,
                        "missing_" + FORMAT_VERSION);
            }
            if (formatVersion != 0 && formatVersion != CURRENT_FORMAT_VERSION) {
                return DecodeResult.failed(
                        LostRecoveryLoadResult.Failure.UNSUPPORTED_FORMAT_VERSION,
                        Integer.toString(formatVersion));
            }

            UUID sourceUuid = optionalUuid(payload, SOURCE_UUID);
            LostRecoveryEnvelope.LostMetadata metadata = new LostRecoveryEnvelope.LostMetadata(
                    optionalVector(payload, "lastKnownPosition"),
                    optionalVector(payload, "homePosition"),
                    optionalLong(payload, "lastRelocationQueuedAtMs", 0L),
                    optionalLong(payload, "lostAtMs", 0L),
                    optionalInt(payload, "relocationRetryAttempts", 0),
                    optionalUuid(payload, "replacementNpcUuid"),
                    optionalLong(payload, "recoveredAtMs", 0L)
            );
            DecodeResult snapshotResult = decodeFullSnapshot(payload, formatVersion, sourceUuid, metadata);
            if (snapshotResult != null) {
                return snapshotResult;
            }
            return DecodeResult.failed(
                    LostRecoveryLoadResult.Failure.SNAPSHOT_DECODE_FAILED,
                    "snapshot_decode_returned_no_result");
        } catch (FieldFailure failure) {
            return DecodeResult.failed(failure.failure, failure.getMessage());
        }
    }

    private DecodeResult decodeFullSnapshot(JsonObject payload,
                                            int formatVersion,
                                            UUID sourceUuid,
                                            LostRecoveryEnvelope.LostMetadata metadata) throws FieldFailure {
        JsonElement snapshotElement = payload.get(FULL_SNAPSHOT);
        String storedHash = optionalString(payload, FULL_SNAPSHOT_HASH);
        if (snapshotElement == null || snapshotElement.isJsonNull()) {
            if (storedHash != null) {
                return DecodeResult.failed(
                        LostRecoveryLoadResult.Failure.SNAPSHOT_MISSING, "hash_without_snapshot");
            }
            DecodedPayload decoded = new DecodedPayload(
                    formatVersion, sourceUuid, metadata, null, null);
            return DecodeResult.legacy(
                    decoded,
                    sourceUuid == null
                            ? LostRecoveryLoadResult.Failure.SOURCE_MISSING
                            : LostRecoveryLoadResult.Failure.SNAPSHOT_MISSING);
        }
        VerifiedSnapshot verified = verifySnapshot(payload, snapshotElement);
        CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot =
                decodeStrictSnapshot(verified.canonicalJson());
        if (sourceUuid != null && !sourceUuid.equals(fullSnapshot.npcUuid())) {
            return DecodeResult.failed(
                    LostRecoveryLoadResult.Failure.SOURCE_MISMATCH,
                    sourceUuid + "!=" + fullSnapshot.npcUuid());
        }
        DecodedPayload decoded = new DecodedPayload(
                formatVersion, sourceUuid, metadata, fullSnapshot, verified.sha256());
        return sourceUuid == null
                ? DecodeResult.legacy(decoded, LostRecoveryLoadResult.Failure.SOURCE_MISSING)
                : DecodeResult.found(decoded);
    }

    private VerifiedSnapshot verifySnapshot(JsonObject payload,
                                            JsonElement snapshotElement) throws FieldFailure {
        if (!snapshotElement.isJsonObject()) {
            throw failure(LostRecoveryLoadResult.Failure.INVALID_FIELD,
                    FULL_SNAPSHOT + "_not_object");
        }
        String storedHash = optionalString(payload, FULL_SNAPSHOT_HASH);
        if (storedHash == null) {
            throw failure(LostRecoveryLoadResult.Failure.SNAPSHOT_HASH_MISSING, FULL_SNAPSHOT_HASH);
        }
        if (!storedHash.matches("(?i)[0-9a-f]{64}")) {
            throw failure(LostRecoveryLoadResult.Failure.SNAPSHOT_HASH_INVALID, "invalid_hash_format");
        }
        String canonicalSnapshot = snapshotElement.getAsJsonObject().toString();
        String computedHash = sha256(canonicalSnapshot);
        if (!MessageDigest.isEqual(
                storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                computedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw failure(LostRecoveryLoadResult.Failure.SNAPSHOT_HASH_INVALID, "hash_mismatch");
        }
        return new VerifiedSnapshot(canonicalSnapshot, computedHash);
    }

    private CoopResidentStateSnapshotService.CoopResidentStateSnapshot decodeStrictSnapshot(
            String canonicalSnapshot) throws FieldFailure {
        CoopResidentStateSnapshotCodec.DecodeResult snapshotDecode = snapshotCodec.decode(canonicalSnapshot);
        if (snapshotDecode.status() != CoopResidentStateSnapshotCodec.Status.FOUND
                || snapshotDecode.snapshot() == null) {
            String detail = snapshotDecode.failure() != null
                    ? snapshotDecode.failure().name() + ":" + snapshotDecode.field()
                    : "snapshot_not_found";
            throw failure(LostRecoveryLoadResult.Failure.SNAPSHOT_DECODE_FAILED, detail);
        }
        return snapshotDecode.snapshot();
    }

    private JsonObject parseEncodedSnapshot(String encoded) {
        try {
            return JsonParser.parseString(encoded).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Strict snapshot codec emitted invalid JSON", exception);
        }
    }

    private void putVector(JsonObject target, String key, Vector3d value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException("Lost vector must be finite: " + key);
        }
        JsonObject vector = new JsonObject();
        vector.addProperty("x", value.x);
        vector.addProperty("y", value.y);
        vector.addProperty("z", value.z);
        target.add(key, vector);
    }

    private Vector3d optionalVector(JsonObject source, String key) throws FieldFailure {
        JsonElement element = source.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw fieldFailure(key, "expected_object");
        }
        JsonObject vector = element.getAsJsonObject();
        double x = requiredFiniteDouble(vector, "x", key);
        double y = requiredFiniteDouble(vector, "y", key);
        double z = requiredFiniteDouble(vector, "z", key);
        return new Vector3d(x, y, z);
    }

    private double requiredFiniteDouble(JsonObject source,
                                        String field,
                                        String parent) throws FieldFailure {
        JsonElement element = source.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw fieldFailure(parent + "." + field, "expected_number");
        }
        try {
            double value = element.getAsDouble();
            if (!Double.isFinite(value)) {
                throw fieldFailure(parent + "." + field, "non_finite");
            }
            return value;
        } catch (FieldFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw fieldFailure(parent + "." + field, exception.getClass().getSimpleName());
        }
    }

    private long optionalLong(JsonObject source, String field, long fallback) throws FieldFailure {
        JsonElement element = source.get(field);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw fieldFailure(field, "expected_integer");
        }
        try {
            return element.getAsBigDecimal().longValueExact();
        } catch (RuntimeException exception) {
            throw fieldFailure(field, exception.getClass().getSimpleName());
        }
    }

    private int optionalInt(JsonObject source, String field, int fallback) throws FieldFailure {
        long value = optionalLong(source, field, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw fieldFailure(field, "integer_out_of_range");
        }
        return (int) value;
    }

    private UUID optionalUuid(JsonObject source, String field) throws FieldFailure {
        String raw = optionalString(source, field);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw fieldFailure(field, "invalid_uuid");
        }
    }

    private String optionalString(JsonObject source, String field) throws FieldFailure {
        JsonElement element = source.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw fieldFailure(field, "expected_string");
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(Character.forDigit((current >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(current & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private FieldFailure fieldFailure(String field, String detail) {
        return failure(LostRecoveryLoadResult.Failure.INVALID_FIELD, field + ":" + detail);
    }

    private FieldFailure failure(LostRecoveryLoadResult.Failure failure, String detail) {
        return new FieldFailure(failure, detail);
    }

    record EncodedPayload(String payloadJson,
                          int formatVersion,
                          boolean fullSnapshotStored,
                          String fullSnapshotSha256) {
    }

    record DecodedPayload(int formatVersion,
                          UUID sourceNpcUuid,
                          LostRecoveryEnvelope.LostMetadata metadata,
                          CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot,
                          String fullSnapshotSha256) {
    }

    private record VerifiedSnapshot(String canonicalJson, String sha256) {
    }

    enum Status {
        FOUND,
        LEGACY_UNVERIFIED,
        FAILED
    }

    record DecodeResult(Status status,
                        DecodedPayload payload,
                        LostRecoveryLoadResult.Failure failure,
                        String detail) {
        static DecodeResult found(DecodedPayload payload) {
            return new DecodeResult(Status.FOUND, payload, null, null);
        }

        static DecodeResult legacy(DecodedPayload payload, LostRecoveryLoadResult.Failure failure) {
            return new DecodeResult(Status.LEGACY_UNVERIFIED, payload, failure, null);
        }

        static DecodeResult failed(LostRecoveryLoadResult.Failure failure, String detail) {
            return new DecodeResult(Status.FAILED, null, failure, detail);
        }
    }

    private static final class FieldFailure extends Exception {
        private final LostRecoveryLoadResult.Failure failure;

        private FieldFailure(LostRecoveryLoadResult.Failure failure, String detail) {
            super(detail);
            this.failure = failure;
        }
    }
}
