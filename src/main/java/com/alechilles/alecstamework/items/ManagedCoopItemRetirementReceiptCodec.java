package com.alechilles.alecstamework.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Strict metadata codec for the non-spawnable item receipt created before capture finalization. */
public final class ManagedCoopItemRetirementReceiptCodec {
    public static final String METADATA_KEY = "Tamework.ManagedCoop.ItemRetirementV1";
    private static final String CURRENT_VERSION = "1";

    public enum Status {
        FOUND,
        NOT_FOUND,
        INVALID
    }

    public record Receipt(@Nonnull String operationId, @Nonnull String itemFingerprint) {
        public Receipt {
            operationId = requireText(operationId, "operationId");
            itemFingerprint = requireSha256(itemFingerprint);
        }
    }

    public record DecodeResult(@Nonnull Status status,
                               @Nullable Receipt receipt,
                               @Nullable String detail) {
        public DecodeResult {
            Objects.requireNonNull(status, "status");
        }
    }

    @Nonnull
    public String encode(@Nonnull String operationId, @Nonnull String itemFingerprint) {
        Receipt receipt = new Receipt(operationId, itemFingerprint);
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("operationId", receipt.operationId());
        root.addProperty("itemFingerprint", receipt.itemFingerprint());
        return root.toString();
    }

    @Nonnull
    public DecodeResult decode(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return new DecodeResult(Status.NOT_FOUND, null, "item_retirement_receipt_missing");
        }
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return invalid("item_retirement_receipt_root_invalid");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!CURRENT_VERSION.equals(requiredString(root, "version"))) {
                return invalid("item_retirement_receipt_version_invalid");
            }
            return new DecodeResult(
                    Status.FOUND,
                    new Receipt(
                            requiredString(root, "operationId"),
                            requiredString(root, "itemFingerprint")),
                    null
            );
        } catch (RuntimeException exception) {
            return invalid("item_retirement_receipt_invalid:" + exceptionName(exception));
        }
    }

    @Nonnull
    private DecodeResult invalid(String detail) {
        return new DecodeResult(Status.INVALID, null, detail);
    }

    @Nonnull
    private static String requiredString(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("receipt field must be a string: " + field);
        }
        return requireText(value.getAsString(), field);
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
        String fingerprint = requireText(value, "itemFingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "itemFingerprint must be canonical lowercase SHA-256");
        }
        return fingerprint;
    }

    @Nonnull
    private static String exceptionName(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
