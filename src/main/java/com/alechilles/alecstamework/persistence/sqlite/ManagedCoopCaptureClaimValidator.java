package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.CaptureRequest;

/** Canonical identity and full-snapshot validation for atomic managed-coop capture claims. */
public final class ManagedCoopCaptureClaimValidator {
    private static final String RESIDENT_PREFIX = "managed-coop-resident:";
    private static final String OPERATION_PREFIX = "managed-coop-capture:";

    private ManagedCoopCaptureClaimValidator() {
    }

    /** Returns the canonical resident ID used by all newly established capture assignments. */
    @Nonnull
    public static String residentId(@Nonnull String profileId) {
        return RESIDENT_PREFIX + sha256(requireText(profileId, "profileId"));
    }

    /** Returns the deterministic operation ID for the complete persisted capture identity. */
    @Nonnull
    public static String operationId(@Nonnull CaptureRequest request) {
        if (request == null || request.authorityKey() == null || request.sourceNpcUuid() == null) {
            throw new IllegalArgumentException("complete capture identity is required");
        }
        String identity = token(request.profileId())
                + token(request.authorityKey().authorityId())
                + token(normalizeRequired(request.coopId(), "coopId"))
                + token(Integer.toString(request.residentSlot()))
                + token(request.sourceNpcUuid().toString())
                + token(normalizeHash(request.snapshotHash()))
                + token(Long.toString(request.expectedResidentGeneration()));
        return OPERATION_PREFIX + sha256(identity);
    }

    /** Computes the lowercase SHA-256 persisted beside the exact UTF-8 snapshot JSON. */
    @Nonnull
    public static String snapshotSha256(@Nonnull String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshotJson must not be blank");
        }
        return sha256(snapshotJson);
    }

    /** Fails closed unless IDs, hash, and snapshot metadata form one canonical replay bundle. */
    public static void validate(@Nonnull CaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("capture request is required");
        }
        String snapshotJson = request.snapshotJson();
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new IllegalArgumentException("snapshotJson must not be blank");
        }
        String snapshotHash = normalizeHash(request.snapshotHash());
        if (!snapshotHash.equals(snapshotSha256(snapshotJson))) {
            throw new IllegalArgumentException("snapshotHash does not verify snapshotJson");
        }
        if (!residentId(request.profileId()).equals(request.residentId())
                && !isLegacyResidentId(request)) {
            throw new IllegalArgumentException(
                    "residentId is neither canonical nor the exact legacy assignment");
        }
        if (!operationId(request).equals(request.operationId())) {
            throw new IllegalArgumentException("operationId is not canonical for capture identity");
        }
        if (request.snapshotVersion() < 1 || request.residentSlot() < 0
                || request.expectedResidentGeneration() < 0L) {
            throw new IllegalArgumentException("snapshot version, slot, and generation must be valid");
        }
        validateSnapshotMetadata(request, snapshotJson);
    }

    /** True only for the location-derived ID emitted by schema-v5 legacy coop migration. */
    static boolean isLegacyResidentId(@Nonnull CaptureRequest request) {
        if (request == null || request.authorityKey() == null) {
            return false;
        }
        String expected = "legacy:"
                + request.authorityKey().worldName() + ":"
                + normalizeRequired(request.coopId(), "coopId") + ":"
                + request.authorityKey().x() + ":"
                + request.authorityKey().y() + ":"
                + request.authorityKey().z() + ":"
                + request.residentSlot();
        return expected.equalsIgnoreCase(requireText(request.residentId(), "residentId"));
    }

    private static void validateSnapshotMetadata(CaptureRequest request, String snapshotJson) {
        final JsonObject snapshot;
        try {
            JsonElement parsed = JsonParser.parseString(snapshotJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("snapshot root must be an object");
            }
            snapshot = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("snapshotJson is not valid object JSON", exception);
        }
        String version = requiredString(snapshot, "version");
        String npcUuid = requiredString(snapshot, "npcUuid");
        String coopId = normalizeRequired(requiredString(snapshot, "coopId"), "snapshot.coopId");
        String roleId = normalizeRequired(requiredString(snapshot, "roleId"), "snapshot.roleId");
        int residentSlot = requiredInt(snapshot, "residentSlot");
        if (!Integer.toString(request.snapshotVersion()).equals(version)
                || !request.sourceNpcUuid().toString().equalsIgnoreCase(npcUuid)
                || !normalizeRequired(request.coopId(), "coopId").equals(coopId)
                || !normalizeRequired(request.roleId(), "roleId").equals(roleId)
                || request.residentSlot() != residentSlot) {
            throw new IllegalArgumentException("snapshot metadata does not match capture request");
        }
    }

    @Nonnull
    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("snapshot field must be a string: " + field);
        }
        return requireText(value.getAsString(), "snapshot." + field);
    }

    private static int requiredInt(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("snapshot field must be an integer: " + field);
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("snapshot field must be an integer: " + field, exception);
        }
    }

    @Nonnull
    private static String normalizeHash(@Nullable String value) {
        String raw = requireText(value, "snapshotHash");
        String hash = raw.toLowerCase(Locale.ROOT);
        if (hash.length() != 64 || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshotHash must be a 64-character SHA-256");
        }
        if (!raw.equals(hash)) {
            throw new IllegalArgumentException("snapshotHash must use canonical lowercase hex");
        }
        return hash;
    }

    @Nonnull
    private static String normalizeRequired(@Nullable String value, String field) {
        return requireText(value, field).toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static String token(@Nullable String value) {
        String required = requireText(value, "capture identity token");
        return required.length() + ":" + required;
    }

    @Nonnull
    private static String sha256(@Nonnull String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
