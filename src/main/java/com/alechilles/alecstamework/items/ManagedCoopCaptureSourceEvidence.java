package com.alechilles.alecstamework.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Versioned source-kind marker embedded in managed-coop resident snapshots.
 *
 * <p>Captured-item sources are already absent from the world. Recovery must therefore require an
 * item-retirement receipt rather than interpreting entity absence as successful retirement.</p>
 */
public final class ManagedCoopCaptureSourceEvidence {
    public static final String SNAPSHOT_FIELD = "_tameworkCaptureSource";
    public static final String ITEM_KIND = "CAPTURED_ITEM";
    private static final String CURRENT_VERSION = "1";

    public enum Status {
        ENTITY_SOURCE,
        CAPTURED_ITEM,
        INVALID
    }

    /** Exact player inventory location and immutable item fingerprint used for retirement proof. */
    public record CapturedItemSource(@Nonnull UUID playerUuid,
                                     short hotbarSlot,
                                     @Nonnull String itemId,
                                     @Nonnull String itemFingerprint) {
        public CapturedItemSource {
            Objects.requireNonNull(playerUuid, "playerUuid");
            itemId = requireText(itemId, "itemId");
            itemFingerprint = requireSha256(itemFingerprint);
            if (hotbarSlot < 0) {
                throw new IllegalArgumentException("hotbarSlot must not be negative");
            }
        }
    }

    public record ReadResult(@Nonnull Status status,
                             @Nullable CapturedItemSource capturedItem,
                             @Nullable String detail) {
        public ReadResult {
            Objects.requireNonNull(status, "status");
        }
    }

    private ManagedCoopCaptureSourceEvidence() {
    }

    /** Adds exact item-source recovery evidence without changing the version-1 snapshot body. */
    @Nonnull
    public static String markCapturedItem(@Nonnull String snapshotJson,
                                          @Nonnull CapturedItemSource source) {
        Objects.requireNonNull(source, "source");
        JsonObject root = parseObject(snapshotJson);
        if (root.has(SNAPSHOT_FIELD)) {
            throw new IllegalArgumentException("snapshot capture source is already marked");
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("version", CURRENT_VERSION);
        marker.addProperty("kind", ITEM_KIND);
        marker.addProperty("playerUuid", source.playerUuid().toString());
        marker.addProperty("hotbarSlot", source.hotbarSlot());
        marker.addProperty("itemId", source.itemId());
        marker.addProperty("itemFingerprint", source.itemFingerprint());
        root.add(SNAPSHOT_FIELD, marker);
        return root.toString();
    }

    /** Distinguishes ordinary entity capture from strictly verified captured-item evidence. */
    @Nonnull
    public static ReadResult read(@Nullable String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return invalid("snapshot_json_missing");
        }
        try {
            JsonObject root = parseObject(snapshotJson);
            JsonElement raw = root.get(SNAPSHOT_FIELD);
            if (raw == null) {
                return new ReadResult(Status.ENTITY_SOURCE, null, null);
            }
            if (!raw.isJsonObject()) {
                return invalid("capture_source_marker_not_object");
            }
            JsonObject marker = raw.getAsJsonObject();
            if (!CURRENT_VERSION.equals(requiredString(marker, "version"))
                    || !ITEM_KIND.equals(requiredString(marker, "kind"))) {
                return invalid("capture_source_marker_version_or_kind_invalid");
            }
            CapturedItemSource source = new CapturedItemSource(
                    UUID.fromString(requiredString(marker, "playerUuid")),
                    requiredShort(marker, "hotbarSlot"),
                    requiredString(marker, "itemId"),
                    requiredString(marker, "itemFingerprint")
            );
            return new ReadResult(Status.CAPTURED_ITEM, source, null);
        } catch (RuntimeException exception) {
            return invalid("capture_source_marker_invalid:" + exceptionName(exception));
        }
    }

    @Nonnull
    private static JsonObject parseObject(String raw) {
        JsonElement parsed = JsonParser.parseString(requireText(raw, "snapshotJson"));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("snapshot root must be an object");
        }
        return parsed.getAsJsonObject();
    }

    @Nonnull
    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("marker field must be a string: " + field);
        }
        return requireText(value.getAsString(), field);
    }

    private static short requiredShort(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("marker field must be an integer: " + field);
        }
        return value.getAsBigDecimal().shortValueExact();
    }

    @Nonnull
    private static ReadResult invalid(String detail) {
        return new ReadResult(Status.INVALID, null, detail);
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    @Nonnull
    private static String requireSha256(@Nullable String value) {
        String hash = requireText(value, "itemFingerprint");
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("itemFingerprint must be canonical lowercase SHA-256");
        }
        return hash;
    }

    @Nonnull
    private static String exceptionName(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
